package com.wing.folderplayer.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

data class CastState(
    val discovering: Boolean = false,
    val renderers: List<Renderer> = emptyList(),
    val active: Renderer? = null,
    val title: String = "",
    /** Last state reported by the renderer (PLAYING / PAUSED_PLAYBACK / STOPPED / TRANSITIONING …) or null if unknown. */
    val rendererState: String? = null,
    /** Command we sent last; shown separately from what the renderer reported. */
    val lastCommand: String? = null,
    val positionMs: Long = -1,
    val durationMs: Long = -1,
    /** False when the renderer does not report positions; the UI then shows only the last command. */
    val progressAvailable: Boolean = false,
    val error: String? = null,
    /** A cast was requested and has not finished starting (or failed) yet. */
    val connecting: Boolean = false,
) {
    /** A cast is starting or running: playback is (about to be) on the renderer. */
    val sessionInProgress: Boolean get() = active != null || connecting
}

/** Wi-Fi multicast lock for discovery; Wi-Fi + wake lock while a session is active. */
interface CastLocks {
    fun acquireDiscovery()
    fun releaseDiscovery()
    fun acquireSession()
    fun releaseSession()
}

class AndroidCastLocks(private val context: Context) : CastLocks {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized override fun acquireDiscovery() {
        if (multicastLock == null) {
            val wifi = context.getSystemService(WifiManager::class.java)
            multicastLock = wifi.createMulticastLock("fp-dlna").apply { setReferenceCounted(false); acquire() }
        }
    }

    @Synchronized override fun releaseDiscovery() {
        runCatching { multicastLock?.release() }; multicastLock = null
    }

    @Synchronized override fun acquireSession() {
        if (wifiLock == null) {
            val wifi = context.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fp-cast").apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            val pm = context.getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fp:cast").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
    }

    @Synchronized override fun releaseSession() {
        runCatching { wifiLock?.release() }; wifiLock = null
        runCatching { wakeLock?.release() }; wakeLock = null
    }
}

/**
 * Android side of DLNA casting: Wi-Fi multicast lock for discovery, Wi-Fi + wake lock while casting, relay lifetime
 * bound to the session, renderer state polling (1 s). Disabled unless the user turns casting on in Settings.
 *
 * cast / stop / shutdown and the Play / Pause / Seek commands run one at a time. cast / stop / shutdown take a new
 * generation number when called (not when their coroutine runs); only the operation holding the latest number owns
 * the relay, the session locks and [CastState.active]. An older operation that finishes or fails late only undoes what
 * it sent to its own renderer. A command is dropped when a newer generation exists by the time it may start.
 */
class CastController internal constructor(
    private val isEnabled: () -> Boolean,
    private val cp: RendererControl,
    private val resolve: (SourceRef) -> SourceFileSystem,
    private val locks: CastLocks,
    relayPort: Int = RelayServer.DEFAULT_PORT,
    private val pollIntervalMs: Long = 1000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val relay = RelayServer(resolve, relayPort)
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()
    private val session = Mutex()
    private val generation = AtomicLong()
    @Volatile private var poll: Job? = null
    @Volatile var onRendererFinished: (() -> Unit)? = null

    val enabled: Boolean get() = isEnabled()

    /** Thrown inside a session operation when a newer cast / stop was requested meanwhile. */
    private class Superseded : Exception()

    private fun ensureCurrent(gen: Long) { if (gen != generation.get()) throw Superseded() }

    init {
        cp.onChange = { _state.update { it.copy(renderers = cp.renderers()) } }
    }

    fun startDiscovery() {
        if (!enabled) return
        scope.launch {
            try {
                locks.acquireDiscovery()
                _state.update { it.copy(discovering = true, error = null) }
                cp.start()
                repeat(3) { cp.search(); delay(2000) }
                _state.update { it.copy(discovering = false, renderers = cp.renderers()) }
            } catch (e: Exception) {
                _state.update { it.copy(discovering = false, error = e.message) }
            }
        }
    }

    /** Casts [ref] to [renderer]; credentials stay on the phone, the renderer only sees the relay URL. */
    fun cast(renderer: Renderer, ref: SourceRef, title: String, artist: String?) {
        val gen = generation.incrementAndGet()
        _state.update { it.copy(connecting = true) }
        scope.launch {
            session.withLock {
                if (gen != generation.get()) return@withLock
                try {
                    var uriSent = false
                    try {
                        poll?.cancel()
                        // The previous session's renderer would keep requesting a revoked URL.
                        _state.value.active?.takeIf { it.udn != renderer.udn }?.let { prev -> runCatching { cp.stop(prev.udn) } }
                        locks.acquireSession()
                        relay.revokeAll()
                        val port = relay.start()
                        val path = relay.register(ref)
                        val st = resolve(ref).stat(ref.path)
                        val url = "http://${localAddressFor(renderer)}:$port$path"
                        val mime = HttpRange.mimeFor(ref.name)
                        ensureCurrent(gen)
                        cp.setUri(renderer.udn, url, DlnaControlPoint.didl(title, artist, url, mime, st?.size ?: -1))
                        uriSent = true
                        ensureCurrent(gen)
                        _state.update { it.copy(active = renderer, title = title, lastCommand = "SetAVTransportURI", error = null) }
                        cp.play(renderer.udn)
                        ensureCurrent(gen)
                        _state.update { it.copy(lastCommand = "Play") }
                        startPolling(renderer, gen)
                    } catch (e: Superseded) {
                        // The newer operation owns the relay, the locks and the state.
                        if (uriSent) runCatching { cp.stop(renderer.udn) }
                    } catch (e: Exception) {
                        if (gen == generation.get()) {
                            // A half-started session must not keep the relay, its tokens or the locks alive.
                            endSession(renderer.udn.takeIf { uriSent || _state.value.active?.udn == it })
                            _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                        } else if (uriSent) {
                            runCatching { cp.stop(renderer.udn) }
                        }
                    }
                } finally {
                    // The latest operation decides; a superseded one leaves the flag to it.
                    if (gen == generation.get()) _state.update { it.copy(connecting = false) }
                }
            }
        }
    }

    private fun endSession(stopUdn: String?) {
        poll?.cancel()
        poll = null
        try { if (stopUdn != null) cp.stop(stopUdn) } catch (_: Exception) {}
        relay.stop()
        locks.releaseSession()
        _state.update { it.copy(active = null, rendererState = null, positionMs = -1, durationMs = -1, progressAvailable = false) }
    }

    /**
     * Play / Pause / Seek belong to the session that was current when the button was pressed: they take the session
     * lock like cast / stop (so one never talks to a renderer while the session around it changes), and are dropped
     * unmodified when a stop or another cast was requested since, or the renderer is no longer the active one. A
     * command already talking to the renderer cannot be recalled, but cast / stop wait for it, and its answer — success
     * or failure — is not written into a session that has moved on.
     */
    private fun command(name: String, block: (String) -> Unit) {
        val gen = generation.get()
        val target = _state.value.active?.udn ?: return
        scope.launch {
            session.withLock {
                if (gen != generation.get()) return@withLock
                val r = _state.value.active?.takeIf { it.udn == target } ?: return@withLock
                // Written only while the session is still the one the command was pressed for.
                fun write(change: (CastState) -> CastState) =
                    _state.update { if (gen == generation.get() && it.active?.udn == r.udn) change(it) else it }
                try {
                    block(r.udn)
                    write { it.copy(lastCommand = name, error = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    write { it.copy(error = "$name: ${e.message}") }
                }
            }
        }
    }

    fun play() = command("Play") { cp.play(it) }
    fun pause() = command("Pause") { cp.pause(it) }
    fun seek(ms: Long) = command("Seek") { cp.seek(it, ms) }

    /** Stops the renderer and ends the session: tokens revoked, relay stopped, locks released. */
    fun stop() {
        val gen = generation.incrementAndGet()
        poll?.cancel()
        scope.launch {
            session.withLock {
                if (gen != generation.get()) return@withLock
                endSession(_state.value.active?.udn)
                _state.update { it.copy(lastCommand = "Stop", connecting = false) }
            }
        }
    }

    private fun startPolling(r: Renderer, gen: Long) {
        poll?.cancel()
        poll = scope.launch {
            var sawPlaying = false
            // State is written only while this session is still the current one, so a renderer answer that arrives
            // after stop() cannot bring the session back.
            fun write(change: (CastState) -> CastState) =
                _state.update { if (gen == generation.get() && it.active?.udn == r.udn) change(it) else it }
            while (isActive && gen == generation.get()) {
                try {
                    val t = cp.transportState(r.udn)
                    val (pos, dur) = runCatching { cp.progress(r.udn) }.getOrDefault(-1L to -1L)
                    write { it.copy(rendererState = t, positionMs = pos, durationMs = dur, progressAvailable = pos >= 0) }
                    if (t == "PLAYING") sawPlaying = true
                    if (sawPlaying && t == "STOPPED" && _state.value.lastCommand != "Stop" && gen == generation.get()) {
                        sawPlaying = false
                        onRendererFinished?.invoke()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    write { it.copy(rendererState = null, error = e.message) }
                }
                delay(pollIntervalMs)
            }
        }
    }

    /** Called when casting is switched off or the app exits. */
    fun shutdown() {
        val gen = generation.incrementAndGet()
        poll?.cancel()
        scope.launch {
            session.withLock {
                if (gen != generation.get()) return@withLock
                endSession(_state.value.active?.udn)
                cp.shutdown()
                locks.releaseDiscovery()
                _state.value = CastState()
            }
        }
    }

    companion object {
        private fun localAddressFor(renderer: Renderer): String {
            DatagramSocket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getByName(renderer.address), 1900))
                return s.localAddress.hostAddress ?: throw IllegalStateException("no local address")
            }
        }

        // Holds only the application context, which lives as long as the process.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: CastController? = null
        /** Instrumentation tests only: replaces the shared controller (e.g. one with a fake renderer); null restores it. */
        @androidx.annotation.VisibleForTesting
        internal fun replaceInstanceForTest(controller: CastController?) { instance = controller }

        fun get(context: Context): CastController = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                CastController({ CastSettings(app).enabled }, DlnaControlPoint(), { ref -> SourceRegistry.fileSystem(ref) }, AndroidCastLocks(app))
            }.also { instance = it }
        }
    }
}

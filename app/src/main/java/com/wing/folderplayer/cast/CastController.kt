package com.wing.folderplayer.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

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
)

/**
 * Android side of DLNA casting: Wi-Fi multicast lock for discovery, Wi-Fi + wake lock while casting, relay lifetime
 * bound to the session, renderer state polling (1 s). Disabled unless the user turns casting on in Settings.
 */
class CastController private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cp = DlnaControlPoint()
    private val relay = RelayServer({ ref -> SourceRegistry.fileSystem(ref) })
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()
    private var poll: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile var onRendererFinished: (() -> Unit)? = null

    val enabled: Boolean get() = CastSettings(context).enabled

    init {
        cp.onChange = { _state.value = _state.value.copy(renderers = cp.renderers()) }
    }

    fun startDiscovery() {
        if (!enabled) return
        scope.launch {
            try {
                if (multicastLock == null) {
                    val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
                    multicastLock = wifi.createMulticastLock("fp-dlna").apply { setReferenceCounted(false); acquire() }
                }
                _state.value = _state.value.copy(discovering = true, error = null)
                cp.start()
                repeat(3) { cp.search(); delay(2000) }
                _state.value = _state.value.copy(discovering = false, renderers = cp.renderers())
            } catch (e: Exception) {
                _state.value = _state.value.copy(discovering = false, error = e.message)
            }
        }
    }

    private fun localAddressFor(renderer: Renderer): String {
        DatagramSocket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getByName(renderer.address), 1900))
            return s.localAddress.hostAddress ?: throw IllegalStateException("no local address")
        }
    }

    /** Casts [ref] to [renderer]; credentials stay on the phone, the renderer only sees the relay URL. */
    fun cast(renderer: Renderer, ref: SourceRef, title: String, artist: String?) {
        scope.launch {
            try {
                acquireSessionLocks()
                relay.revokeAll()
                val port = relay.start()
                val path = relay.register(ref)
                val st = SourceRegistry.fileSystem(ref).stat(ref.path)
                val url = "http://${localAddressFor(renderer)}:$port$path"
                val mime = HttpRange.mimeFor(ref.name)
                cp.setUri(renderer.udn, url, DlnaControlPoint.didl(title, artist, url, mime, st?.size ?: -1))
                _state.value = _state.value.copy(active = renderer, title = title, lastCommand = "SetAVTransportURI", error = null)
                cp.play(renderer.udn)
                _state.value = _state.value.copy(lastCommand = "Play")
                startPolling(renderer)
            } catch (e: Exception) {
                // A half-started session must not keep the relay, its tokens or the locks alive.
                endSession(renderer.udn.takeIf { _state.value.active?.udn == it })
                _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun endSession(stopUdn: String?) {
        poll?.cancel()
        try { if (stopUdn != null) cp.stop(stopUdn) } catch (_: Exception) {}
        relay.stop()
        releaseSessionLocks()
        _state.value = _state.value.copy(active = null, rendererState = null, positionMs = -1, durationMs = -1, progressAvailable = false)
    }

    private fun command(name: String, block: (String) -> Unit) {
        val r = _state.value.active ?: return
        scope.launch {
            try {
                block(r.udn)
                _state.value = _state.value.copy(lastCommand = name, error = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "$name: ${e.message}")
            }
        }
    }

    fun play() = command("Play") { cp.play(it) }
    fun pause() = command("Pause") { cp.pause(it) }
    fun seek(ms: Long) = command("Seek") { cp.seek(it, ms) }

    /** Stops the renderer and ends the session: tokens revoked, relay stopped, locks released. */
    fun stop() {
        val r = _state.value.active
        poll?.cancel()
        scope.launch {
            endSession(r?.udn)
            _state.value = _state.value.copy(lastCommand = "Stop")
        }
    }

    private fun startPolling(r: Renderer) {
        poll?.cancel()
        poll = scope.launch {
            var sawPlaying = false
            while (isActive && _state.value.active?.udn == r.udn) {
                try {
                    val t = cp.transport(r.udn).currentTransportState?.value
                    val p = runCatching { cp.position(r.udn) }.getOrNull()
                    val pos = DlnaControlPoint.parseTime(p?.relTime)
                    val dur = DlnaControlPoint.parseTime(p?.trackDuration)
                    _state.value = _state.value.copy(rendererState = t, positionMs = pos, durationMs = dur, progressAvailable = pos >= 0)
                    if (t == "PLAYING") sawPlaying = true
                    if (sawPlaying && t == "STOPPED" && _state.value.lastCommand != "Stop") {
                        sawPlaying = false
                        onRendererFinished?.invoke()
                    }
                } catch (e: Exception) {
                    _state.value = _state.value.copy(rendererState = null, error = e.message)
                }
                delay(1000)
            }
        }
    }

    private fun acquireSessionLocks() {
        if (wifiLock == null) {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fp-cast").apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            val pm = context.getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fp:cast").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
    }

    private fun releaseSessionLocks() {
        runCatching { wifiLock?.release() }; wifiLock = null
        runCatching { wakeLock?.release() }; wakeLock = null
    }

    /** Called when casting is switched off or the app exits. */
    fun shutdown() {
        stop()
        scope.launch {
            cp.shutdown()
            runCatching { multicastLock?.release() }; multicastLock = null
            _state.value = CastState()
        }
    }

    companion object {
        // Holds only the application context, which lives as long as the process.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: CastController? = null
        fun get(context: Context): CastController =
            instance ?: synchronized(this) { instance ?: CastController(context.applicationContext).also { instance = it } }
    }
}

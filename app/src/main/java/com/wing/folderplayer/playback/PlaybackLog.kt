package com.wing.folderplayer.playback

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.media3.common.Player
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

enum class PlaybackEventKind {
    SERVICE_START, SERVICE_STOP, PROCESS_EXIT, PLAY, PAUSE, SUPPRESSED, STATE, TRACK, COMMAND, NOISY, SLEEP_TIMER, ERROR,
    RETRY, RETRY_RECOVERED, RETRY_GAVE_UP, RETRY_CANCELLED,
    BUFFERING, LOAD_ERROR, HEARTBEAT, MEMORY,
}

data class PlaybackEvent(val time: Long, val kind: PlaybackEventKind, val detail: String)

/** A process end Android recorded ([ApplicationExitInfo]), with its reason already named. */
/** [status]: the signal or exit code; [pssKb] / [rssKb]: the memory the process used at the end (0: not known). */
data class ProcessExit(
    val timestamp: Long, val reason: String, val description: String?, val importance: Int,
    val status: Int = 0, val pssKb: Long = 0, val rssKb: Long = 0,
)

/** The log as text: one event per line, `yyyy-MM-dd HH:mm:ss.SSS KIND detail`, in the device's local time. */
object PlaybackLogFormat {
    const val KEEP = 500
    const val MAX_LINES = 1000

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private const val TIME_LENGTH = 23

    fun encode(event: PlaybackEvent, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = TIME.format(Instant.ofEpochMilli(event.time).atZone(zone))
        val detail = event.detail.replace('\n', ' ').replace('\r', ' ')
        return if (detail.isEmpty()) "$time ${event.kind.name}" else "$time ${event.kind.name} $detail"
    }

    /** The event of a line written by [encode], or null for anything else (a line cut short by a crash). */
    fun parse(line: String, zone: ZoneId = ZoneId.systemDefault()): PlaybackEvent? {
        if (line.length <= TIME_LENGTH || line[TIME_LENGTH] != ' ') return null
        return runCatching {
            val time = LocalDateTime.parse(line.substring(0, TIME_LENGTH), TIME).atZone(zone).toInstant().toEpochMilli()
            val rest = line.substring(TIME_LENGTH + 1)
            PlaybackEvent(time, PlaybackEventKind.valueOf(rest.substringBefore(' ')), rest.substringAfter(' ', ""))
        }.getOrNull()
    }

    /** The lines the file keeps: the last [KEEP] once it has grown past [MAX_LINES]. */
    fun bound(lines: List<String>): List<String> = if (lines.size > MAX_LINES) lines.takeLast(KEEP) else lines

    /** Exits not logged yet (after [lastLogged]), oldest first; Android lists them newest first. */
    fun newExits(exits: List<ProcessExit>, lastLogged: Long): List<ProcessExit> =
        exits.filter { it.timestamp > lastLogged }.sortedBy { it.timestamp }

    fun exitDetail(exit: ProcessExit) =
        "reason=${exit.reason} status=${exit.status} importance=${exit.importance} pss=${exit.pssKb / 1024}MB rss=${exit.rssKb / 1024}MB desc=${exit.description.orEmpty()}"

    /** Error texts can quote a URL; the log is meant to be shared, so `user:password@` never goes into it. */
    fun withoutUserInfo(text: String): String = USER_INFO.replace(text, "$1")

    private val USER_INFO = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\s@]+@""")

    fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }
}

/** Names of Media3 player values for the log; a value this table does not know is logged as its number. */
internal object PlayerNames {
    fun playWhenReadyReason(reason: Int): String = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
        else -> "REASON_$reason"
    }

    fun suppressionReason(reason: Int): String = when (reason) {
        Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "NONE"
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "TRANSIENT_AUDIO_FOCUS_LOSS"
        else -> "REASON_$reason"
    }

    fun transitionReason(reason: Int): String = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
        else -> "REASON_$reason"
    }

    /** The controller commands that start, stop or change what plays; null for the others (not logged). */
    fun loggedCommand(command: Int): String? = when (command) {
        Player.COMMAND_PLAY_PAUSE -> "PLAY_PAUSE"
        Player.COMMAND_STOP -> "STOP"
        Player.COMMAND_SEEK_TO_NEXT -> "SEEK_TO_NEXT"
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> "SEEK_TO_NEXT_MEDIA_ITEM"
        Player.COMMAND_SEEK_TO_PREVIOUS -> "SEEK_TO_PREVIOUS"
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> "SEEK_TO_PREVIOUS_MEDIA_ITEM"
        Player.COMMAND_SET_MEDIA_ITEM -> "SET_MEDIA_ITEM"
        Player.COMMAND_CHANGE_MEDIA_ITEMS -> "CHANGE_MEDIA_ITEMS"
        else -> null
    }
}

/**
 * What started, paused or stopped playback and why, kept on the device (`filesDir/diagnostics/playback.log`, the last
 * [PlaybackLogFormat.KEEP] events) so the owner can share it after playback stopped by itself. Process-wide; every
 * file operation runs on one background thread, in the order it was asked for.
 */
object PlaybackLog {
    private const val TAG = "PlaybackLog"
    private const val PREFS = "playback_log"
    private const val KEY_LAST_EXIT = "last_exit_ts"

    private val io = Executors.newSingleThreadExecutor { Thread(it, "playback-log").apply { isDaemon = true } }
    @Volatile private var log: LogFile? = null

    @Synchronized fun init(context: Context) {
        if (log == null) log = LogFile(File(context.applicationContext.filesDir, "diagnostics/playback.log"))
    }

    /** For tests: log to [file] from now on (null: not at all). */
    @Synchronized internal fun open(file: File?) {
        log = file?.let(::LogFile)
    }

    /** Appends an event in the background; nothing happens before [init]. */
    fun record(kind: PlaybackEventKind, detail: String, time: Long = System.currentTimeMillis()) {
        val log = log ?: return
        val event = PlaybackEvent(time, kind, detail)
        runCatching { io.execute { log.appendSafely(event) } }
    }

    /** The kept events, newest last. Blocks until the events recorded before have been written. */
    fun read(): List<PlaybackEvent> = onIo(emptyList()) { it.events() }

    fun text(): String = read().joinToString("\n") { PlaybackLogFormat.encode(it) }

    fun clear() = onIo(Unit) { it.clear() }

    /**
     * Logs the ends of this app's processes that Android recorded since the last time (a kill while paused leaves no
     * other trace), at the time each one happened, before anything recorded after this call.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun recordProcessExits(context: Context) {
        val log = log ?: return
        val app = context.applicationContext
        runCatching {
            io.execute {
                runCatching {
                    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val exits = app.getSystemService(ActivityManager::class.java)
                        .getHistoricalProcessExitReasons(app.packageName, 0, 10)
                        .map { ProcessExit(it.timestamp, PlaybackLogFormat.exitReasonName(it.reason), it.description, it.importance, it.status, it.pss, it.rss) }
                    val new = PlaybackLogFormat.newExits(exits, prefs.getLong(KEY_LAST_EXIT, 0L))
                    new.forEach { log.appendSafely(PlaybackEvent(it.timestamp, PlaybackEventKind.PROCESS_EXIT, PlaybackLogFormat.exitDetail(it))) }
                    new.lastOrNull()?.let { prefs.edit { putLong(KEY_LAST_EXIT, it.timestamp) } }
                }.onFailure { Log.w(TAG, "process exit reasons not read", it) }
            }
        }
    }

    private fun <T> onIo(fallback: T, task: (LogFile) -> T): T {
        val log = log ?: return fallback
        return runCatching { io.submit(Callable { task(log) }).get() }
            .onFailure { Log.w(TAG, "playback log not read", it) }
            .getOrDefault(fallback)
    }

    /** The file and its line count; used on [io] only. */
    private class LogFile(val file: File) {
        private var lines = -1

        fun appendSafely(event: PlaybackEvent) {
            runCatching { append(event) }.onFailure { Log.w(TAG, "playback log not written", it) }
        }

        private fun append(event: PlaybackEvent) {
            file.parentFile?.mkdirs()
            if (lines < 0) lines = if (file.exists()) file.useLines { it.count() } else 0
            file.appendText(PlaybackLogFormat.encode(event) + "\n")
            if (++lines > PlaybackLogFormat.MAX_LINES) {
                val kept = PlaybackLogFormat.bound(file.readLines())
                val tmp = File(file.path + ".tmp")
                tmp.writeText(kept.joinToString("") { it + "\n" })
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                lines = kept.size
            }
        }

        fun events(): List<PlaybackEvent> =
            if (!file.exists()) emptyList() else file.readLines().mapNotNull { PlaybackLogFormat.parse(it) }.takeLast(PlaybackLogFormat.KEEP)

        fun clear() {
            file.delete()
            lines = 0
        }
    }
}

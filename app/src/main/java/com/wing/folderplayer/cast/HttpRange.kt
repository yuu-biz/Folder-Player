package com.wing.folderplayer.cast

/** Result of evaluating a Range header against a resource of known size (RFC 9110 §14). */
sealed class RangeResult {
    /** No (usable) Range header: 200 with the whole body. */
    object Full : RangeResult()
    /** 206 with bytes [start, endInclusive]. */
    data class Partial(val start: Long, val endInclusive: Long) : RangeResult() {
        val length get() = endInclusive - start + 1
    }
    /** 416 with `Content-Range: bytes * /size`. */
    object Unsatisfiable : RangeResult()
}

object HttpRange {
    /** Single byte ranges only; multi-range requests are answered with the full body (allowed by the RFC). */
    fun evaluate(header: String?, size: Long): RangeResult {
        if (header.isNullOrBlank()) return RangeResult.Full
        val h = header.trim()
        if (!h.startsWith("bytes=", ignoreCase = true)) return RangeResult.Full
        val spec = h.substring(6).trim()
        if (spec.contains(',')) return RangeResult.Full
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.Full
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        if (a.isEmpty()) {
            val suffix = b.toLongOrNull() ?: return RangeResult.Full
            if (suffix <= 0) return RangeResult.Unsatisfiable
            if (size == 0L) return RangeResult.Unsatisfiable
            return RangeResult.Partial(maxOf(0, size - suffix), size - 1)
        }
        val start = a.toLongOrNull() ?: return RangeResult.Full
        if (start >= size) return RangeResult.Unsatisfiable
        val end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return RangeResult.Full)
        if (end < start) return RangeResult.Full
        return RangeResult.Partial(start, minOf(end, size - 1))
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}

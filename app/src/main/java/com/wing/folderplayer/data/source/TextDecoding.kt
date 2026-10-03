package com.wing.folderplayer.data.source

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Decodes LRC/CUE/NFO text: BOM-aware, strict UTF-8 first, then GB18030 (common for Chinese CUE/LRC files). */
object TextDecoding {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        strict(bytes, Charsets.UTF_8)?.let { return it }
        val gb = runCatching { Charset.forName("GB18030") }.getOrNull()
        if (gb != null) strict(bytes, gb)?.let { return it }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}

object MediaTypes {
    /** Formats Media3 plays out of the box. */
    val NATIVE_AUDIO = setOf("mp3", "flac", "m4a", "wav", "ogg", "aac", "opus")
    /** Formats routed through the bundled FFmpeg/DSD decoder (ALAC in .m4a is detected at open time). */
    val EXTRA_AUDIO = setOf("wma", "ape", "dsf", "dff")
    val AUDIO = NATIVE_AUDIO + EXTRA_AUDIO
    val IMAGES = setOf("jpg", "jpeg", "png")

    fun isAudio(name: String) = SourcePath.extension(name) in AUDIO
    fun isImage(name: String) = SourcePath.extension(name) in IMAGES
    fun isCue(name: String) = SourcePath.extension(name) == "cue"
    fun isLyric(name: String) = SourcePath.extension(name) == "lrc"
    fun isNfo(name: String) = SourcePath.extension(name) == "nfo"
}

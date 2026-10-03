package com.wing.folderplayer.data.metadata

import com.wing.folderplayer.testutil.InMemoryFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Embedded title/artist/lyrics/picture from ID3v2.3/2.4 and FLAC, bounded reads. */
class EmbeddedTagReaderTest {
    private fun synchsafe(n: Int) = byteArrayOf(((n shr 21) and 0x7f).toByte(), ((n shr 14) and 0x7f).toByte(), ((n shr 7) and 0x7f).toByte(), (n and 0x7f).toByte())
    private fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte())

    private fun frame(id: String, body: ByteArray, v4: Boolean) =
        id.toByteArray() + (if (v4) synchsafe(body.size) else be32(body.size)) + byteArrayOf(0, 0) + body

    private fun id3(v4: Boolean, vararg frames: ByteArray): ByteArray {
        val all = frames.fold(ByteArray(0)) { a, b -> a + b }
        return "ID3".toByteArray() + byteArrayOf(if (v4) 4 else 3, 0, 0) + synchsafe(all.size) + all
    }

    private val picture = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    @Test fun readsId3v23Utf16AndUslt() {
        val title = byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "曲名".toByteArray(Charsets.UTF_16LE)
        val uslt = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "[00:01.00]歌词一\n[00:02.00]歌词二".toByteArray()
        val apic = byteArrayOf(0) + "image/png".toByteArray() + byteArrayOf(0, 3) + "desc".toByteArray() + byteArrayOf(0) + picture
        val data = id3(false, frame("TIT2", title, false), frame("TPE1", byteArrayOf(0) + "Artist".toByteArray(), false),
            frame("USLT", uslt, false), frame("APIC", apic, false)) + ByteArray(5000)
        val fs = InMemoryFileSystem().apply { put("/a.mp3", data) }
        val t = EmbeddedTagReader.read(fs, "/a.mp3")!!
        assertEquals("曲名", t.title)
        assertEquals("Artist", t.artist)
        assertEquals("[00:01.00]歌词一\n[00:02.00]歌词二", t.lyrics)
        assertArrayEquals(picture, t.picture)
    }

    @Test fun readsId3v24Utf8() {
        val data = id3(true, frame("TALB", byteArrayOf(3) + "Album ✓".toByteArray(), true), frame("TRCK", byteArrayOf(0) + "3/10".toByteArray(), true))
        val fs = InMemoryFileSystem().apply { put("/b.mp3", data) }
        val t = EmbeddedTagReader.read(fs, "/b.mp3")!!
        assertEquals("Album ✓", t.album)
        assertEquals("3/10", t.trackNumber)
    }

    @Test fun readsFlacVorbisCommentAndPicture() {
        val comments = listOf("TITLE=Flac Title", "ARTIST=Flac Artist", "LYRICS=plain lyric line")
        val vc = ByteArrayOutputStream().apply {
            val vendor = "ref".toByteArray(); write(le32(vendor.size)); write(vendor)
            write(le32(comments.size)); comments.forEach { c -> val b = c.toByteArray(); write(le32(b.size)); write(b) }
        }.toByteArray()
        val pic = ByteArrayOutputStream().apply {
            write(be32(3)); val mime = "image/png".toByteArray(); write(be32(mime.size)); write(mime)
            write(be32(0)); write(ByteArray(16)); write(be32(picture.size)); write(picture)
        }.toByteArray()
        fun block(type: Int, last: Boolean, body: ByteArray) = byteArrayOf(((if (last) 0x80 else 0) or type).toByte(),
            (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
        val data = "fLaC".toByteArray() + block(0, false, ByteArray(34)) + block(4, false, vc) + block(6, true, pic) + ByteArray(1000)
        val fs = InMemoryFileSystem().apply { put("/c.flac", data) }
        val t = EmbeddedTagReader.read(fs, "/c.flac")!!
        assertEquals("Flac Title", t.title)
        assertEquals("Flac Artist", t.artist)
        assertEquals("plain lyric line", t.lyrics)
        assertArrayEquals(picture, t.picture)
    }

    @Test fun oversizedOrMissingTagsAreIgnored() {
        val huge = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + synchsafe(200 * 1024 * 1024)
        val fs = InMemoryFileSystem().apply { put("/h.mp3", huge); put("/n.mp3", ByteArray(100)); put("/x.wav", ByteArray(10)) }
        assertNull(EmbeddedTagReader.read(fs, "/h.mp3"))
        assertNull(EmbeddedTagReader.read(fs, "/n.mp3"))
        assertNull(EmbeddedTagReader.read(fs, "/x.wav"))
    }
}

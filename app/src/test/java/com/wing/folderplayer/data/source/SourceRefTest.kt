package com.wing.folderplayer.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** paths with Japanese, spaces, #, %, + survive encode/decode exactly once and never escape the root. */
class SourceRefTest {
    private val tricky = "/fixture/Album-A/01 曲 #1+%.flac"

    @Test fun roundTripTrickyNames() {
        val ref = SourceRef("src-1", tricky)
        val uri = ref.toUriString()
        assertEquals("fpsrc://src-1/fixture/Album-A/01%20%E6%9B%B2%20%231%2B%25.flac", uri)
        assertEquals(ref, SourceUris.parse(uri))
    }

    @Test fun noDoubleDecoding() {
        // A file literally named "%41.mp3" must not turn into "A.mp3".
        val ref = SourceRef("s", "/%41.mp3")
        val parsed = SourceUris.parse(ref.toUriString())!!
        assertEquals("/%41.mp3", parsed.path)
        // A literal "+" is not a space.
        assertEquals("/a+b", SourceUris.parse(SourceRef("s", "/a+b").toUriString())!!.path)
        assertEquals("a+b", SourceUris.decode("a+b"))
    }

    @Test fun cueFragmentIsUnambiguous() {
        val audio = SourceRef("s", "/Cue/image #2.flac")
        val id = SourceUris.cueTrackId(audio, 123456)
        assertTrue(SourceUris.isCueTrackId(id))
        assertEquals(123456L, SourceUris.cueStartMs(id))
        assertEquals(audio, SourceUris.parse(id))
        assertFalse(SourceUris.isCueTrackId(audio.toUriString()))
    }

    @Test fun sourceIdIsEncodedToo() {
        val ref = SourceRef("weird/id #1", "/x")
        assertEquals(ref, SourceUris.parse(ref.toUriString()))
    }

    @Test fun malformedAndForeignUrisAreRejected() {
        assertNull(SourceUris.parse("http://host/a.mp3"))
        assertNull(SourceUris.parse("fpsrc://s/%zz"))
        assertNull(SourceUris.parse("fpsrc://s/%4"))
        assertNull(SourceUris.parse("fpsrc://s/a/%2E%2E/b"))
        assertNull(SourceUris.parse("fpsrc:///x"))
    }

    @Test fun pathNormalizationAndRootEscape() {
        assertEquals("/a/b", SourcePath.normalize("a//b/"))
        assertEquals("/a", SourcePath.normalize("/a/./b/.."))
        assertEquals("/", SourcePath.normalize(""))
        try {
            SourcePath.normalize("/a/../../etc")
            fail("escape accepted")
        } catch (e: SourcePath.EscapesRootException) {
        }
        try {
            SourcePath.child("/a", "..")
            fail("bad child accepted")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test fun joinAndRelativize() {
        assertEquals("/Music/Album", SourcePath.join("/Music", "/Album"))
        assertEquals("/Album", SourcePath.relativize("/Music", "/Music/Album"))
        assertEquals("/", SourcePath.relativize("/Music", "/Music"))
        assertNull(SourcePath.relativize("/Music", "/MusicOther/x"))
        assertEquals("/storage/emulated/0/x", SourcePath.join("/", "/storage/emulated/0/x"))
    }

    @Test fun parentNameExtension() {
        assertEquals("/A", SourcePath.parent("/A/b.flac"))
        assertEquals("/", SourcePath.parent("/A"))
        assertNull(SourcePath.parent("/"))
        assertEquals("b.flac", SourcePath.name("/A/b.flac"))
        assertEquals("flac", SourcePath.extension("X.FLAC"))
        assertEquals("01 曲 #1+%", SourcePath.baseName("01 曲 #1+%.flac"))
    }

    @Test fun textDecodingHandlesBomUtf8AndGb18030() {
        assertEquals("歌词", TextDecoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "歌词".toByteArray()))
        assertEquals("歌词", TextDecoding.decode("歌词".toByteArray(Charsets.UTF_8)))
        assertEquals("歌词", TextDecoding.decode("歌词".toByteArray(charset("GB18030"))))
        assertEquals("ab", TextDecoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'a'.code.toByte(), 0, 'b'.code.toByte(), 0)))
    }

    @Test fun configSerializationNeverContainsPassword() {
        val store = InMemoryCredentialStore()
        val cfg = SourceConfig(id = "x", name = "nas", type = SourceType.SMB, host = "h", share = "music", username = "u")
        store.put(cfg.effectiveCredentialRef, "s3cret!")
        val json = com.google.gson.Gson().toJson(listOf(cfg))
        assertFalse(json.contains("s3cret"))
        assertFalse(cfg.describe().contains("s3cret"))
        assertFalse(SourceRef(cfg.id, "/a").toUriString().contains("s3cret"))
    }
}

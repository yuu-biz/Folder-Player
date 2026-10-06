package com.wing.folderplayer.data.artwork

import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.readBytes
import com.wing.folderplayer.testutil.InMemoryFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * One cover is read from the source once for validation and display together. The source is an in-memory file system
 * whose reads are counted ("NAS reads"); [ArtworkImageBytes] with a cache that keeps nothing is the behaviour before.
 */
class ArtworkImageBytesTest {
    private val fs = InMemoryFileSystem("nas").apply {
        put("/A/cover.jpg", "IMG-cover".toByteArray(), 111)
        put("/A/folder.png", "IMG-folder".toByteArray(), 222)
        put("/A/track.flac", "audio")
    }
    private val reads = AtomicInteger()
    private val readsOf = HashMap<String, Int>()
    private val decodes: (ByteArray) -> Boolean = { String(it).startsWith("IMG") }

    private fun loader(cache: ValidatedImageCache = ValidatedImageCache(), revision: Int = 0) = ArtworkImageBytes(
        cache,
        read = { ref -> reads.incrementAndGet(); readsOf.merge(ref.path, 1, Int::plus); fs.readBytes(ref.path, 1L shl 20) },
        revisionOf = { revision },
    )

    private fun entry(path: String) = fs.stat(path)!!.let { MusicFile(it.name, it.path, false, it.size, it.lastModified, "nas") }
    private fun uri(path: String) = entry(path).let { ImageUris.of(SourceRef("nas", it.path), it) }
    private fun ref(path: String) = SourceRef("nas", path)

    @Test fun beforeTheCoverWasReadTwiceNowOnce() {
        val before = loader(ValidatedImageCache(maxTotalBytes = 0, maxItemBytes = 0)) // keeps nothing = the old behaviour
        assertTrue(before.validate(entry("/A/cover.jpg"), decodes))
        before.load(uri("/A/cover.jpg"), ref("/A/cover.jpg"))
        assertEquals("validation + display", 2, reads.get())

        reads.set(0)
        val after = loader()
        assertTrue(after.validate(entry("/A/cover.jpg"), decodes))
        val bytes = after.load(uri("/A/cover.jpg"), ref("/A/cover.jpg"))
        assertEquals("validation and display share one read", 1, reads.get())
        assertArrayEquals("IMG-cover".toByteArray(), bytes)
        // Another size / a second display of the same cover: still no read.
        after.load(uri("/A/cover.jpg"), ref("/A/cover.jpg"))
        assertEquals(1, reads.get())
    }

    @Test fun resolverStillSkipsBrokenImagesAndKeepsOnlyTheGoodOne() {
        fs.put("/A/cover.jpg", "broken".toByteArray(), 111)
        val l = loader()
        val resolver = ArtworkResolver({ r -> fs.list(r.path) }, { e -> l.validate(e, decodes) })
        val r = resolver.resolve(ref("/A")) as ArtworkResult.Found
        assertEquals("/A/folder.png", r.image.path)
        assertNull("the broken image is not kept", l.cache.get(l.keyOf(uri("/A/cover.jpg"))))
        assertNotNull(l.cache.get(l.keyOf(uri("/A/folder.png"))))
        // The broken one is read again when asked for (nothing was remembered about it).
        assertFalse(l.validate(entry("/A/cover.jpg"), decodes))
        assertEquals(2, readsOf["/A/cover.jpg"])
        assertEquals(1, readsOf["/A/folder.png"])
    }

    @Test fun readErrorsAndPermissionLossAreNeverRemembered() {
        val l = loader()
        fs.unreadable.add("/A")
        val e = runCatching { l.validate(entry("/A/cover.jpg"), decodes) }.exceptionOrNull()
        assertTrue("permission error propagates: $e", e is SourceException.PermissionDenied)
        assertEquals(0, l.cache.count)
        fs.truncateAt["/A/folder.png"] = 3
        fs.unreadable.clear()
        assertTrue(runCatching { l.validate(entry("/A/folder.png"), decodes) }.exceptionOrNull() is IOException)
        assertEquals("a cut connection leaves nothing", 0, l.cache.count)
        fs.truncateAt.clear()
        assertTrue("reconnected: read and kept", l.validate(entry("/A/cover.jpg"), decodes))
        assertEquals(1, l.cache.count)
    }

    @Test fun aReadThatFailsForDisplayIsNotRemembered() {
        val l = loader()
        fs.unreadable.add("/A")
        assertTrue(runCatching { l.load(uri("/A/cover.jpg"), ref("/A/cover.jpg")) }.isFailure)
        assertEquals(0, l.cache.count)
        fs.unreadable.clear()
        assertArrayEquals("IMG-cover".toByteArray(), l.load(uri("/A/cover.jpg"), ref("/A/cover.jpg")))
    }

    @Test fun hugeImagesAreNotKeptInMemoryAndAreReadAgainForDisplay() {
        fs.put("/A/cover.jpg", ByteArray(500).also { "IMG".toByteArray().copyInto(it) }, 111)
        val cache = ValidatedImageCache(maxTotalBytes = 10_000, maxItemBytes = 100)
        val l = loader(cache)
        assertTrue("still valid", l.validate(entry("/A/cover.jpg"), decodes))
        assertEquals(0L, cache.totalBytes)
        l.load(uri("/A/cover.jpg"), ref("/A/cover.jpg"))
        assertEquals("fallback: read again", 2, reads.get())
    }

    @Test fun totalSizeIsBoundedLeastRecentlyUsedFirst() {
        val cache = ValidatedImageCache(maxTotalBytes = 100, maxItemBytes = 60)
        cache.put("a", ByteArray(40)); cache.put("b", ByteArray(40))
        cache.get("a")                       // a is now the more recent one
        cache.put("c", ByteArray(40))        // 120 > 100: b (least recently used) goes
        assertNotNull(cache.get("a")); assertNull(cache.get("b")); assertNotNull(cache.get("c"))
        assertTrue(cache.totalBytes <= 100)
        assertFalse("above the per-image limit", cache.put("d", ByteArray(61)))
        assertTrue(cache.totalBytes <= 100)
        cache.put("a", ByteArray(10)) // replacing adjusts the total
        assertEquals(50L, cache.totalBytes)
    }

    @Test fun keysCarryTheSourceIdAndRevisionButNoCredentials() {
        val k0 = loader(revision = 0).keyOf("fpsrc://nas/A/cover.jpg?v=9-111")
        val k1 = loader(revision = 1).keyOf("fpsrc://nas/A/cover.jpg?v=9-111")
        assertEquals("fpsrc://nas/A/cover.jpg?v=9-111|r0", k0)
        assertNotEquals("an edited source (new revision) does not share bytes", k0, k1)
        assertTrue(k0.startsWith("fpsrc://nas"))
        // The version of the file is part of the key: a replaced image is another entry.
        assertNotEquals(loader().keyOf("fpsrc://nas/A/cover.jpg?v=9-112"), k0)
    }

    @Test fun anImageThatDoesNotMatchTheListingIsNotKept() {
        val l = loader()
        val stale = entry("/A/cover.jpg").copy(size = 999) // the listing said 999 bytes, the file has 9
        assertTrue(l.validate(stale, decodes))
        assertEquals(0, l.cache.count)
    }

    @Test fun unversionedUrisAreNotKept() {
        val l = loader()
        l.load("fpsrc://nas/A/cover.jpg", ref("/A/cover.jpg"))
        l.load("fpsrc://nas/A/cover.jpg", ref("/A/cover.jpg"))
        assertEquals(2, reads.get())
        assertEquals(0, l.cache.count)
    }

    @Test fun invalidatingASourceDropsItsImages() {
        val l = loader()
        l.validate(entry("/A/cover.jpg"), decodes)
        l.cache.removePrefix("fpsrc://nas")
        assertEquals(0, l.cache.count)
    }
}

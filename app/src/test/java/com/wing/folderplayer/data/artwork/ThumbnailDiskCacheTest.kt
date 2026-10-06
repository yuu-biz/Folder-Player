package com.wing.folderplayer.data.artwork

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Size buckets and the bounded, least-recently-used thumbnail cache (clean-up runs inline: Unconfined scope). */
class ThumbnailDiskCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private var clock = 1_000_000_000L
    private fun cache(max: Long = 100, trimTo: Long = 75) =
        ThumbnailDiskCache(File(tmp.root, "thumbs"), CoroutineScope(Dispatchers.Unconfined), max, trimTo, trimEveryBytes = 1) { clock }

    private fun write(f: File, bytes: Int, mtime: Long) {
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(bytes))
        f.setLastModified(mtime)
    }

    @Test fun requestedSizeIsRoundedUpNeverDown() {
        val c = cache()
        for (px in 1..ThumbnailDiskCache.BUCKETS.last()) {
            val b = c.bucketOf(px)
            assertTrue("bucket $b for $px is not below the request", b >= px)
            assertTrue("$b is one of the buckets", b in ThumbnailDiskCache.BUCKETS)
        }
        assertEquals(128, c.bucketOf(1)); assertEquals(128, c.bucketOf(128)); assertEquals(192, c.bucketOf(129))
        assertEquals(288, c.bucketOf(193)); assertEquals(512, c.bucketOf(289)); assertEquals(512, c.bucketOf(512))
        assertEquals(4, ThumbnailDiskCache.BUCKETS.size)
    }

    @Test fun nearbySizesShareOneFileAndOtherBucketsOrImagesDoNot() {
        val c = cache()
        val uri = "fpsrc://nas/Album/cover.jpg?v=10-20"
        assertEquals(c.file(uri, 104, 0), c.file(uri, 128, 0))
        assertEquals(c.file(uri, 130, 0), c.file(uri, 192, 0))
        assertNotEquals(c.file(uri, 128, 0), c.file(uri, 129, 0))
        assertNotEquals(c.file(uri, 128, 0), c.file("fpsrc://nas/Album/cover.jpg?v=10-21", 128, 0))
        assertEquals("revision is a directory", "r3", c.file(uri, 128, 3).parentFile.name)
        assertFalse("no source or path data in the file path", c.file("fpsrc://nas/x.jpg?v=1-1", 128, 4).name.contains("nas"))
    }

    @Test fun oldestFilesGoFirstWhenOverTheLimit() {
        val c = cache(max = 100, trimTo = 75)
        val files = (0 until 10).map { c.file("fpsrc://s/$it.jpg?v=1-1", 128, 0).also { f -> write(f, 20, 1_000_000L + it * 1_000L) } }
        c.stored(files.last()) // 200 bytes > 100: down to <= 75, oldest first
        val left = files.filter { it.exists() }
        assertEquals(files.takeLast(3), left)
        assertTrue(left.sumOf { it.length() } <= 75)
    }

    @Test fun aUsedFileIsKeptOverNewerUnusedOnes() {
        val c = cache(max = 100, trimTo = 60)
        val old = c.file("fpsrc://s/old.jpg?v=1-1", 128, 0).also { write(it, 20, 1_000L) }
        val others = (0 until 5).map { c.file("fpsrc://s/n$it.jpg?v=1-1", 128, 0).also { f -> write(f, 20, 100_000L + it) } }
        clock = 500_000_000L
        c.markUsed(old) // used long after it was stored
        c.stored(others.last())
        assertTrue("recently used file survives", old.exists())
        assertFalse("the least recently used one went", others.first().exists())
    }

    @Test fun usedTimestampIsNotRewrittenForEveryHit() {
        val c = cache()
        val recent = clock - 1_000_000
        val f = c.file("fpsrc://s/a.jpg?v=1-1", 128, 0).also { write(it, 5, recent) }
        val before = f.lastModified()
        c.markUsed(f)
        assertEquals("fresh enough: untouched", before, f.lastModified())
        f.setLastModified(clock - ThumbnailDiskCache.TOUCH_INTERVAL_MS - 5_000_000)
        c.markUsed(f)
        assertTrue("stale: refreshed to now", f.lastModified() >= clock - 1_000)
    }

    @Test fun belowTheLimitNothingIsDeleted() {
        val c = cache(max = 1_000, trimTo = 750)
        val files = (0 until 5).map { c.file("fpsrc://s/$it.jpg?v=1-1", 128, 0).also { f -> write(f, 20, 1_000L + it) } }
        c.stored(files.last())
        assertTrue(files.all { it.exists() })
    }

    @Test fun aNewRevisionRemovesOlderGenerationsAndLegacyFlatFiles() {
        val root = File(tmp.root, "thumbs")
        val c = cache(max = 10_000, trimTo = 7_500)
        val old = c.file("fpsrc://s/a.jpg?v=1-1", 128, 4).also { write(it, 20, 1_000L) }
        val legacy = File(root, "0123abcd.jpg").also { write(it, 20, 1_000L) } // version without revision directories
        assertTrue(old.exists() && legacy.exists())
        val fresh = c.file("fpsrc://s/a.jpg?v=1-1", 128, 5) // revision changed (settings toggled)
        assertFalse("older generation deleted", old.exists() || old.parentFile.exists())
        assertFalse("legacy flat file deleted", legacy.exists())
        write(fresh, 20, 2_000L)
        c.file("fpsrc://s/b.jpg?v=1-1", 128, 4) // a late caller still on revision 4 does not purge revision 5
        assertTrue(fresh.exists())
    }

    @Test fun staleUnfinishedWritesAreCleanedUp() {
        val c = cache()
        val f = c.file("fpsrc://s/a.jpg?v=1-1", 128, 0)
        val stale = File(f.path + ".tmp").also { write(it, 10, clock - 2 * 60 * 60 * 1000L) }
        val fresh = File(f.parentFile, "x.jpg.tmp").also { write(it, 10, clock) }
        c.trim()
        assertFalse(stale.exists())
        assertTrue("a write in progress is left alone", fresh.exists())
    }
}

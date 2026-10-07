package com.wing.folderplayer.data.artwork

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The two-tier image cache: sizes, names, one shared limit split between thumbnails and player artwork, clean-up. */
class ImageDiskCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    // Unconfined: a clean-up that is requested runs at once, on the calling thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 10_000_000_000L
    private var limit = 1000L
    private val root get() = File(tmp.root, "imgcache")
    private fun cache(legacy: List<File> = emptyList()) = ImageDiskCache(root, scope, { limit }, legacy, trimEveryBytes = 1, now = { now })

    private fun put(f: File, bytes: Int, mtime: Long) {
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(bytes))
        f.setLastModified(mtime)
    }

    @Test fun thumbnailsComeInTwoSizes() {
        val c = cache()
        for (px in listOf(-1, 0)) assertEquals("unknown size: the larger one", 256, c.thumbBucket(px))
        for (px in listOf(1, 40, 128)) assertEquals(128, c.thumbBucket(px))
        for (px in listOf(129, 256, 360, 533, 2000)) assertEquals(256, c.thumbBucket(px))
    }

    @Test fun namesDependOnImageTierAndSizeNeverOnAnythingElse() {
        val c = cache()
        val uri = "fpsrc://nas-1/Music/A/cover.jpg?v=1000-5"
        assertEquals(c.file(ImageTier.THUMB, uri, 128), c.file(ImageTier.THUMB, uri, 128))
        assertNotEquals(c.file(ImageTier.THUMB, uri, 128), c.file(ImageTier.THUMB, uri, 256))
        assertNotEquals(c.file(ImageTier.THUMB, uri, 256), c.file(ImageTier.ARTWORK, uri))
        assertNotEquals("a changed image (version) is another file", c.file(ImageTier.ARTWORK, uri), c.file(ImageTier.ARTWORK, uri.replace("1000-5", "1000-6")))
        val t = c.file(ImageTier.THUMB, uri, 128); val a = c.file(ImageTier.ARTWORK, uri)
        assertTrue(t.parentFile!!.name == "t" && t.name.endsWith(".jpg") && a.parentFile!!.name == "a" && a.name.endsWith(".img"))
        assertFalse("the name does not show the path", t.name.contains("Music") || a.name.contains("nas"))
    }

    @Test fun overTheLimitTheTierFurthestAboveItsShareLosesItsOldestFirst() {
        val c = cache()
        // 4 thumbnails of 100 (400) and 4 pictures of 200 (800): 1200 against a limit of 1000, cleaned up to 750.
        val thumbs = (0 until 4).map { c.file(ImageTier.THUMB, "t$it", 256).also { f -> put(f, 100, 1_000L + it) } }
        val arts = (0 until 4).map { c.file(ImageTier.ARTWORK, "a$it").also { f -> put(f, 200, 1_000L + it) } }
        c.trim()
        val left = c.usage(ImageTier.THUMB).bytes + c.usage(ImageTier.ARTWORK).bytes
        assertTrue("under three quarters of the limit: $left", left <= 750)
        assertEquals("the pictures gave way first, then one thumbnail", 3, c.usage(ImageTier.THUMB).files)
        assertEquals(2, c.usage(ImageTier.ARTWORK).files)
        assertTrue("oldest pictures went", !arts[0].exists() && !arts[1].exists() && arts[2].exists() && arts[3].exists())
        assertTrue("oldest thumbnail went", !thumbs[0].exists() && thumbs[1].exists())
    }

    @Test fun aTierCanUseTheOthersUnusedRoomButNotPushItBelowItsShare() {
        val c = cache()
        // Only pictures: they may fill the whole limit (no clean-up below it) ...
        (0 until 4).forEach { put(c.file(ImageTier.ARTWORK, "a$it"), 200, 1_000L + it) }
        c.trim()
        assertEquals(4, c.usage(ImageTier.ARTWORK).files)
        // ... but with thumbnails at their share (400 of 1000) they give way when the sum is over the limit.
        (0 until 4).forEach { put(c.file(ImageTier.THUMB, "t$it", 128), 100, 5_000L + it) }
        c.trim()
        assertTrue(c.usage(ImageTier.THUMB).bytes >= 300)
        assertTrue(c.usage(ImageTier.ARTWORK).bytes <= 450)
    }

    @Test fun belowTheLimitNothingIsDeletedAndALowerLimitCleansUpAtOnce() {
        val c = cache()
        (0 until 3).forEach { put(c.file(ImageTier.ARTWORK, "a$it"), 200, 1_000L + it) }
        c.trim()
        assertEquals(3, c.usage(ImageTier.ARTWORK).files)
        limit = 400
        c.onLimitChanged()
        assertTrue(c.usage(ImageTier.ARTWORK).bytes <= 300)
        assertTrue("the newest one is kept", c.file(ImageTier.ARTWORK, "a2").exists())
    }

    @Test fun unfinishedWritesAreCleanedUpAfterAWhileAndNotCounted() {
        val c = cache()
        val old = File(c.file(ImageTier.THUMB, "x", 128).path + ".tmp").also { put(it, 50, now - 2 * 60 * 60 * 1000L) }
        val fresh = File(c.file(ImageTier.THUMB, "y", 128).path + ".tmp").also { put(it, 50, now) }
        put(c.file(ImageTier.THUMB, "z", 128), 10, now)
        assertEquals("tmp files are not part of the usage", 1, c.usage(ImageTier.THUMB).files)
        c.trim()
        assertFalse(old.exists()); assertTrue(fresh.exists())
    }

    @Test fun writeGoesThroughATemporaryFileAndClearRemovesEverythingIncludingOlderLayouts() {
        val legacyDir = File(tmp.root, "thumbs").also { put(File(it, "r3/old.jpg"), 10, 1L) }
        val c = cache(listOf(legacyDir))
        assertFalse("the cache of earlier versions is deleted at the start", legacyDir.exists())
        val f = c.file(ImageTier.ARTWORK, "p")
        c.write(f, ByteArray(123) { 1 })
        assertEquals(123L, f.length()); assertFalse(File(f.path + ".tmp").exists())
        assertEquals(TierUsage(123, 1), c.usage(ImageTier.ARTWORK))
        c.clear()
        assertFalse(root.exists())
        assertEquals(TierUsage(0, 0), c.usage(ImageTier.THUMB))
    }

    @Test fun imageScalingPicksTheLargestSampleThatKeepsTheLongerSide() {
        assertEquals(1, ImageScaling.sampleForLongSide(1000, 800, 2048))
        assertEquals(1, ImageScaling.sampleForLongSide(4000, 3000, 2048))
        assertEquals(2, ImageScaling.sampleForLongSide(6000, 4000, 2048))
        assertEquals("a long thin scan is judged by its longer side", 4, ImageScaling.sampleForLongSide(9000, 100, 2048))
        assertEquals(8, ImageScaling.sampleForLongSide(3000, 3000, 256))
    }
}

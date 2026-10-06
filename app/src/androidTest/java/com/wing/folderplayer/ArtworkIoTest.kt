package com.wing.folderplayer

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.wing.folderplayer.data.artwork.ArtworkResult
import com.wing.folderplayer.data.artwork.ImageUris
import com.wing.folderplayer.data.artwork.SourceImageFetcher
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.source.LocalFileSystem
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourceInput
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Folder image I/O: how often the source is read for one cover (validation + display) and how many thumbnail files
 * the disk cache keeps. The source is a counting wrapper around a local folder, so every read is observable.
 */
@RunWith(AndroidJUnit4::class)
class ArtworkIoTest {
    private val id = "artwork-io-test"
    private lateinit var dir: File
    private lateinit var fs: CountingFs
    private val thumbs get() = ThumbnailRepository.get(Fx.ctx)

    /** Counts openRead calls per path; [failNext] makes the next reads of a path fail. */
    private class CountingFs(private val inner: SourceFileSystem) : SourceFileSystem by inner {
        val reads = ConcurrentHashMap<String, AtomicInteger>()
        val failNext = ConcurrentHashMap<String, SourceException>()
        fun reads(path: String) = reads[path]?.get() ?: 0
        override fun openRead(path: String, offset: Long, length: Long): SourceInput {
            reads.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            failNext.remove(path)?.let { throw it }
            return inner.openRead(path, offset, length)
        }
    }

    private fun jpeg(seed: Int, px: Int = 640): ByteArray {
        val rnd = Random(seed.toLong())
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(px * px) { i -> (0xFF shl 24) or ((i % px) * 255 / px shl 16) or ((i / px) * 255 / px shl 8) or rnd.nextInt(64) }
        bmp.setPixels(pixels, 0, px, 0, 0, px, px)
        return java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray().also { bmp.recycle() }
    }

    @Before fun setUp() {
        SourceRegistry.init(Fx.ctx)
        dir = File(Fx.ctx.cacheDir, "artwork-io-${System.nanoTime()}").apply { mkdirs() }
        val cfg = SourceConfig(id = id, name = "artwork io", type = SourceType.LOCAL, url = dir.absolutePath)
        fs = CountingFs(LocalFileSystem(cfg))
        SourceRegistry.fileSystemFactory = { c -> if (c.id == id) fs else null }
        SourceRegistry.upsert(cfg, null)
        thumbs.clearCaches()
    }

    @After fun tearDown() {
        SourceRegistry.fileSystemFactory = null
        SourceRegistry.remove(id)
        thumbs.clearCaches()
        dir.deleteRecursively()
    }

    private fun album(name: String, vararg images: Pair<String, ByteArray>): SourceRef {
        File(dir, name).mkdirs()
        File(dir, "$name/track.flac").writeBytes(ByteArray(10))
        images.forEach { (n, b) -> File(dir, "$name/$n").writeBytes(b) }
        return SourceRef(id, "/$name")
    }

    private fun found(r: ArtworkResult): ArtworkResult.Found = r as? ArtworkResult.Found ?: error("expected a cover, got $r")

    private fun display(f: ArtworkResult.Found, px: Int): Boolean {
        val req = ImageRequest.Builder(Fx.ctx).data(ImageUris.of(f.image, f.entry)).size(px)
            .memoryCachePolicy(CachePolicy.DISABLED).build()
        return when (val res = runBlocking { Fx.ctx.imageLoader.execute(req) }) {
            is SuccessResult -> true
            is ErrorResult -> { Fx.log("display failed: ${res.throwable}"); false }
        }
    }

    private fun thumbFiles(): List<File> =
        File(Fx.ctx.cacheDir, SourceImageFetcher.THUMB_DIR).walkTopDown().filter { it.isFile && it.extension == "jpg" }.toList()

    @Test fun coverIsReadOnceForValidationAndDisplay() {
        val folder = album("Album One", "cover.jpg" to jpeg(1))
        val f = found(runBlocking { thumbs.playbackCover(folder, null) })
        assertTrue("displayed", display(f, 128))
        val reads = fs.reads("/Album One/cover.jpg")
        Fx.log("ArtworkIoTest cover reads (validation + display): $reads")
        assertEquals("one read of the cover from the source", 1, reads)
    }

    @Test fun anotherSizeOfTheSameCoverDoesNotReadTheSourceAgainWhileItIsKept() {
        val folder = album("Album Two", "cover.jpg" to jpeg(2))
        val f = found(runBlocking { thumbs.playbackCover(folder, null) })
        assertTrue(display(f, 128))
        assertTrue(display(f, 300))
        val reads = fs.reads("/Album Two/cover.jpg")
        Fx.log("ArtworkIoTest cover reads (validation + two sizes): $reads")
        assertEquals(1, reads)
    }

    @Test fun brokenCoverIsStillSkippedAndOnlyTheGoodOneIsKept() {
        val folder = album("Album Three", "cover.jpg" to "not an image".toByteArray(), "folder.jpg" to jpeg(3))
        val f = found(runBlocking { thumbs.playbackCover(folder, null) })
        assertEquals("/Album Three/folder.jpg", f.image.path)
        assertTrue(display(f, 128))
        assertEquals("the broken cover is read once (validation) and never displayed", 1, fs.reads("/Album Three/cover.jpg"))
        assertEquals(1, fs.reads("/Album Three/folder.jpg"))
    }

    @Test fun failedReadsAreNotRememberedAndTheNextTryReadsAgain() {
        val folder = album("Album Four", "cover.jpg" to jpeg(4))
        fs.failNext["/Album Four/cover.jpg"] = SourceException.Unreachable("connection lost")
        val first = runBlocking { thumbs.playbackCover(folder, null) }
        assertTrue("network failure is reported, not 'no image': $first", first is ArtworkResult.Failed)
        fs.failNext["/Album Four/cover.jpg"] = SourceException.PermissionDenied("no access")
        val second = runBlocking { thumbs.playbackCover(folder, null) }
        assertTrue("permission failure is reported: $second", second is ArtworkResult.Failed)
        val third = found(runBlocking { thumbs.playbackCover(folder, null) })
        assertTrue("works once the source is readable again", display(third, 128))
        assertTrue("both failed attempts and the good one read the source", fs.reads("/Album Four/cover.jpg") >= 3)
    }

    @Test fun nearbyRequestedSizesShareOneThumbnailFile() {
        val folder = album("Album Five", "cover.jpg" to jpeg(5))
        val f = found(runBlocking { thumbs.playbackCover(folder, null) })
        for (px in listOf(104, 120, 128)) assertTrue(display(f, px))
        val files = thumbFiles()
        Fx.log("ArtworkIoTest thumbnail files for sizes 104/120/128: ${files.size}")
        assertEquals("one file for sizes of the same bucket", 1, files.size)
    }
}

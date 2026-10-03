package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.artwork.ArtworkResult
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.utils.PermissionDiagnostics
import com.wing.folderplayer.utils.PermissionDiagnostics.Access
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Local access on real Android shared storage with real image decoding. The permission state is set by the
 * runner script with `pm grant/revoke` before the process starts and passed as `-e perm_state` (ALL, AUDIO_ONLY,
 * PARTIAL, NONE). Observations are logged with tag FpTest for ANDROID_STORAGE.md.
 */
@RunWith(AndroidJUnit4::class)
class LocalAccessTest {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private lateinit var state: String

    @Before fun setUp() {
        Fx.require("perm_state")
        state = Fx.arg("perm_state")!!
        SourceRegistry.init(Fx.ctx)
        ThumbnailRepository.get(Fx.ctx).clearCaches()
    }

    private fun names(path: String): Set<String>? = try {
        SourceRegistry.fileSystem(local).list(path).map { it.name }.toSet()
    } catch (e: SourceException) {
        Fx.log("list $path -> ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    @Test fun permissionStateIsDiagnosedAndAudioSurvivesImageDenial() {
        val r = PermissionDiagnostics.report(Fx.ctx)
        Fx.log("sdk=${r.sdk} state=$state audio=${r.audio} images=${r.images} notifications=${r.notifications}")
        val albumA = names("${Fx.FX}/Album-A")
        Fx.log("Album-A listing ($state): $albumA")
        val unindexed = names("${Fx.FX}/Unindexed")
        Fx.log("Unindexed listing ($state): $unindexed")
        val legacy = Fx.sdk < 33
        when (state) {
            "ALL" -> {
                assertEquals(Access.GRANTED, r.audio)
                assertEquals(Access.GRANTED, r.images)
                assertTrue("cover.jpg visible with image permission", albumA!!.contains("cover.jpg"))
            }
            "AUDIO_ONLY" -> {
                assertEquals(Access.GRANTED, r.audio)
                assertEquals(if (legacy) Access.GRANTED else Access.DENIED, r.images)
                if (!legacy) assertFalse("images hidden without READ_MEDIA_IMAGES (root cause of missing covers)", albumA!!.contains("cover.jpg"))
            }
            "PARTIAL" -> {
                assertEquals(Access.GRANTED, r.audio)
                // Partial photo access (READ_MEDIA_VISUAL_USER_SELECTED) exists from Android 14; before that it is "denied".
                assertEquals(if (Fx.sdk >= 34) Access.PARTIAL else if (legacy) Access.GRANTED else Access.DENIED, r.images)
            }
            "NONE" -> {
                assertEquals(Access.DENIED, r.audio)
            }
        }
        if (state != "NONE") {
            assertTrue("audio listed", albumA!!.contains("01 曲 #1+%.flac"))
            val res = Fx.play(SourceRef(local, Fx.TRICKY), playMs = 1500)
            Fx.log("local playback ($state): $res")
            assertTrue("audio plays even when images are denied: $res", res.reachedReady && res.positionMs > 500)
        }
        val cover = runBlocking { ThumbnailRepository.get(Fx.ctx).playbackCover(SourceRef(local, "${Fx.FX}/Album-A"), null) }
        Fx.log("Album-A cover ($state): $cover")
        if (state == "ALL") assertTrue(cover is ArtworkResult.Found && (cover as ArtworkResult.Found).image.name == "cover.jpg")
        else if (Fx.sdk >= 33) assertTrue("missing image permission must not look like 'no image': $cover",
            cover is ArtworkResult.Failed && (cover as ArtworkResult.Failed).kind == ArtworkResult.Kind.PERMISSION)
    }

    /** Folder image rules with real BitmapFactory decoding; runs only with full image access. */
    @Test fun folderImageRulesOnDevice() {
        org.junit.Assume.assumeTrue(state == "ALL")
        val thumbs = ThumbnailRepository.get(Fx.ctx)
        fun cover(folder: String): String? = runBlocking {
            (thumbs.playbackCover(SourceRef(local, "${Fx.FX}/$folder"), null) as? ArtworkResult.Found)?.image?.path
        }
        assertEquals("${Fx.FX}/Album-A/cover.jpg", cover("Album-A"))
        assertEquals("${Fx.FX}/Album-B/arbitrary-name.png", cover("Album-B"))
        assertEquals("${Fx.FX}/Parent/cover.jpg", cover("Parent/CD1"))
        assertEquals("${Fx.FX}/CorruptCover/folder.png", cover("CorruptCover"))
        assertNull(cover("LongChildName"))
        assertNull(cover("ChildOnly"))
        assertNull(cover("Album-C"))
        val unindexed = runBlocking { thumbs.playbackCover(SourceRef(local, "${Fx.FX}/Unindexed"), null) }
        Fx.log("Unindexed (.nomedia) via File API: $unindexed")
    }
}

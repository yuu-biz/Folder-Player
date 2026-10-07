package com.wing.folderplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.Coil
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.wing.folderplayer.data.artwork.ArtworkResult
import com.wing.folderplayer.data.artwork.ArtworkSettings
import com.wing.folderplayer.data.artwork.ImageUris
import com.wing.folderplayer.data.artwork.ImageTier
import com.wing.folderplayer.data.artwork.SourceImageFetcher
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.source.NetworkStats
import com.wing.folderplayer.data.source.ReadKind
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two-tier image cache against the Docker SMB share: the thumbnails and the player artwork are read from the NAS once
 * and then served from disk (counted by [NetworkStats]), Clear makes the next view read them again, and the Settings page
 * shows the limit, the usage and the network account. Needs the fixture servers (run-suites.sh passes smb_host).
 */
@RunWith(AndroidJUnit4::class)
class ImageCacheUiTest : UiTestBase() {
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val player: PlayerViewModel get() = onUi { ViewModelProvider(compose.activity)[PlayerViewModel::class.java] }
    private val thumbs get() = ThumbnailRepository.get(Fx.ctx)
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }
    private fun shown(tag: String) = runCatching { node(tag).assertIsDisplayed(); true }.getOrDefault(false)
    private fun imageBytes() = NetworkStats.snapshot().bytes.getValue(ReadKind.IMAGE)
    private fun flushMemory() { Coil.imageLoader(Fx.ctx).memoryCache?.clear() }

    private fun open(sourceId: String, path: String) {
        toBrowser()
        onUi { browser.loadFolder(SourceRef(sourceId, path)) }
        until(30_000, "folder $path") { browser.uiState.value.currentFolder?.path == path && !browser.uiState.value.isLoading }
        if (browser.uiState.value.viewMode != "LIST") click("btn_view_mode")
        until(5_000, "list") { exists("file_list") }
    }

    @Test fun a70_imagesAreReadFromTheNasOnceThenServedFromDiskAndClearReadsThemAgain() {
        Fx.require("smb_host")
        val cfg = SourceConfig(name = "img-smb", type = SourceType.SMB, host = Fx.arg("smb_host")!!, share = "music", username = "alice")
        SourceRegistry.upsert(cfg, "alicepass")
        thumbs.settings.setThumbnailsEnabled(SourceType.SMB, true)
        thumbs.settings.wifiOnly = false
        try {
            toBrowser()
            thumbs.clearCaches(); flushMemory(); NetworkStats.reset()

            // 1. First view of a folder of albums: the covers are read from the NAS, thumbnails are written.
            open(cfg.id, "/fixture")
            until(60_000, "a thumbnail") { shown("thumb_Album-A") }
            val first = NetworkStats.snapshot()
            assertTrue("covers were read: ${first.bytes}", first.bytes.getValue(ReadKind.IMAGE) > 0)
            assertTrue(first.thumbnailsFetched >= 1)
            assertTrue(first.folderLists >= 1)
            until(10_000, "thumbnail file") { thumbs.imageCache.usage(ImageTier.THUMB).files >= 1 }

            // 2. The memory cache dropped and the folder shown again: the disk answers, the NAS is not asked for an image.
            onUi { browser.exitSource() }
            flushMemory()
            open(cfg.id, "/fixture")
            until(30_000, "served from disk") { NetworkStats.snapshot().thumbHits > first.thumbHits && shown("thumb_Album-A") }
            assertEquals("no image was read again", first.bytes.getValue(ReadKind.IMAGE), imageBytes())

            // 3. Settings: the limit, the usage, the account of what was read; a lower limit is taken at once.
            toSettings()
            settingsReveal("cache_usage")
            until(10_000, "usage") { exists("cache_thumbs") && exists("cache_artwork") && exists("netstat_total") && exists("netstat_cache") }
            assertTrue(text("cache_usage"), text("cache_usage").contains("512 MB"))
            assertTrue("per-source read is listed", exists("netstat_source_${cfg.id}"))
            click("cachecap_128")
            until(5_000, "128 MB") { thumbs.settings.cacheLimitMb == 128 && text("cache_usage").contains("128 MB") }
            click("cachecap_512")
            until(5_000, "512 MB") { thumbs.settings.cacheLimitMb == 512 }
            click("netstat_reset")
            until(5_000, "counters reset") { NetworkStats.snapshot().totalBytes == 0L }

            // 4. Clear: the files are gone, and viewing the folder again reads the covers from the NAS again.
            click("cache_clear")
            until(20_000, "cleared") { thumbs.imageCache.usage(ImageTier.THUMB).files == 0 }
            toBrowser()
            onUi { browser.exitSource() }
            flushMemory()
            open(cfg.id, "/fixture")
            until(60_000, "thumbnail again") { shown("thumb_Album-A") }
            assertTrue("covers read again after Clear", imageBytes() > 0)

            // 5. Player artwork: made when an album is played, then served from disk.
            val album = SourceRef(cfg.id, "/fixture/Album-A")
            val before = NetworkStats.snapshot()
            onUi { player.playFolder(album, null) }
            until(60_000, "playing") { player.uiState.value.isPlaying }
            until(60_000, "player artwork made") { NetworkStats.snapshot().artworkFetched > before.artworkFetched }
            assertTrue(thumbs.imageCache.usage(ImageTier.ARTWORK).files >= 1)
            onUi { player.playPause() }
            until(10_000, "paused") { !player.uiState.value.isPlaying }

            // Asking for the picture again (memory cache off): served from the file, the NAS is not asked.
            val cover = runBlocking { thumbs.playbackCover(album, null) } as? ArtworkResult.Found ?: error("no cover")
            val uri = ImageUris.of(cover.image, cover.entry)
            fun displayPlayerArtwork(px: Int): Boolean {
                val req = ImageRequest.Builder(Fx.ctx).data(uri).size(px)
                    .setParameter(SourceImageFetcher.TIER_PARAM, SourceImageFetcher.TIER_ARTWORK)
                    .memoryCachePolicy(CachePolicy.DISABLED).build()
                return runBlocking { Coil.imageLoader(Fx.ctx).execute(req) } is SuccessResult
            }
            val s = NetworkStats.snapshot()
            assertTrue(displayPlayerArtwork(130)); assertTrue(displayPlayerArtwork(1000))
            val after = NetworkStats.snapshot()
            assertTrue("served from the file twice: ${after.artworkHits} vs ${s.artworkHits}", after.artworkHits >= s.artworkHits + 2)
            assertEquals("the picture was not made again", s.artworkFetched, after.artworkFetched)
            assertEquals("no image read again", s.bytes.getValue(ReadKind.IMAGE), imageBytes())

            // Playing the album again reads no image either (the surfaces already hold the picture).
            onUi { player.playFolder(album, null) }
            until(60_000, "playing again") { player.uiState.value.isPlaying }
            Thread.sleep(3_000)
            assertEquals(s.artworkFetched, NetworkStats.snapshot().artworkFetched)
            assertEquals(s.bytes.getValue(ReadKind.IMAGE), imageBytes())
            onUi { player.playPause() }
        } finally {
            thumbs.setCacheLimitMb(ArtworkSettings.DEFAULT_CACHE_MB)
            SourceRegistry.remove(cfg.id)
        }
    }
}

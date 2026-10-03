package com.wing.folderplayer

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.wing.folderplayer.data.nfo.NfoParser
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.readBytes
import com.wing.folderplayer.data.source.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * A `.nomedia` folder chosen through the system picker (ACTION_OPEN_DOCUMENT_TREE, real DocumentsUI) gives the
 * audio, cover.jpg, LRC and NFO that the File API cannot see; the grant survives a restart; after the grant is revoked
 * the source reports it and picking the folder again restores the same source.
 * Run the methods in order, one process each (instrument.sh run-each SafTest); `a_…` first.
 */
@RunWith(AndroidJUnit4::class)
class SafTest : ServiceTestBase() {
    override val keepSafSources = true

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val docsUi = Pattern.compile("com\\.(google\\.)?android\\.documentsui")
    private val purple = Triple(120, 30, 200)

    private fun safSource(): SourceConfig? =
        SourceRegistry.savedSources().firstOrNull { it.type == SourceType.SAF && Uri.decode(it.url).endsWith("Music/fixture/Unindexed") }

    private fun exists(tag: String) = runCatching { compose.onNodeWithTag(tag).assertExists(); true }.getOrDefault(false)

    private fun toBrowser() {
        compose.waitForIdle()
        // The browser is the start page (no page swipe any more); this suite works on its source list.
        compose.waitUntil(10_000) { exists("source_list") }
    }

    /** Navigates DocumentsUI from the storage root to [path] and grants access. */
    private fun pickInDocumentsUi(vararg path: String) {
        assertTrue("picker shown", device.wait(Until.hasObject(By.pkg(docsUi)), 15_000))
        device.waitForIdle()
        Thread.sleep(1_000)
        if (!device.hasObject(By.res("android:id/title").text(path.first()))) {
            val rootPattern = Pattern.compile("(Internal storage|sdk_gphone.*|Android SDK.*)")
            device.findObject(By.desc("Show roots"))?.click()
            if (device.wait(Until.findObject(By.text(rootPattern)), 4_000) == null) {
                // Android 8.x DocumentsUI hides device storage until "Show internal storage" is chosen in its menu.
                device.pressBack()
                device.findObject(By.desc("More options"))?.click()
                device.wait(Until.findObject(By.text(Pattern.compile("(?i)show internal storage"))), 4_000)?.click()
                device.waitForIdle()
                device.findObject(By.desc("Show roots"))?.click()
            }
            val root = device.wait(Until.findObject(By.text(rootPattern)), 10_000)
                ?: error("storage root not found")
            root.click()
            device.waitForIdle()
            Thread.sleep(800)
        }
        for (seg in path) {
            runCatching { UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().resourceId("android:id/title").text(seg)) }
            val item = device.wait(Until.findObject(By.res("android:id/title").text(seg)), 10_000) ?: error("folder $seg not shown")
            item.click()
            device.waitForIdle()
            Thread.sleep(800)
        }
        val use = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 10_000) ?: error("no 'Use this folder'")
        use.click()
        val allow = device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000) ?: error("no 'Allow'")
        allow.click()
        assertTrue("back in the app", device.wait(Until.hasObject(By.pkg(Fx.ctx.packageName)), 10_000))
    }

    private fun checkContents(src: SourceConfig) {
        val fs = SourceRegistry.fileSystem(src.id)
        val names = fs.list("/").map { it.name }.toSet()
        Fx.log("SAF Unindexed listing: $names")
        assertTrue(names.containsAll(listOf("track.flac", "cover.jpg", "track.lrc", "Info.nfo", ".nomedia")))
        val img = fs.readBytes("/cover.jpg", 1 shl 22)
        val bmp = BitmapFactory.decodeByteArray(img, 0, img.size) ?: error("cover.jpg not decodable")
        val c = centreColor(bmp)
        Fx.log("SAF cover colour $c")
        assertTrue("purple cover", close(c, purple))
        assertTrue(fs.readText("/track.lrc").contains("Unindexed LRC line"))
        val nfo = NfoParser.parse(fs.readBytes("/Info.nfo", 1 shl 20), "Info.nfo")
        Fx.log("SAF NFO: title=${nfo.title} fields=${nfo.fields} tracks=${nfo.tracks}")
        assertEquals("Unindexed Album", nfo.title)
        assertEquals("Hidden Artist", nfo.fields["Artist"])
        assertEquals("Hidden Track", nfo.tracks.single().title)
        // The same folder through the File API (the reason SAF is needed): .nomedia hides its contents.
        val viaFile = Fx.localFile("/Music/fixture/Unindexed").listFiles()?.map { it.name }.orEmpty()
        Fx.log("File API sees: $viaFile")
    }

    @Test fun a_pickNomediaFolderThroughSystemPicker() {
        safSource()?.let { old ->
            runCatching { Fx.ctx.contentResolver.releasePersistableUriPermission(Uri.parse(old.url), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            SourceRegistry.remove(old.id)
        }
        toBrowser()
        compose.onNodeWithTag("btn_add_source").performClick()
        compose.onNodeWithTag("menu_add_saf").performClick()
        pickInDocumentsUi("Music", "fixture", "Unindexed")
        compose.waitUntil(10_000) { safSource() != null }
        val src = safSource()!!
        assertTrue("persisted grant", Fx.ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == src.url && it.isReadPermission })
        compose.waitUntil(10_000) { exists("source_${src.name}") }
        checkContents(src)
    }

    @Test fun b_grantSurvivesRestartAndPlays() {
        val src = safSource() ?: error("run a_pickNomediaFolderThroughSystemPicker first")
        assertTrue("grant after restart", Fx.ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == src.url })
        checkContents(src)
        main { vm.playFolder(SourceRef(src.id, "/"), null) }
        waitFor(15_000, "SAF playback") { state.isPlaying && state.currentMediaId?.startsWith("fpsrc://${src.id}/") == true }
        waitFor(10_000, "SAF cover in player") { state.coverUri?.toString()?.contains("cover.jpg") == true }
        waitFor(10_000, "LRC from SAF") { state.lyrics.any { it.text.contains("Unindexed LRC line") } }
        waitFor(20_000, "notification cover") { notificationBitmap()?.let { close(centreColor(it), purple) } == true }
    }

    @Test fun c_revokedGrantIsReportedAndRepickRestoresSameSource() {
        val src = safSource() ?: error("run a_pickNomediaFolderThroughSystemPicker first")
        Fx.ctx.contentResolver.releasePersistableUriPermission(Uri.parse(src.url), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val err = runCatching { SourceRegistry.fileSystem(src.id).list("/") }.exceptionOrNull()
        Fx.log("after revoke: $err")
        assertTrue("revocation reported as permission problem: $err", err is SourceException.PermissionDenied)
        toBrowser()
        compose.onNodeWithTag("source_${src.name}").performTouchInput { longClick() }
        compose.onNodeWithText(Fx.ctx.getString(R.string.browser_repick_saf)).performClick()
        pickInDocumentsUi("Music", "fixture", "Unindexed")
        compose.waitUntil(10_000) { (safSource()?.revision ?: 0) > src.revision }
        val again = safSource()!!
        assertEquals("same source id after re-pick", src.id, again.id)
        assertEquals(1, SourceRegistry.savedSources().count { it.type == SourceType.SAF && it.url == src.url })
        checkContents(again)
    }

    /** Tricky names on SAF: a folder whose file names contain Japanese, space, `#`, `%` and `+`. */
    @Test fun d_trickyNamesThroughSaf() {
        SourceRegistry.savedSources().filter { it.type == SourceType.SAF && Uri.decode(it.url).endsWith("Music/fixture/Album-A") }.forEach { old ->
            runCatching { Fx.ctx.contentResolver.releasePersistableUriPermission(Uri.parse(old.url), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            SourceRegistry.remove(old.id)
        }
        toBrowser()
        compose.onNodeWithTag("btn_add_source").performClick()
        compose.onNodeWithTag("menu_add_saf").performClick()
        pickInDocumentsUi("Music", "fixture", "Album-A")
        compose.waitUntil(10_000) { SourceRegistry.savedSources().any { it.type == SourceType.SAF && Uri.decode(it.url).endsWith("Music/fixture/Album-A") } }
        val src = SourceRegistry.savedSources().first { it.type == SourceType.SAF && Uri.decode(it.url).endsWith("Music/fixture/Album-A") }
        val fs = SourceRegistry.fileSystem(src.id)
        val names = fs.list("/").map { it.name }
        Fx.log("SAF Album-A: $names")
        val tricky = "01 曲 #1+%.flac"
        assertTrue(names.containsAll(listOf(tricky, "01 曲 #1+%.lrc", "cover.jpg", "Info.nfo")))
        val ref = SourceRef(src.id, "/$tricky")
        assertEquals("single encoding round trip", ref, com.wing.folderplayer.data.source.SourceUris.parse(ref.toUriString()))
        main { vm.playFolder(SourceRef(src.id, "/"), "/$tricky") }
        waitFor(15_000, "tricky SAF track playing") { state.isPlaying && state.currentMediaId == ref.toUriString() }
        waitFor(10_000, "LRC with tricky name") { state.lyrics.any { it.text.contains("LRC line one 曲") } }
        waitFor(10_000, "cover") { state.coverUri?.toString()?.contains("cover.jpg") == true }
        val nfo = NfoParser.parse(fs.readBytes("/Info.nfo", 1 shl 20), "Info.nfo")
        assertEquals("Album A (NFO)", nfo.title)
    }
}

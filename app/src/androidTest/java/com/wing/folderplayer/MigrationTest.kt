package com.wing.folderplayer

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.playlist.PlaylistStore
import com.wing.folderplayer.data.prefs.PlaybackPreferences
import com.wing.folderplayer.data.prefs.SourcePreferences
import com.wing.folderplayer.data.source.KeystoreCredentialStore
import com.wing.folderplayer.data.source.LegacyDataMigrator
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Migration on a device: public-main (0.4 / schema 1) SharedPreferences and playlist files as the baseline app wrote them
 * are migrated by the real migrator, with the Keystore credential store and the on-disk backup.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val ctx = Fx.ctx
    private val webdavA = "11111111-aaaa-4bbb-8ccc-000000000001"
    private val webdavB = "11111111-aaaa-4bbb-8ccc-000000000002"

    private fun seedLegacy() {
        val sp = ctx.getSharedPreferences("source_prefs", Context.MODE_PRIVATE)
        sp.edit().clear()
            .putString("sources_list", """[{"id":"$webdavA","name":"My NAS","type":"WEBDAV","url":"http://192.168.2.10:5244/dav","path":"/Music","username":"alice","password":"pw-alice"},""" +
                """{"id":"$webdavB","name":"Broken","type":"WEBDAV"},""" +
                """{"id":"x","name":"Future","type":"QUANTUM","url":"q://x","password":"pw-future"}]""")
            .putString("last_source", """{"id":"$webdavA","name":"My NAS","type":"WEBDAV","url":"http://192.168.2.10:5244/dav","path":"/Music","username":"alice","password":"pw-alice"}""")
            .putString("last_path", "http://192.168.2.10:5244/dav/Music/Album%20B/")
            .putString("sort_field_/storage/emulated/0/Music/Album A", "DATE")
            .putBoolean("sort_asc_/storage/emulated/0/Music/Album A", false)
            .putString("default_sort_field", "SIZE")
            .commit()
        val pb = ctx.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
        pb.edit().clear()
            .putString("source_config", """{"id":"random-uuid","name":"Internal Storage","type":"LOCAL","url":"/storage/emulated/0","username":"","password":""}""")
            .putString("folder_path", "/storage/emulated/0/Music/Album A")
            .putString("last_media_id", "file:///storage/emulated/0/Music/Album%20A/01%20%E6%9B%B2%20%231%2B%25.flac")
            .putLong("last_position", 12345)
            .putString("cover_display_size", "LARGE")
            .putBoolean("auto_next_folder", true)
            .putString("active_playlist_id", "list_1")
            .commit()
        val dir = File(ctx.filesDir, "playlists").apply { deleteRecursively(); mkdirs() }
        File(dir, "metadata.json").writeText("""{"default":"Default","list_1":"Road trip"}""")
        File(dir, "default.fpl").writeText(
            """[{"path":"file:///storage/emulated/0/Music/Album%20A/01%20%E6%9B%B2%20%231%2B%25.flac","title":"Song 1","artist":"","sourceId":"local","artworkUri":"file:///storage/emulated/0/Music/Album%20A/cover.jpg","durationMs":0},""" +
                """{"path":"http://192.168.2.10:5244/dav/Music/Album%20B/track.flac","title":"Remote","artist":"A","sourceId":"http://192.168.2.10:5244/dav","durationMs":0},""" +
                """{"path":"smb://unknown/x.flac","title":"Unresolvable","artist":"","sourceId":"???","durationMs":0}]""")
        File(dir, "list_1.fpl").writeText("""[{"path":"/storage/emulated/0/Music/Album A/02 track.mp3","title":"Two","artist":"","sourceId":"local","durationMs":0}]""")
        File(dir, "corrupt.fpl").writeText("{ not a playlist")
    }

    @Test fun publicMainDataIsMigratedRecordByRecord() {
        SourceRegistry.init(ctx)
        seedLegacy()
        val creds = KeystoreCredentialStore(ctx)
        LegacyDataMigrator(ctx, creds).migrateIfNeeded(SourceRegistry.builtInSources())
        SourceRegistry.reload()

        // Sources: order/ids kept, password moved to the Keystore store, broken records kept aside.
        val saved = SourceRegistry.savedSources()
        assertEquals(listOf(webdavA), saved.map { it.id })
        assertEquals("/Music", saved[0].path)
        assertEquals("pw-alice", creds.get(saved[0].effectiveCredentialRef))
        val prefsXml = File(ctx.applicationInfo.dataDir, "shared_prefs/source_prefs.xml").readText()
        assertFalse("password left in source_prefs: $prefsXml", prefsXml.contains("pw-alice"))
        assertFalse(File(ctx.applicationInfo.dataDir, "shared_prefs/playback_prefs.xml").readText().contains("pw-"))
        assertEquals(2, SourceRegistry.rejectedRecords().size)
        assertTrue(SourceRegistry.rejectedRecords().none { it.contains("pw-future") })

        // Browser state and sort overrides.
        val sp = SourcePreferences(ctx)
        assertEquals(SourceRef(webdavA, "/Album B"), sp.getLastBrowsed())
        val albumA = SourceRef(SourceRegistry.LOCAL_INTERNAL_ID, "/Music/Album A")
        assertEquals("DATE", sp.getDirectorySort(albumA)?.field)
        assertEquals(false, sp.getDirectorySort(albumA)?.ascending)
        assertEquals("SIZE", sp.getDefaultSort().field)

        // Playback state.
        val pb = PlaybackPreferences(ctx)
        assertEquals(albumA, pb.getLastFolder())
        assertEquals(SourceRef(SourceRegistry.LOCAL_INTERNAL_ID, "/Music/Album A/01 曲 #1+%.flac"), SourceUris.parse(pb.getLastMediaId()))
        assertEquals(12345L, pb.getLastPosition())
        assertEquals("LARGE", pb.getCoverDisplaySize())
        assertTrue(pb.getAutoNextFolder())
        assertEquals("list_1", pb.getActivePlaylistId())

        // Playlists: each item converted, the unresolvable one kept, a corrupt file left untouched.
        val store = PlaylistStore(ctx)
        val def = store.getPlaylist("default")!!.items
        assertEquals(3, def.size)
        assertEquals(SourceRef(SourceRegistry.LOCAL_INTERNAL_ID, "/Music/Album A/01 曲 #1+%.flac"), def[0].ref)
        assertEquals("fpsrc://local-internal/Music/Album%20A/cover.jpg", def[0].artworkUri)
        assertEquals(SourceRef(webdavA, "/Album B/track.flac"), def[1].ref)
        assertEquals("", def[2].sourceId)
        assertEquals("Road trip", store.getAllPlaylists().first { it.id == "list_1" }.name)
        assertEquals("/Music/Album A/02 track.mp3", store.getPlaylist("list_1")!!.items.single().path)
        assertEquals("{ not a playlist", File(ctx.filesDir, "playlists/corrupt.fpl").readText())

        // Backup with secrets redacted.
        val backup = File(ctx.filesDir, "migration-backup").listFiles()!!.maxByOrNull { it.name }!!
        val bxml = File(backup, "source_prefs.xml").readText()
        assertTrue(bxml.contains("My NAS"))
        assertFalse(bxml.contains("pw-alice"))
        assertNotNull(File(backup, "playlists/default.fpl").takeIf { it.exists() })
    }
}

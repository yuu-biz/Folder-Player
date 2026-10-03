package com.wing.folderplayer.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** public-main (schema 1) JSON exactly as the baseline app wrote it with Gson. */
class SourceMigrationTest {
    private val baselineSources = """[
        {"id":"0b8a1f1e-1111-4c1d-9c77-aaaaaaaaaaaa","name":"My NAS","type":"WEBDAV","url":"http://192.168.2.10:5244/dav","path":"/Music","username":"alice","password":"pw-alice"},
        {"id":"0b8a1f1e-2222-4c1d-9c77-bbbbbbbbbbbb","name":"Second","type":"WEBDAV","url":"http://192.168.2.10:5244/dav","username":"bob","password":"pw-bob"}
    ]"""

    private val internal = SourceConfig(id = SourceRegistry.LOCAL_INTERNAL_ID, name = "Internal Storage", type = SourceType.LOCAL, url = "/storage/emulated/0", builtIn = true)
    private val sd = SourceConfig(id = "local-sd-1A2B-3C4D", name = "SD Card", type = SourceType.LOCAL, url = "/storage/1A2B-3C4D", builtIn = true)

    @Test fun sourcesKeepIdsOrderAndMovePasswords() {
        val store = InMemoryCredentialStore()
        val r = SourceMigration.migrateSourcesJson(baselineSources, store)
        assertEquals(listOf("My NAS", "Second"), r.sources.map { it.name })
        assertEquals("0b8a1f1e-1111-4c1d-9c77-aaaaaaaaaaaa", r.sources[0].id)
        assertEquals("/Music", r.sources[0].path)
        assertNull(r.sources[1].path)
        assertEquals("pw-alice", store.get(r.sources[0].effectiveCredentialRef))
        assertEquals("pw-bob", store.get(r.sources[1].effectiveCredentialRef))
        assertEquals(2, r.passwordsMoved)
        assertTrue(r.rejected.isEmpty())
        // New fields get defaults, not null.
        assertEquals("", r.sources[0].host)
        assertEquals(0, r.sources[0].port)
        assertFalse(com.google.gson.Gson().toJson(r.sources).contains("pw-"))
    }

    @Test fun brokenSingleRecordDoesNotResetTheRest() {
        val json = """[
            {"id":"a","name":"Good","type":"WEBDAV","url":"http://h/dav","password":"x"},
            {"id":"b","name":"Unknown type","type":"GOPHER","url":"gopher://h","password":"leak"},
            "not an object",
            {"id":"c","name":null,"type":"WEBDAV","url":"http://h2/dav","futureField":{"x":1}},
            {"id":"d","name":"No URL","type":"WEBDAV"}
        ]"""
        val r = SourceMigration.migrateSourcesJson(json, InMemoryCredentialStore())
        assertEquals(listOf("a", "c"), r.sources.map { it.id })
        assertEquals("WebDAV", r.sources[1].name) // null name repaired
        assertEquals(3, r.rejected.size)
        assertTrue(r.rejected.none { it.contains("leak") })
    }

    @Test fun duplicateIdsGetNewIds() {
        val json = """[{"id":"same","name":"A","type":"WEBDAV","url":"http://a"},{"id":"same","name":"B","type":"WEBDAV","url":"http://b"}]"""
        val r = SourceMigration.migrateSourcesJson(json, InMemoryCredentialStore())
        assertEquals(2, r.sources.map { it.id }.toSet().size)
    }

    @Test fun unparsableListIsReportedNotThrown() {
        val r = SourceMigration.migrateSourcesJson("[{\"password\":\"zz\"", InMemoryCredentialStore())
        assertTrue(r.sources.isEmpty())
        assertFalse(r.rejected.single().contains("zz"))
        assertTrue(SourceMigration.migrateSourcesJson(null, InMemoryCredentialStore()).sources.isEmpty())
    }

    @Test fun legacyLocalPathsBecomeRefs() {
        val all = listOf(internal, sd)
        assertEquals(SourceRef(internal.id, "/Music/Album A/01 曲.flac"),
            SourceMigration.legacyToRef("/storage/emulated/0/Music/Album A/01 曲.flac", all))
        // Uri.fromFile(...).toString() form, encoded once.
        assertEquals(SourceRef(internal.id, "/Music/a b#1.mp3"),
            SourceMigration.legacyToRef("file:///storage/emulated/0/Music/a%20b%231.mp3", all))
        assertEquals(SourceRef(sd.id, "/x.flac"), SourceMigration.legacyToRef("/storage/1A2B-3C4D/x.flac", all))
        assertNull(SourceMigration.legacyToRef("/data/other/x.flac", all))
    }

    @Test fun legacyWebDavUrlsBecomeRefsWithHint() {
        val store = InMemoryCredentialStore()
        val sources = listOf(internal) + SourceMigration.migrateSourcesJson(baselineSources, store).sources
        val url = "http://192.168.2.10:5244/dav/Music/Album%20B/%E6%9B%B2%201%2B.flac"
        // Longest root (/dav/Music of "My NAS") wins without a hint.
        assertEquals(SourceRef("0b8a1f1e-1111-4c1d-9c77-aaaaaaaaaaaa", "/Album B/曲 1+.flac"), SourceMigration.legacyToRef(url, sources))
        // With the hint of the second source (root /dav) the same URL maps there.
        assertEquals(SourceRef("0b8a1f1e-2222-4c1d-9c77-bbbbbbbbbbbb", "/Music/Album B/曲 1+.flac"),
            SourceMigration.legacyToRef(url, sources, "0b8a1f1e-2222-4c1d-9c77-bbbbbbbbbbbb"))
        assertNull(SourceMigration.legacyToRef("http://other-host/dav/x.mp3", sources))
    }

    @Test fun legacyCueMediaIdKeepsTrackFragmentInfo() {
        val all = listOf(internal)
        val legacy = "file:///storage/emulated/0/Cue/image.flac#track_183000"
        val ref = SourceMigration.legacyToRef(legacy, all)!!
        assertEquals("/Cue/image.flac", ref.path)
        assertEquals(183000L, SourceUris.cueStartMs(legacy))
    }

    @Test fun legacyPlaybackSourceConfigMatchesMigratedSource() {
        val store = InMemoryCredentialStore()
        val sources = listOf(internal) + SourceMigration.migrateSourcesJson(baselineSources, store).sources
        val webdavJson = """{"id":"0b8a1f1e-2222-4c1d-9c77-bbbbbbbbbbbb","name":"Second","type":"WEBDAV","url":"http://192.168.2.10:5244/dav","username":"bob","password":"pw-bob"}"""
        assertEquals("0b8a1f1e-2222-4c1d-9c77-bbbbbbbbbbbb", SourceMigration.legacySourceId(webdavJson, sources))
        // Local configs had a new random id on every launch: matched by root instead.
        val localJson = """{"id":"random-123","name":"Internal Storage","type":"LOCAL","url":"/storage/emulated/0","username":"","password":""}"""
        assertEquals(internal.id, SourceMigration.legacySourceId(localJson, sources))
        assertNull(SourceMigration.legacySourceId("null", sources))
    }

    @Test fun backupRedactsPasswordsInPrefsXml() {
        val xml = """<map><string name="sources_list">[{&quot;id&quot;:&quot;a&quot;,&quot;password&quot;:&quot;pa ss&amp;1&quot;,&quot;url&quot;:&quot;u&quot;}]</string></map>"""
        val red = LegacyDataMigrator.redact(xml)
        assertFalse(red.contains("pa ss"))
        assertTrue(red.contains("&quot;password&quot;:&quot;<removed>&quot;"))
        assertTrue(red.contains("&quot;url&quot;:&quot;u&quot;"))
    }
}

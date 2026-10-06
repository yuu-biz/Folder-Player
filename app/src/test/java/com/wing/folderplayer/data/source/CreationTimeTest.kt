package com.wing.folderplayer.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Sort by creation date: where it comes from and what stands in for it. */
class CreationTimeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun creationTimeIsTheCreationTimeOrElseTheModificationTime() {
        assertEquals(500L, MusicFile("a", "/a", false, 0, 1000, "s", createdAt = 500).createdOrModified)
        assertEquals("a source that does not tell", 1000L, MusicFile("a", "/a", false, 0, 1000, "s").createdOrModified)
        assertEquals("unknown stays unknown", 0L, MusicFile("a", "/a", false).createdOrModified)
    }

    @Test fun localListingAndStatCarryACreationTime() {
        val dir = tmp.newFolder("music")
        val f = java.io.File(dir, "song.flac").apply { writeText("x") }
        f.setLastModified(1_000_000_000_000L) // modification time long ago; the file itself was created just now
        val fs = LocalFileSystem(SourceConfig(id = "l", name = "l", type = SourceType.LOCAL, url = dir.path))
        val listed = fs.list("/").single()
        val stat = fs.stat("/song.flac")!!
        val now = System.currentTimeMillis()
        // A file system with birth times reports the time of creation; one without (Android, a JVM on some file systems)
        // reports nothing here (not the modification time again) and the sort falls back to the modification time.
        assertEquals(1_000_000_000_000L, listed.lastModified)
        assertEquals(listed.createdAt, stat.createdAt)
        assertTrue("created just now or unknown: ${listed.createdAt} vs $now", listed.createdAt == 0L || kotlin.math.abs(listed.createdAt - now) < 60_000)
        assertEquals(if (listed.createdAt == 0L) listed.lastModified else listed.createdAt, listed.createdOrModified)
    }
}

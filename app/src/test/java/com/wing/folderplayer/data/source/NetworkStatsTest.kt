package com.wing.folderplayer.data.source

import com.wing.folderplayer.testutil.InMemoryFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The rough network account: what a read is counted as, and that the counting wrapper changes nothing else. */
class NetworkStatsTest {
    @Before fun reset() = NetworkStats.reset()

    @Test fun readsAreSortedByKindOfFile() {
        assertEquals(ReadKind.IMAGE, NetworkStats.kindOf("/A/cover.JPG"))
        assertEquals(ReadKind.IMAGE, NetworkStats.kindOf("/A/folder.png"))
        for (p in listOf("/A/01.flac", "/A/01.LRC", "/A/disc.cue", "/A/Info.nfo", "/fav.json")) assertEquals(p, ReadKind.AUDIO_META, NetworkStats.kindOf(p))
        for (p in listOf("/A/readme.pdf", "/A/noextension")) assertEquals(p, ReadKind.OTHER, NetworkStats.kindOf(p))
    }

    @Test fun countersAddUpAndResetToZero() {
        NetworkStats.addRead("nas", ReadKind.IMAGE, 100)
        NetworkStats.addRead("nas", ReadKind.AUDIO_META, 900)
        NetworkStats.addRead("other", ReadKind.OTHER, 5)
        NetworkStats.addRead("nas", ReadKind.IMAGE, 0) // nothing read: nothing counted
        repeat(3) { NetworkStats.thumbnailHit() }; NetworkStats.thumbnailMiss(); NetworkStats.artworkMiss(); NetworkStats.artworkHit()
        NetworkStats.thumbnailFetched(); NetworkStats.artworkFetched(); NetworkStats.folderListed(); NetworkStats.folderListed()
        val s = NetworkStats.snapshot()
        assertEquals(1005L, s.totalBytes)
        assertEquals(mapOf("nas" to 1000L, "other" to 5L), s.perSource)
        assertEquals(100L, s.bytes.getValue(ReadKind.IMAGE))
        assertEquals(2L, s.folderLists); assertEquals(1L, s.thumbnailsFetched); assertEquals(1L, s.artworkFetched)
        assertEquals(4L, s.hits); assertEquals(2L, s.misses)
        assertEquals(4.0 / 6.0, s.hitRate!!, 1e-9)
        NetworkStats.reset()
        val z = NetworkStats.snapshot()
        assertEquals(0L, z.totalBytes); assertTrue(z.perSource.isEmpty()); assertNull("no lookup yet: no rate", z.hitRate)
        assertEquals(0L, z.folderLists)
    }

    @Test fun theWrapperCountsListsAndBothKindsOfReadPerSourceAndLeavesTheRestAlone() {
        val nas = InMemoryFileSystem("nas-1")
        nas.put("/Album/01.flac", ByteArray(1000) { it.toByte() })
        nas.put("/Album/cover.jpg", ByteArray(300))
        val fs: SourceFileSystem = CountingFileSystem(nas)
        assertEquals("nas-1", fs.config.id)
        assertEquals(2, fs.list("/Album").size)
        assertEquals(1L, NetworkStats.snapshot().folderLists)
        assertEquals(1000, fs.openRead("/Album/01.flac").use { it.readBytes() }.size)
        assertEquals(300, fs.openRead("/Album/cover.jpg").use { it.readBytes() }.size)
        // A random-access reader (what the decoder uses): counted as it reads, once.
        val buf = ByteArray(100)
        fs.openRandomAccess("/Album/01.flac").use { r -> assertEquals(100, r.read(0, buf, 0, 100)) }
        val s = NetworkStats.snapshot()
        assertEquals(1100L, s.bytes.getValue(ReadKind.AUDIO_META))
        assertEquals(300L, s.bytes.getValue(ReadKind.IMAGE))
        assertEquals(mapOf("nas-1" to 1400L), s.perSource)
        // Stat and the content are the wrapped file system's own.
        assertEquals(1000L, fs.stat("/Album/01.flac")!!.size)
    }
}

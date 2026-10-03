package com.wing.folderplayer.data.artwork

import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.testutil.InMemoryFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folder image rules against the fixture layout. "Images" are byte strings; "BAD" marks an undecodable file. */
class ArtworkResolverTest {
    private val fs = InMemoryFileSystem("fx").apply {
        put("/fixture/Album-A/01 曲 #1+%.flac", "audio")
        put("/fixture/Album-A/02 track.mp3", "audio")
        put("/fixture/Album-A/cover.jpg", "IMG-cover")
        put("/fixture/Album-A/folder.png", "IMG-folder")
        put("/fixture/Album-B/track.flac", "audio")
        put("/fixture/Album-B/arbitrary-name.png", "IMG-arbitrary")
        put("/fixture/Album-C/track-with-embedded-art.mp3", "audio")
        put("/fixture/Parent/cover.jpg", "IMG-parent")
        put("/fixture/Parent/CD1/track.flac", "audio")
        put("/fixture/ChildOnly/track.flac", "audio")
        put("/fixture/ChildOnly/Artwork/cover.jpg", "IMG-child")
        put("/fixture/LongChildName/track.flac", "audio")
        put("/fixture/CorruptCover/track.flac", "audio")
        put("/fixture/CorruptCover/cover.jpg", "BAD")
        put("/fixture/CorruptCover/folder.png", "IMG-folder-ok")
        put("/fixture/Order/zz.png", "IMG-zz")
        put("/fixture/Order/Disk.JPG", "IMG-disk")
        put("/fixture/Order/front.png", "IMG-front")
        put("/fixture/Order/FRONT.jpeg", "IMG-front-jpeg")
        put("/fixture/Order/front.jpg", "IMG-front-jpg")
        put("/fixture/Order/Album.png", "IMG-album")
        put("/fixture/Order/aa.jpg", "IMG-aa")
        put("/fixture/Order/notes.txt", "x")
    }

    private val resolver = ArtworkResolver(
        lister = { ref -> fs.list(ref.path) },
        validate = { e -> String(fs.files[e.path]!!).startsWith("IMG") },
    )

    private fun resolveName(folder: String): String? =
        (resolver.resolve(SourceRef("fx", folder)) as? ArtworkResult.Found)?.image?.path

    @Test fun fixedPriorityBeatsListingOrder() {
        assertEquals("/fixture/Album-A/cover.jpg", resolveName("/fixture/Album-A"))
        val names = ArtworkRules.candidates(fs.list("/fixture/Order")).map { it.name }
        assertEquals(listOf("Album.png", "front.jpg", "FRONT.jpeg", "front.png", "Disk.JPG", "aa.jpg", "zz.png"), names)
    }

    @Test fun arbitraryNameIsUsedWhenNoPriorityName() {
        assertEquals("/fixture/Album-B/arbitrary-name.png", resolveName("/fixture/Album-B"))
    }

    @Test fun shortFolderFallsBackToParentOnce() {
        assertEquals("/fixture/Parent/cover.jpg", resolveName("/fixture/Parent/CD1"))
    }

    @Test fun longFolderAndChildArtworkAreNotSearched() {
        assertEquals(ArtworkResult.None, resolver.resolve(SourceRef("fx", "/fixture/LongChildName")))
        // ChildOnly has 9 chars (> 6): no parent lookup, and Artwork/ is never entered.
        assertEquals(ArtworkResult.None, resolver.resolve(SourceRef("fx", "/fixture/ChildOnly")))
        assertEquals(ArtworkResult.None, resolver.resolve(SourceRef("fx", "/fixture/Album-C")))
    }

    @Test fun brokenCoverFallsBackToNextCandidate() {
        assertEquals("/fixture/CorruptCover/folder.png", resolveName("/fixture/CorruptCover"))
    }

    @Test fun allBrokenIsReportedAsDecodeFailureNotNone() {
        val f = InMemoryFileSystem("x").apply { put("/A/cover.jpg", "BAD"); put("/A/t.flac", "a") }
        val r = ArtworkResolver({ fs2 -> f.list(fs2.path) }, { e -> String(f.files[e.path]!!).startsWith("IMG") }).resolve(SourceRef("x", "/A"))
        assertTrue(r is ArtworkResult.Failed && r.kind == ArtworkResult.Kind.DECODE)
    }

    @Test fun permissionErrorIsNotNoImage() {
        fs.unreadable.add("/fixture/Album-B")
        val r = resolver.resolve(SourceRef("fx", "/fixture/Album-B"))
        assertTrue(r is ArtworkResult.Failed && r.kind == ArtworkResult.Kind.PERMISSION)
        fs.unreadable.clear()
    }

    @Test fun readErrorTriesNextCandidateAndIsReportedAsNetworkNotThrown() {
        val f = InMemoryFileSystem("n").apply { put("/A/cover.jpg", "IMG-c"); put("/A/folder.png", "IMG-f"); put("/A/t.flac", "a") }
        val eof = com.wing.folderplayer.data.source.SourceException.PrematureEof(485, 242)
        // cover.jpg cannot be read (truncated transfer); folder.png still works.
        val partly = ArtworkResolver({ ref -> f.list(ref.path) }, { e -> if (e.name == "cover.jpg") throw eof else true })
        assertEquals("/A/folder.png", (partly.resolve(SourceRef("n", "/A")) as ArtworkResult.Found).image.path)
        // Nothing readable: the network error is the result (not None, which would be cached as "no image").
        val none = ArtworkResolver({ ref -> f.list(ref.path) }, { throw eof }).resolve(SourceRef("n", "/A"))
        assertTrue(none is ArtworkResult.Failed && none.kind == ArtworkResult.Kind.NETWORK)
    }

    @Test fun parentFallbackNeverLeavesRoot() {
        val f = InMemoryFileSystem("r").apply { put("/CD1/t.flac", "a"); put("/cover.jpg", "IMG") }
        // "/CD1" is short; its parent is the source root, which is allowed (still inside the source).
        val r = ArtworkResolver({ ref -> f.list(ref.path) }, { true }).resolve(SourceRef("r", "/CD1"))
        assertTrue(r is ArtworkResult.Found)
        assertEquals(null, ArtworkRules.parentForFallback("/"))
    }

    @Test fun lateCoverIsFoundOnNextResolve() {
        val f = InMemoryFileSystem("l").apply { put("/LateCover/track.flac", "a") }
        val res = ArtworkResolver({ ref -> f.list(ref.path) }, { true })
        assertEquals(ArtworkResult.None, res.resolve(SourceRef("l", "/LateCover")))
        f.put("/LateCover/cover.jpg", "IMG")
        assertTrue(res.resolve(SourceRef("l", "/LateCover")) is ArtworkResult.Found)
    }
}

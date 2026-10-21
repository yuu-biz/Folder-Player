package com.wing.folderplayer.data.viewer

import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.testutil.InMemoryFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

/** The browser's file viewer: which files it opens, how text is decoded, and the local copies of images and PDFs. */
class FileViewerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ja = Locale.JAPAN
    private val zhCn = Locale.SIMPLIFIED_CHINESE
    private val zhTw = Locale.TRADITIONAL_CHINESE

    @Test fun kindsByExtension() {
        assertEquals(ViewerKind.TEXT, ViewerTypes.kind("readme.TXT"))
        assertEquals(ViewerKind.TEXT, ViewerTypes.kind("rip.log"))
        assertEquals(ViewerKind.TEXT, ViewerTypes.kind("Info.nfo"))
        assertEquals(ViewerKind.IMAGE, ViewerTypes.kind("Cover.JPG"))
        assertEquals(ViewerKind.IMAGE, ViewerTypes.kind("back.webp"))
        assertEquals(ViewerKind.PDF, ViewerTypes.kind("booklet.pdf"))
        assertNull(ViewerTypes.kind("01 Track.flac"))
        assertNull(ViewerTypes.kind("archive.zip"))
        assertNull(ViewerTypes.kind("no extension"))
        assertTrue(ViewerTypes.monospaceByDefault("EAC.log"))
        assertFalse(ViewerTypes.monospaceByDefault("notes.txt"))
    }

    @Test fun mimeTypesFallBackToTheKind() {
        val table = mapOf("txt" to "text/plain", "jpg" to "image/jpeg", "log" to "application/octet-stream")
        assertEquals("text/plain", ViewerTypes.mimeType("a.txt", table::get))
        assertEquals("text/plain", ViewerTypes.mimeType("a.nfo", table::get))
        // A text file the table calls binary still goes out as text.
        assertEquals("text/plain", ViewerTypes.mimeType("a.log", table::get))
        assertEquals("image/jpeg", ViewerTypes.mimeType("a.jpg", table::get))
        assertEquals("image/*", ViewerTypes.mimeType("a.heic", table::get))
        assertEquals("application/pdf", ViewerTypes.mimeType("a.pdf", table::get))
    }

    @Test fun utf8AndByteOrderMarks() {
        val text = "曲目リスト\n01. 夜に駆ける"
        assertEquals(TextPreview(text, false, TextEncoding.UTF_8), ViewerText.decode(text.toByteArray(Charsets.UTF_8), false, TextEncoding.AUTO, ja))
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(TextPreview(text, false, TextEncoding.UTF_16LE), ViewerText.decode(bom, false, TextEncoding.AUTO, Locale.US))
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, ViewerText.decode(utf8Bom, false, TextEncoding.AUTO, Locale.US).text)
    }

    @Test fun legacyEncodingFollowsTheDeviceLanguage() {
        val text = "アルバム解説：このアルバムは１９９８年に録音された。"
        val sjis = text.toByteArray(charset("windows-31j"))
        assertEquals(TextPreview(text, false, TextEncoding.SHIFT_JIS), ViewerText.decode(sjis, false, TextEncoding.AUTO, ja))
        val gb = "专辑介绍：这张专辑录制于一九九八年。"
        assertEquals(TextPreview(gb, false, TextEncoding.GB18030), ViewerText.decode(gb.toByteArray(charset("GB18030")), false, TextEncoding.AUTO, zhCn))
        val big5 = "專輯介紹：這張專輯錄製於一九九八年。"
        assertEquals(TextPreview(big5, false, TextEncoding.BIG5), ViewerText.decode(big5.toByteArray(charset("Big5")), false, TextEncoding.AUTO, zhTw))
    }

    @Test fun chosenEncodingIsUsedAsItIs() {
        val text = "日本語のテキスト"
        val sjis = text.toByteArray(charset("windows-31j"))
        // On an English device the guess is GB18030 (as for lyrics); picking Shift_JIS by hand gives the text.
        assertEquals(TextEncoding.GB18030, ViewerText.decode(sjis, false, TextEncoding.AUTO, Locale.US).encoding)
        assertEquals(TextPreview(text, false, TextEncoding.SHIFT_JIS), ViewerText.decode(sjis, false, TextEncoding.SHIFT_JIS, Locale.US))
        // A wrong choice shows replacement characters rather than failing.
        assertTrue(ViewerText.decode(sjis, false, TextEncoding.UTF_8, Locale.US).text.contains('�'))
    }

    @Test fun truncatedTextMayEndInsideACharacter() {
        val bytes = "あいう".toByteArray(Charsets.UTF_8) // 9 bytes, 3 per character
        val cut = bytes.copyOf(7)
        // Strict UTF-8 still accepts it: the incomplete last character is dropped, the guess does not fall to Shift_JIS.
        assertEquals(TextPreview("あい", true, TextEncoding.UTF_8), ViewerText.decode(cut, true, TextEncoding.AUTO, ja))
        // A complete file with the same bytes is not UTF-8.
        assertFalse(ViewerText.decode(cut, false, TextEncoding.AUTO, ja).encoding == TextEncoding.UTF_8)
    }

    @Test fun readStopsAtTheLimit() {
        val fs = InMemoryFileSystem()
        fs.put("/a/short.txt", "hello")
        fs.put("/a/long.txt", ByteArray(5000) { 'x'.code.toByte() })
        fs.put("/a/exact.txt", ByteArray(100) { 'y'.code.toByte() })
        val (short, shortCut) = ViewerText.read(fs, "/a/short.txt", 100)
        assertEquals("hello", String(short)); assertFalse(shortCut)
        val (long, longCut) = ViewerText.read(fs, "/a/long.txt", 100)
        assertEquals(100, long.size); assertTrue(longCut)
        val (exact, exactCut) = ViewerText.read(fs, "/a/exact.txt", 100)
        assertEquals(100, exact.size); assertFalse(exactCut)
    }

    private fun entry(fs: InMemoryFileSystem, path: String) = fs.stat(path)!!

    @Test fun copyIsMadeOnceAndAgainWhenTheFileChanges() {
        val fs = InMemoryFileSystem()
        val cache = ViewerCache(tmp.newFolder("viewer"))
        val data = ByteArray(300_000) { (it % 251).toByte() }
        fs.put("/Album/booklet.pdf", data, mtime = 1000)
        val progress = mutableListOf<Long>()
        val first = cache.fetch(fs, entry(fs, "/Album/booklet.pdf")) { progress += it }
        assertEquals("booklet.pdf", first.name)
        assertArrayEquals(data, first.readBytes())
        assertEquals(300_000L, progress.last())
        assertTrue(first.parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })

        // Same size and time: the copy is used, the source is not read (its bytes changed without telling).
        fs.put("/Album/booklet.pdf", ByteArray(300_000), mtime = 1000)
        val again = cache.fetch(fs, entry(fs, "/Album/booklet.pdf")) { fail("read again") }
        assertEquals(first, again)
        assertArrayEquals(data, again.readBytes())

        // A new modification time is a new file.
        fs.put("/Album/booklet.pdf", ByteArray(300_000) { 7 }, mtime = 2000)
        val changed = cache.fetch(fs, entry(fs, "/Album/booklet.pdf"))
        assertFalse(changed == first)
        assertEquals(7, changed.readBytes()[0].toInt())
    }

    @Test fun tooLargeAndBrokenReadsLeaveNoCopy() {
        val fs = InMemoryFileSystem()
        val dir = tmp.newFolder("viewer")
        val cache = ViewerCache(dir)
        fs.put("/big.pdf", ByteArray(2000))
        try {
            cache.fetch(fs, entry(fs, "/big.pdf"), limit = 1000); fail()
        } catch (e: SourceException.TooLarge) { }
        fs.put("/cut.jpg", ByteArray(2000))
        fs.truncateAt["/cut.jpg"] = 1500
        try {
            cache.fetch(fs, entry(fs, "/cut.jpg")); fail()
        } catch (e: SourceException.PrematureEof) { }
        assertEquals(0L, cache.usedBytes())
        assertTrue(dir.walk().none { it.isFile })
    }

    @Test fun oldestCopiesGoFirst() {
        val fs = InMemoryFileSystem()
        val cache = ViewerCache(tmp.newFolder("viewer"), maxBytes = 2500)
        val files = (1..4).map { i ->
            fs.put("/f$i.jpg", ByteArray(1000) { i.toByte() }, mtime = i.toLong())
            cache.fetch(fs, entry(fs, "/f$i.jpg")).also { it.parentFile!!.setLastModified(1_000_000L * i) }
        }
        cache.trim()
        assertTrue(cache.usedBytes() <= 2500)
        assertFalse(files[0].exists())
        assertFalse(files[1].exists())
        assertTrue(files[2].exists())
        assertTrue(files[3].exists())
        // The copy being opened stays even when it alone is over the limit.
        fs.put("/huge.pdf", ByteArray(4000))
        val huge = cache.fetch(fs, entry(fs, "/huge.pdf"))
        assertTrue(huge.exists())
    }

    @Test fun twoCallersForOneFileGetOneCompleteCopy() {
        val fs = InMemoryFileSystem()
        val cache = ViewerCache(tmp.newFolder("viewer"))
        val data = ByteArray(200_000) { (it % 13).toByte() }
        fs.put("/Album/scan.png", data)
        fs.readDelayMs = 2
        val results = arrayOfNulls<java.io.File>(2)
        val threads = (0..1).map { i -> Thread { results[i] = cache.fetch(fs, entry(fs, "/Album/scan.png")) } }
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }
        assertEquals(results[0], results[1])
        assertArrayEquals(data, results[0]!!.readBytes())
    }

    @Test fun namesALocalFileCanHave() {
        assertEquals("a_b_c.txt", ViewerCache.safeName("a/b:c.txt"))
        assertEquals("file", ViewerCache.safeName("..."))
        val long = ViewerCache.safeName("x".repeat(300) + ".pdf")
        assertTrue(long.length <= 120)
        assertTrue(long.endsWith(".pdf"))
        val cache = ViewerCache(tmp.newFolder("viewer"))
        val a = cache.fileFor(MusicFile("a.txt", "/x/a.txt", false, 10, 1, "s1"))
        val b = cache.fileFor(MusicFile("a.txt", "/x/a.txt", false, 10, 1, "s2"))
        assertFalse(a == b)
    }
}

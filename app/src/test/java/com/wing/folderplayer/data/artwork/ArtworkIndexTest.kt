package com.wing.folderplayer.data.artwork

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The folder-image index is read off the caller's thread, and changes made meanwhile are not lost or undone. */
class ArtworkIndexTest {
    @get:Rule val tmp = TemporaryFolder()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "index-io") }
    private val scope = CoroutineScope(executor.asCoroutineDispatcher())

    @After fun tearDown() { executor.shutdownNow() }

    private fun file() = File(tmp.root, "artwork-index.json").apply {
        writeText("""{"a|r0":{"image":"fpsrc://s/a/cover.jpg","name":"cover.jpg","size":10,"mtime":20},"b|r0":{"image":"fpsrc://s/b/cover.jpg","name":"cover.jpg","size":11,"mtime":21}}""")
    }

    private fun e(name: String) = ArtworkIndex.Entry("fpsrc://s/$name", name, 1, 2)

    private fun awaitFile(f: File, ok: (String) -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) { if (f.exists() && ok(f.readText())) return; Thread.sleep(10) }
        throw AssertionError("file never matched: ${if (f.exists()) f.readText() else "missing"}")
    }

    @Test fun readsAndParsesTheFileOffTheCallersThread() {
        val readerThread = AtomicReference<String>()
        val release = CountDownLatch(1)
        val f = file()
        val t0 = System.nanoTime()
        val index = ArtworkIndex(f, scope, 20) { file ->
            readerThread.set(Thread.currentThread().name)
            release.await(10, TimeUnit.SECONDS)
            file.readText()
        }
        // Creating the index (first resolve on the main thread) does not wait for the read, even while it is stuck.
        assertTrue("constructor returned at once", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1_000)
        release.countDown()
        val entry = runBlocking { index.get("a|r0") }
        assertEquals("cover.jpg", entry?.name)
        assertTrue("read on the I/O thread: ${readerThread.get()}", readerThread.get().startsWith("index-io"))
        assertNotEquals(Thread.currentThread().name, readerThread.get())
    }

    @Test fun getWaitsForTheFileInsteadOfAnsweringEmpty() {
        val release = CountDownLatch(1)
        val index = ArtworkIndex(file(), scope, 20) { f -> release.await(10, TimeUnit.SECONDS); f.readText() }
        val answer = AtomicReference<ArtworkIndex.Entry?>()
        val done = CountDownLatch(1)
        Thread { answer.set(runBlocking { index.get("b|r0") }); done.countDown() }.start()
        assertFalse("not answered while the file is still being read", done.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(11L, answer.get()?.size)
    }

    @Test fun changesBeforeTheLoadAreKeptAndRemovalsAlsoHitTheFile() {
        val release = CountDownLatch(1)
        val f = file()
        val index = ArtworkIndex(f, scope, 20) { x -> release.await(10, TimeUnit.SECONDS); x.readText() }
        index.put("c|r0", e("c"))      // new entry
        index.remove("a|r0")           // removed before the file was merged
        index.put("d|r0", e("d")); index.remove("d|r0") // put, then removed
        release.countDown()
        runBlocking {
            assertNull("removed key does not come back from the file", index.get("a|r0"))
            assertEquals("untouched file entry", "cover.jpg", index.get("b|r0")?.name)
            assertEquals("entry added meanwhile", "c", index.get("c|r0")?.name)
            assertNull(index.get("d|r0"))
        }
    }

    @Test fun saveAfterALateLoadKeepsTheFilesEntries() {
        val release = CountDownLatch(1)
        val f = file()
        val index = ArtworkIndex(f, scope, 20) { x -> release.await(10, TimeUnit.SECONDS); x.readText() }
        index.put("c|r0", e("c")) // a save is scheduled while the file has not been read yet
        Thread.sleep(150)         // longer than the save delay: it must still wait for the load
        assertFalse("nothing written over the file before it was read", f.readText().contains("\"c|r0\""))
        release.countDown()
        awaitFile(f) { it.contains("\"c|r0\"") }
        val text = f.readText()
        assertTrue("old entries survive the save: $text", text.contains("\"a|r0\"") && text.contains("\"b|r0\""))
    }

    @Test fun removePrefixAndClear() {
        val f = file()
        val index = ArtworkIndex(f, scope, 20)
        runBlocking { index.get("a|r0") }
        index.put("a2|r0", e("a2"))
        index.removePrefix("a")
        runBlocking { assertNull(index.get("a|r0")); assertNull(index.get("a2|r0")); assertEquals("cover.jpg", index.get("b|r0")?.name) }
        awaitFile(f) { !it.contains("\"a|r0\"") && it.contains("\"b|r0\"") }
        index.clear()
        assertFalse("clear deletes the file", f.exists())
        Thread.sleep(150)
        assertFalse("a cancelled save does not bring it back", f.exists() && f.readText().contains("cover.jpg"))
        assertEquals(0, index.size)
    }

    @Test fun clearBeforeTheLoadAlsoDropsWhatTheFileHeld() {
        val release = CountDownLatch(1)
        val f = file()
        val index = ArtworkIndex(f, scope, 20) { x -> release.await(10, TimeUnit.SECONDS); x.readText() }
        index.clear()
        release.countDown()
        runBlocking { assertNull(index.get("a|r0")); assertNull(index.get("b|r0")) }
        assertEquals(0, index.size)
    }

    @Test fun unreadableFileStartsEmptyAndTheNextSaveReplacesIt() {
        val f = File(tmp.root, "artwork-index.json").apply { writeText("{ not json") }
        val index = ArtworkIndex(f, scope, 20)
        runBlocking { assertNull(index.get("a|r0")) }
        index.put("c|r0", e("c"))
        awaitFile(f) { it.contains("\"c|r0\"") }
    }

    @Test fun missingFileIsFine() {
        val index = ArtworkIndex(File(tmp.root, "none.json"), scope, 20)
        runBlocking { assertNull(index.get("x")) }
    }
}

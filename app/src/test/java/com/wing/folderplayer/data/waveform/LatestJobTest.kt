package com.wing.folderplayer.data.waveform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
class LatestJobTest {

    @Test fun `watchers of the same key share one run`() = runTest {
        val jobs = LatestJob<String, Int>(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val block: suspend (suspend (Int) -> Unit) -> Int? = { runs++; gate.await(); 7 }
        val first = async { jobs.watch("a", block).toList() }
        val second = async { jobs.watch("a", block).toList() }
        runCurrent()
        gate.complete(Unit)
        assertEquals(listOf(7), first.await())
        assertEquals(listOf(7), second.await())
        assertEquals(1, runs)
    }

    @Test fun `a run outlives its only watcher and a later watcher joins it`() = runTest {
        val jobs = LatestJob<String, Int>(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        var finished = false
        val block: suspend (suspend (Int) -> Unit) -> Int? = { progress ->
            runs++
            progress(1)
            gate.await()
            finished = true
            2
        }
        val gone = launch { jobs.watch("a", block).toList() }
        runCurrent()
        gone.cancel()
        runCurrent()
        val later = async { jobs.watch("a", block).toList() }
        runCurrent()
        gate.complete(Unit)
        assertEquals(listOf(1, 2), later.await())
        assertTrue(finished)
        assertEquals(1, runs)
    }

    @Test fun `a run finishes with no watcher at all`() = runTest {
        val jobs = LatestJob<String, Int>(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var finished = false
        val gone = launch { jobs.watch("a") { gate.await(); finished = true; 2 }.toList() }
        runCurrent()
        gone.cancel()
        gate.complete(Unit)
        runCurrent()
        assertTrue(finished)
    }

    @Test fun `a watch after the run finished runs the block again`() = runTest {
        val jobs = LatestJob<String, Int>(backgroundScope)
        var runs = 0
        assertEquals(listOf(1), jobs.watch("a") { ++runs }.toList())
        assertEquals(listOf(2), jobs.watch("a") { ++runs }.toList())
    }

    @Test fun `another key cancels the running job and its watchers end without a result`() = runTest {
        val jobs = LatestJob<String, Int>(backgroundScope)
        var cancelled = false
        val old = async {
            jobs.watch("a") { progress ->
                progress(1)
                try { awaitCancellation() } finally { cancelled = true }
            }.toList()
        }
        runCurrent()
        val next = async { jobs.watch("b") { 5 }.toList() }
        assertEquals(listOf(1), old.await())
        assertTrue(cancelled)
        assertEquals(listOf(5), next.await())
    }

    @Test fun `progress reaches a watcher before the result`() = runTest {
        val jobs = LatestJob<String, Int>(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<Int>()
        val watcher = launch { jobs.watch("a") { progress -> progress(1); gate.await(); 3 }.collect { seen += it } }
        runCurrent()
        assertEquals(listOf(1), seen)
        gate.complete(Unit)
        watcher.join()
        assertEquals(listOf(1, 3), seen)
    }

    @Test fun `a block returning null ends its watchers without a value`() = runTest {
        val jobs = LatestJob<String, Int>(backgroundScope)
        assertEquals(emptyList<Int>(), jobs.watch("a") { null }.toList())
    }
}

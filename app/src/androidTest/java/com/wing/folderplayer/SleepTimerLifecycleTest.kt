package com.wing.folderplayer

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.service.MusicService
import com.wing.folderplayer.ui.player.PlayerUiState
import com.wing.folderplayer.ui.player.PlayerViewModel
import com.wing.folderplayer.ui.player.TimerType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sleep timer belongs to the playback, not to the screen: playback goes on in MusicService after the activity is
 * gone, and the timer must still stop it. A new activity shows (and can cancel) the running timer.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerLifecycleTest {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val long = MusicFile("long.flac", "/Music/fixture/Long/long.flac", false, 0, 0, local)
    private fun tiny(i: Int) = "%03d".format(i).let { MusicFile("track $it.flac", "/Music/fixture/Many/Folder $it/track $it.flac", false, 0, 0, local) }

    private val open = mutableListOf<ActivityScenario<MainActivity>>()

    private fun <T> main(block: () -> T): T {
        var r: Any? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(200)
        }
        fail("timed out waiting for $what")
    }

    /** Opens the app like the launcher does; returns the activity's PlayerViewModel once it is connected. */
    private fun launch(): Pair<ActivityScenario<MainActivity>, PlayerViewModel> {
        val sc = ActivityScenario.launch(MainActivity::class.java).also { open += it }
        var vm: PlayerViewModel? = null
        sc.onActivity { vm = ViewModelProvider(it)[PlayerViewModel::class.java] }
        waitFor(15_000, "player connected") { main { vm!!.controllerReady.value } }
        main { if (vm!!.uiState.value.autoNextFolder) vm!!.toggleAutoNextFolder() }
        return sc to vm!!
    }

    /** Leaves the app (Back on the last screen): the activity is destroyed and its ViewModel cleared. */
    private fun ActivityScenario<MainActivity>.finish() { close(); open -= this }

    private fun PlayerViewModel.s(): PlayerUiState = main { uiState.value }
    private fun servicePlaying(): Boolean = main { MusicService.current?.isPlayingForTest } == true

    @Before fun setUp() { SourceRegistry.init(Fx.ctx) }

    @After fun tearDown() {
        open.toList().forEach { it.finish() }
        // Stop what is still playing and any timer, through a UI like the user would.
        val (sc, vm) = launch()
        main { vm.resetSleepTimer(); if (vm.uiState.value.isPlaying) vm.playPause() }
        Thread.sleep(500)
        sc.finish()
    }

    @Test fun timeTimerStopsPlaybackAfterTheActivityIsGone() {
        val (sc, vm) = launch()
        main { vm.playCustomList(listOf(long), 0) }
        waitFor(15_000, "playing") { vm.s().isPlaying }
        main { vm.startSleepTimer(TimerType.TIME, 1) }
        val started = SystemClock.elapsedRealtime()
        sc.finish()

        Thread.sleep(3_000)
        assertTrue("playback goes on without the activity", servicePlaying())
        waitFor(90_000, "the 1-minute timer pauses playback with no UI") { !servicePlaying() }
        val after = SystemClock.elapsedRealtime() - started
        Fx.log("time timer paused after $after ms")
        assertTrue("paused by the timer at its deadline, not earlier: $after ms", after in 55_000..75_000)
    }

    @Test fun songTimerStopsPlaybackAfterTheActivityIsGone() {
        val (sc, vm) = launch()
        // Timer first: the 2-second first track must not end before the timer is set.
        main { vm.startSleepTimer(TimerType.SONGS, 1) }
        main { vm.playCustomList(listOf(tiny(20), long), 0) }
        waitFor(15_000, "playing") { vm.s().isPlaying }
        sc.finish()

        waitFor(20_000, "paused after the first song with no UI") { !servicePlaying() }
        Thread.sleep(1_500)
        assertFalse("stays paused", servicePlaying())
        val (_, next) = launch()
        waitFor(10_000, "stopped on the second track") { next.s().currentMediaId?.contains("long.flac") == true }
        assertFalse(next.s().isPlaying)
        assertFalse("timer done", next.s().sleepTimerActive)
    }

    @Test fun nextActivityShowsAndCancelsTheRunningTimer() {
        val (sc, vm) = launch()
        main { vm.playCustomList(listOf(long), 0) }
        waitFor(15_000, "playing") { vm.s().isPlaying }
        main { vm.startSleepTimer(TimerType.TIME, 30) }
        sc.finish()

        val (sc2, vm2) = launch()
        waitFor(10_000, "the new UI shows the running timer") {
            vm2.s().let { it.sleepTimerActive && it.sleepTimerType == TimerType.TIME && it.sleepTimerValue in 29..30 }
        }
        main { vm2.resetSleepTimer() }
        waitFor(5_000, "cancelled") { !vm2.s().sleepTimerActive }
        sc2.finish()

        val (_, vm3) = launch()
        Thread.sleep(2_000)
        assertFalse("the timer was cancelled in the service, not only on screen", vm3.s().sleepTimerActive)
        assertTrue(servicePlaying())
    }

    @Test fun recreatedActivityKeepsOneTimer() {
        val (sc, vm) = launch()
        main { vm.startSleepTimer(TimerType.SONGS, 2) }
        sc.recreate() // e.g. language change
        var vmAfter: PlayerViewModel? = null
        sc.onActivity { vmAfter = ViewModelProvider(it)[PlayerViewModel::class.java] }
        val v = vmAfter!!
        waitFor(10_000, "timer still shown") { v.s().sleepTimerActive && v.s().sleepTimerValue == 2 }
        main { v.playCustomList(listOf(tiny(20), tiny(21), long), 0) }
        // Counted once per song: stops at the start of the third track, not the second.
        waitFor(20_000, "paused on the third track") { !servicePlaying() && v.s().currentMediaId?.contains("long.flac") == true }
        Thread.sleep(1_500)
        assertFalse("stays paused", servicePlaying())
        assertFalse(v.s().sleepTimerActive)
        assertEquals(0, v.s().sleepTimerValue)
    }
}

package com.wing.folderplayer.ui.player

import com.wing.folderplayer.playback.RetryPhase
import com.wing.folderplayer.playback.RetryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetryNoticeTest {
    private fun status(phase: RetryPhase, at: Long) = RetryStatus(2, 2_000, 0, phase, at)

    @Test fun theWaitCountsDownInWholeSecondsThenShowsTheAttempt() {
        val waiting = status(RetryPhase.WAITING, at = 10_000)
        assertEquals(RetryNotice.Waiting(8), waiting.notice(2_000))
        assertEquals(RetryNotice.Waiting(8), waiting.notice(2_001))
        assertEquals(RetryNotice.Waiting(1), waiting.notice(9_999))
        // The service's handler may run a little late: no "in 0 s".
        assertEquals(RetryNotice.Trying, waiting.notice(10_000))
        assertEquals(RetryNotice.Trying, status(RetryPhase.TRYING, 10_000).notice(60_000))
    }

    @Test fun reconnectedShowsForAMomentAndTheEndsShowNothing() {
        val recovered = status(RetryPhase.RECOVERED, at = 5_000)
        assertEquals(RetryNotice.Reconnected, recovered.notice(5_000 + RetryNotice.RECONNECTED_SHOWN_MS - 1))
        assertNull(recovered.notice(5_000 + RetryNotice.RECONNECTED_SHOWN_MS))
        for (phase in listOf(RetryPhase.NONE, RetryPhase.GAVE_UP, RetryPhase.CANCELLED)) assertNull(phase.name, status(phase, 5_000).notice(5_000))
    }
}

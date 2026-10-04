package com.wing.folderplayer.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
    @Test fun minutesAndHours() {
        assertEquals("0:00", DurationFormat.format(0))
        assertEquals("0:30", DurationFormat.format(30_000))
        assertEquals("0:30", DurationFormat.format(29_600)) // rounded to the nearest second
        assertEquals("1:00", DurationFormat.format(59_700))
        assertEquals("10:00", DurationFormat.format(600_000))
        assertEquals("59:59", DurationFormat.format(3_599_000))
        assertEquals("1:00:00", DurationFormat.format(3_600_000))
        assertEquals("2:03:04", DurationFormat.format(7_384_000))
    }
}

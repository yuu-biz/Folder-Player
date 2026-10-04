package com.wing.folderplayer.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistMoveTest {
    private val list = listOf("a", "b", "c", "d", "e")

    @Test fun movesDownAndUp() {
        assertEquals(listOf("a", "c", "d", "b", "e"), list.moved(1, 3))
        assertEquals(listOf("e", "a", "b", "c", "d"), list.moved(4, 0))
        assertEquals(listOf("b", "a", "c", "d", "e"), list.moved(0, 1))
    }

    @Test fun sameIndexKeepsTheListAndOutOfRangeIsRejected() {
        assertEquals(list, list.moved(2, 2))
        assertNull(list.moved(-1, 2))
        assertNull(list.moved(1, 5))
        assertNull(emptyList<String>().moved(0, 0))
    }

    @Test fun repeatedEntriesMoveByPosition() {
        assertEquals(listOf("x", "y", "x"), listOf("x", "x", "y").moved(1, 2))
    }
}

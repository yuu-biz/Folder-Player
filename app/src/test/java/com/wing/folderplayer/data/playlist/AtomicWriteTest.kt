package com.wing.folderplayer.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicWriteTest {
    @get:Rule val tmpDir = TemporaryFolder()

    @Test fun replacesTheFileAndLeavesNoTempFile() {
        val f = File(tmpDir.root, "metadata.json").apply { writeText("{\"default\":\"Default\"}") }
        writeTextAtomically(f, "{\"default\":\"Default\",\"list_1\":\"Mine\"}")
        assertEquals("{\"default\":\"Default\",\"list_1\":\"Mine\"}", f.readText())
        assertEquals(listOf("metadata.json"), tmpDir.root.list()!!.toList())
    }

    @Test fun createsAMissingFile() {
        val f = File(tmpDir.root, "new.fpl")
        writeTextAtomically(f, "x")
        assertEquals("x", f.readText())
    }

    @Test fun aFailedWriteLeavesTheOldContentUntouched() {
        val f = File(tmpDir.root, "metadata.json").apply { writeText("old") }
        // The temp file cannot be written (a directory is in its place): the target must not be cut off.
        assertTrue(File(tmpDir.root, "metadata.json.tmp").mkdir())
        val failed = runCatching { writeTextAtomically(f, "new") }.isFailure
        assertTrue(failed)
        assertEquals("old", f.readText())
        assertFalse(File(tmpDir.root, "metadata.json.tmp").isFile)
    }
}

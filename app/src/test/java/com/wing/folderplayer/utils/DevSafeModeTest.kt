package com.wing.folderplayer.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A folder named Init / init in Music resets settings only in debug builds; a release build ignores it. */
class DevSafeModeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun releaseBuildIgnoresTheFolder() {
        for (name in listOf("Init", "init")) {
            val music = tmp.newFolder("music-$name").apply { resolve(name).mkdir() }
            assertFalse("release, $name", DevSafeMode.requested(music, debugBuild = false))
            assertTrue("debug, $name", DevSafeMode.requested(music, debugBuild = true))
        }
    }

    @Test fun nothingWithoutTheFolder() {
        val music = tmp.newFolder("music").apply { resolve("Initial D").mkdir(); resolve("Init.txt").writeText("") }
        assertFalse(DevSafeMode.requested(music, debugBuild = true))
        assertFalse(DevSafeMode.requested(null, debugBuild = true))
    }
}

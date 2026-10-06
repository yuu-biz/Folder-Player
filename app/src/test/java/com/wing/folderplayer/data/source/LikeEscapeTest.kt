package com.wing.folderplayer.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

/** The folder prefix of the media-library query is a literal: LIKE wildcards and the escape character are escaped. */
class LikeEscapeTest {
    @Test fun wildcardsAndTheEscapeCharacterAreEscaped() {
        assertEquals("/storage/emulated/0/Music/plain/", likeEscape("/storage/emulated/0/Music/plain/"))
        assertEquals("/m/50\\%\\_off/", likeEscape("/m/50%_off/"))
        assertEquals("/m/a\\\\b/", likeEscape("/m/a\\b/"))
        assertEquals("\\%", likeEscape("%"))
        assertEquals("\\_\\_", likeEscape("__"))
    }

    /** A tiny LIKE with ESCAPE '\' (as SQLite does it, "%" = any run, "_" = one character) to check the pattern means what it says. */
    private fun like(text: String, pattern: String): Boolean {
        fun m(t: Int, p: Int): Boolean {
            if (p == pattern.length) return t == text.length
            val c = pattern[p]
            return when {
                c == '\\' && p + 1 < pattern.length -> t < text.length && text[t] == pattern[p + 1] && m(t + 1, p + 2)
                c == '%' -> (t..text.length).any { m(it, p + 1) }
                c == '_' -> t < text.length && m(t + 1, p + 1)
                else -> t < text.length && text[t].equals(c, ignoreCase = true) && m(t + 1, p + 1)
            }
        }
        return m(0, 0)
    }

    @Test fun anEscapedFolderMatchesItselfAndNoSibling() {
        for (name in listOf("p%c", "u_d", "%", "_", "a\\b", "100%_sure")) {
            val prefix = "/m/$name/"
            val direct = likeEscape(prefix) + "%"
            val nested = likeEscape(prefix) + "%/%"
            assertEquals("$name: own file", true, like(prefix + "w1.flac", direct) && !like(prefix + "w1.flac", nested))
            assertEquals("$name: nested file is excluded by the second pattern", true, like(prefix + "sub/w1.flac", nested))
        }
        for ((name, sibling) in listOf("p%c" to "pXc", "u_d" to "uXd", "%" to "plain", "_" to "x")) {
            assertEquals("$name does not match $sibling", false, like("/m/$sibling/w1.flac", likeEscape("/m/$name/") + "%"))
        }
    }
}

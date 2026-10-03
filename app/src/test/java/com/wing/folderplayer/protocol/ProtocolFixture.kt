package com.wing.folderplayer.protocol

import org.junit.Assume
import java.io.File

/**
 * Endpoints of the Docker fixture servers (scripts/fixtures/servers.sh). Tests are skipped — never passed — when
 * the properties are absent; only runs where they were set exercise the real protocols (see docs/fork/VERIFICATION.md).
 */
object ProtocolFixture {
    fun prop(name: String): String? = System.getProperty(name)?.takeIf { it.isNotBlank() }

    fun require(vararg names: String) {
        for (n in names) Assume.assumeTrue("fixture property $n not set", prop(n) != null)
    }

    val fixtureDir: File get() = File(prop("fp.fixture.dir") ?: "../build/fixture")

    fun local(path: String): File = File(fixtureDir, path.removePrefix("/"))

    const val TRICKY = "/fixture/Album-A/01 曲 #1+%.flac"
}

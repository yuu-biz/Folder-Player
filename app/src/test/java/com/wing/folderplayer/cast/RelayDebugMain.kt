package com.wing.folderplayer.cast

import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.testutil.InMemoryFileSystem

/** Manual diagnostic: serves one in-memory file for 40 s and prints its URL. */
object RelayDebugMain {
    @JvmStatic fun main(args: Array<String>) {
        val mem = InMemoryFileSystem("m").apply { put("/a.mp3", ByteArray(5000) { it.toByte() }) }
        val r = RelayServer({ mem }, 47651)
        r.start()
        println("URL=http://0.0.0.0:47651" + r.register(SourceRef("m", "/a.mp3")))
        Thread.sleep(40_000)
        r.stop()
    }
}

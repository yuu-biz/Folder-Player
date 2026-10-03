package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.cast.CastController
import com.wing.folderplayer.cast.CastSettings
import com.wing.folderplayer.cast.RelayServer
import com.wing.folderplayer.cast.Renderer
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A cast that fails half-way (renderer gone after the relay started) must release the relay, its tokens and the locks. */
@RunWith(AndroidJUnit4::class)
class CastCleanupTest {
    private fun <T> field(o: Any, name: String): T {
        val f = o.javaClass.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") return f.get(o) as T
    }

    @Test fun failedCastReleasesRelayTokensAndLocks() {
        SourceRegistry.init(Fx.ctx)
        CastSettings(Fx.ctx).enabled = true
        val cast = CastController.get(Fx.ctx)
        val relay: RelayServer = field(cast, "relay")
        val locks: Any = field(cast, "locks")
        // A renderer that is not (or no longer) known to the control point: SetAVTransportURI fails after the relay
        // was started and the track registered.
        val gone = Renderer(udn = "uuid:not-there", name = "gone", model = "", address = "127.0.0.1")
        cast.cast(gone, SourceRef(SourceRegistry.LOCAL_INTERNAL_ID, "/Music/fixture/Album-B/track.flac"), "track", null)
        val end = System.currentTimeMillis() + 15_000
        while (cast.state.value.error == null && System.currentTimeMillis() < end) Thread.sleep(100)
        Thread.sleep(500)
        Fx.log("after failed cast: error=${cast.state.value.error} relayRunning=${relay.isRunning} " +
            "tokens=${field<Map<*, *>>(relay, "tokens").size} wifiLock=${field<Any?>(locks, "wifiLock")} wakeLock=${field<Any?>(locks, "wakeLock")}")
        assertTrue("the failure is reported", cast.state.value.error != null)
        assertNull("no active session", cast.state.value.active)
        assertFalse("relay stopped", relay.isRunning)
        assertEquals("tokens revoked", 0, field<Map<*, *>>(relay, "tokens").size)
        assertNull("Wi-Fi lock released", field<Any?>(locks, "wifiLock"))
        assertNull("wake lock released", field<Any?>(locks, "wakeLock"))
        CastSettings(Fx.ctx).enabled = false
    }
}

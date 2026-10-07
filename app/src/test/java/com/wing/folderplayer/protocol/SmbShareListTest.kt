package com.wing.folderplayer.protocol

import com.wing.folderplayer.data.source.SmbShareFailure
import com.wing.folderplayer.data.source.SmbShareLister
import com.wing.folderplayer.data.source.SmbShareResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The share list of the Samba fixture (shares music, home, alice, public) over real SMB, as the SMB editor asks for it. */
class SmbShareListTest {
    private lateinit var host: String
    private var port = 445

    @Before fun setUp() {
        ProtocolFixture.require("fp.smb.host")
        host = ProtocolFixture.prop("fp.smb.host")!!
        port = ProtocolFixture.prop("fp.smb.port")?.toInt() ?: 445
    }

    private fun names(r: SmbShareResult): List<String> = (r as? SmbShareResult.Ok ?: error("not listed: $r")).shares.map { it.name }

    @Test fun aLoggedInUserGetsTheDiskSharesWithoutTheAdministrativeOnes() {
        val alice = names(SmbShareLister.list(host, port, "alice", "alicepass", "", anonymous = false))
        println("alice sees: $alice")
        assertTrue(alice.toString(), alice.containsAll(listOf("music", "home", "alice", "public")))
        assertFalse(alice.any { it.endsWith("$") })
        val bob = names(SmbShareLister.list(host, port, "bob", "bobpass", "", anonymous = false))
        println("bob sees: $bob")
        assertTrue(bob.toString(), bob.containsAll(listOf("music", "home", "public")))
    }

    @Test fun aWrongPasswordIsAnAuthenticationFailureNotAnEmptyList() {
        val r = SmbShareLister.list(host, port, "alice", "wrong", "", anonymous = false)
        assertEquals(r.toString(), SmbShareFailure.AUTH_FAILED, (r as SmbShareResult.Failed).reason)
    }

    @Test fun anUnreachableServerIsReportedAsSuch() {
        val r = SmbShareLister.list(host, 1, "alice", "alicepass", "", anonymous = false)
        assertEquals(r.toString(), SmbShareFailure.UNREACHABLE, (r as SmbShareResult.Failed).reason)
    }

    @Test fun theGuestAttemptAfterASearchAnswersOrFailsCleanly() {
        val r = SmbShareLister.listAsGuest(host, port)
        println("guest: $r")
        // The fixture maps unknown users to guest ("map to guest = Bad User") and has a guest-ok share.
        if (r is SmbShareResult.Ok) assertTrue(names(r).toString(), "public" in names(r)) else assertTrue(r is SmbShareResult.Failed)
    }
}

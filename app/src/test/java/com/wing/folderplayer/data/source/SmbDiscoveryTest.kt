package com.wing.folderplayer.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** The logic of the SMB search that needs no network: which addresses may be probed, NetBIOS packets, merging. */
class SmbDiscoveryTest {
    private fun ip(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    @Test fun onlyPrivateAddressesAreEverProbed() {
        assertTrue(SmbDiscoveryLogic.isPrivate(ip(192, 168, 0, 5)))
        assertTrue(SmbDiscoveryLogic.isPrivate(ip(10, 1, 2, 3)))
        assertTrue(SmbDiscoveryLogic.isPrivate(ip(172, 16, 0, 1)) && SmbDiscoveryLogic.isPrivate(ip(172, 31, 255, 1)))
        for (a in listOf(ip(172, 15, 0, 1), ip(172, 32, 0, 1), ip(8, 8, 8, 8), ip(100, 64, 0, 1), ip(169, 254, 1, 1), ip(203, 0, 113, 7))) {
            assertFalse(a.joinToString("."), SmbDiscoveryLogic.isPrivate(a))
            assertTrue(SmbDiscoveryLogic.candidates(a, 24).isEmpty())
        }
    }

    @Test fun candidatesAreTheSlash24OfTheDeviceWithoutItself() {
        val c = SmbDiscoveryLogic.candidates(ip(192, 168, 1, 20), 24)
        assertEquals(253, c.size)
        assertEquals("192.168.1.1", c.first()); assertEquals("192.168.1.254", c.last())
        assertFalse("192.168.1.20" in c || "192.168.1.0" in c || "192.168.1.255" in c)
        // A wider network is still scanned as the /24 around the device only.
        assertEquals(c, SmbDiscoveryLogic.candidates(ip(192, 168, 1, 20), 16))
        assertEquals(253, SmbDiscoveryLogic.candidates(ip(10, 20, 30, 40), 8).size)
        // A narrower one is scanned as it is: /26 holds 62 hosts, one of them this device.
        val n = SmbDiscoveryLogic.candidates(ip(192, 168, 1, 70), 26)
        assertEquals(61, n.size)
        assertEquals("192.168.1.65", n.first()); assertEquals("192.168.1.126", n.last())
    }

    @Test fun netBiosRequestIsAStandardNodeStatusQuery() {
        val r = SmbDiscoveryLogic.netBiosRequest(0x1234)
        assertEquals(50, r.size)
        assertEquals(listOf(0x12, 0x34, 0, 0, 0, 1), r.take(6).map { it.toInt() and 0xff })
        assertEquals(0x20, r[12].toInt()); assertEquals("CK" + "A".repeat(30), String(r, 13, 32))
        assertEquals(listOf(0, 0x00, 0x21, 0x00, 0x01), r.takeLast(5).map { it.toInt() and 0xff })
    }

    private fun entry(name: String, suffix: Int, flags: Int): ByteArray =
        name.padEnd(15, ' ').toByteArray(Charsets.ISO_8859_1) + byteArrayOf(suffix.toByte(), (flags ushr 8).toByte(), flags.toByte())

    private fun response(vararg entries: ByteArray): ByteArray {
        val header = byteArrayOf(0x12, 0x34, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0)
        val name = byteArrayOf(0xc0.toByte(), 0x0c, 0, 0x21, 0, 1, 0, 0, 0, 0)
        val rdata = byteArrayOf(entries.size.toByte()) + entries.fold(ByteArray(0)) { a, e -> a + e } + ByteArray(6)
        return header + name + byteArrayOf(0, rdata.size.toByte()) + rdata
    }

    @Test fun netBiosNameIsTheUniqueComputerName() {
        val r = response(entry("WORKGROUP", 0x00, 0x8400), entry("DISKSTATION", 0x00, 0x0400), entry("DISKSTATION", 0x20, 0x0400))
        assertEquals("DISKSTATION", SmbDiscoveryLogic.parseNetBiosName(r))
        // Only the file server name: that one is used. Group names (flag bit 15) never are.
        assertEquals("NAS", SmbDiscoveryLogic.parseNetBiosName(response(entry("WORKGROUP", 0x00, 0x8400), entry("NAS", 0x20, 0x0400))))
        assertNull(SmbDiscoveryLogic.parseNetBiosName(response(entry("WORKGROUP", 0x00, 0x8400))))
    }

    @Test fun malformedNetBiosAnswersGiveNoNameAndNoCrash() {
        assertNull(SmbDiscoveryLogic.parseNetBiosName(ByteArray(0)))
        assertNull(SmbDiscoveryLogic.parseNetBiosName(ByteArray(11)))
        val ok = response(entry("NAS", 0x00, 0x0400))
        for (cut in ok.indices) SmbDiscoveryLogic.parseNetBiosName(ok, cut) // any truncation: null or a name, never an exception
        assertNull(SmbDiscoveryLogic.parseNetBiosName(ok, 20))
    }

    @Test fun mergeKeepsOneEntryPerAddressWithTheBestName() {
        val merged = SmbDiscoveryLogic.merge(listOf(
            SmbHost("192.168.1.5"),
            SmbHost("192.168.1.5", 445, "BOX", SmbNameSource.NETBIOS),
            SmbHost("192.168.1.5", 4445, "Synology", SmbNameSource.MDNS),
            SmbHost("192.168.1.5", 445, "BOX", SmbNameSource.NETBIOS),
            SmbHost("192.168.1.9"),
            SmbHost("192.168.1.2", 445, "alpha", SmbNameSource.NETBIOS),
        ))
        assertEquals(listOf("192.168.1.2", "192.168.1.5", "192.168.1.9"), merged.map { it.address })
        assertEquals("Synology", merged[1].name); assertEquals(4445, merged[1].port)
        assertEquals("unnamed hosts come last, by address", "192.168.1.9", merged.last().address)
        assertEquals("192.168.1.9", merged.last().displayName)
    }

    @Test fun probeSeesAnOpenPortAndNotAClosedOne() {
        val open = ServerSocket(0)
        val port = open.localPort
        try { assertTrue(SmbProbe.isOpen("127.0.0.1", port, 500)) } finally { open.close() }
        assertFalse(SmbProbe.isOpen("127.0.0.1", port, 500))
    }
}

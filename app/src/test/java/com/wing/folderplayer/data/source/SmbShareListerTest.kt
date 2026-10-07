package com.wing.folderplayer.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the shares a server reports a user would pick (the server's own answer is checked against Samba in SmbShareListTest). */
class SmbShareListerTest {
    private val disk = 0
    private val printer = 1
    private val ipc = 3
    private val special = 0x80000000.toInt()
    private val temporary = 0x40000000

    @Test fun onlyPlainDiskSharesAreOfferedSortedByName() {
        val all = listOf(
            Triple("music", disk, "Main library "),
            Triple("IPC$", ipc or special, "Remote IPC"),
            Triple("ADMIN$", disk or special, "Remote Admin"),
            Triple("C$", disk or special, "Default share"),
            Triple("Photos", disk, ""),
            Triple("laser", printer, "Printer"),
            Triple("tmp", disk or temporary, ""),
            Triple("hidden$", disk, ""),
            Triple("", disk, ""),
            Triple("PHOTOS", disk, "duplicate by case"),
            Triple("backup", disk, "Backup"),
        )
        val shares = SmbShareLister.visible(all)
        assertEquals(listOf("backup", "music", "Photos"), shares.map { it.name })
        assertEquals("remarks are trimmed", "Main library", shares.single { it.name == "music" }.remark)
    }

    @Test fun failuresAreToldApart() {
        val denied = SmbShareLister.failure(SourceException.PermissionDenied("list shares: STATUS_ACCESS_DENIED"))
        assertEquals(SmbShareFailure.NOT_ALLOWED, denied.reason)
        assertEquals(SmbShareFailure.AUTH_FAILED, SmbShareLister.failure(SourceException.AuthFailed("bad password")).reason)
        assertEquals(SmbShareFailure.UNREACHABLE, SmbShareLister.failure(java.net.ConnectException("refused")).reason)
        val other = SmbShareLister.failure(IllegalStateException("boom"))
        assertEquals(SmbShareFailure.ERROR, other.reason)
        assertTrue(other.detail.contains("boom"))
    }
}

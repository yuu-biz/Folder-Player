package com.wing.folderplayer.data

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Auto Backup stops for good once an app's data passes 25 MB. The fonts a user downloads (14-24 MB each, imports up to
 * 50 MB) are re-creatable and must stay out, or favourites, playlists and the source list are no longer backed up.
 */
class BackupRulesTest {
    private val xmlDir: File = listOf(File("src/main/res/xml"), File("app/src/main/res/xml")).first { it.isDirectory }

    private fun excludes(file: String, section: String?): Set<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xmlDir, file))
        val root = if (section == null) doc.documentElement
        else doc.getElementsByTagName(section).item(0) as Element
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .map { it.getAttribute("domain") to it.getAttribute("path") }.toSet()
    }

    @Test
    fun cloudBackupSkipsFontsAndSecrets() {
        val ex = excludes("data_extraction_rules.xml", "cloud-backup")
        assertTrue(ex.toString(), "file" to "fonts/" in ex)
        assertTrue(ex.toString(), "sharedpref" to "fp_credentials.xml" in ex)
        assertTrue(ex.toString(), "file" to "migration-backup/" in ex)
    }

    @Test
    fun deviceTransferSkipsSecrets() {
        val ex = excludes("data_extraction_rules.xml", "device-transfer")
        assertTrue(ex.toString(), "sharedpref" to "fp_credentials.xml" in ex)
    }

    @Test
    fun legacyFullBackupSkipsSecrets() {
        val ex = excludes("backup_rules.xml", null)
        assertTrue(ex.toString(), "sharedpref" to "fp_credentials.xml" in ex)
    }
}

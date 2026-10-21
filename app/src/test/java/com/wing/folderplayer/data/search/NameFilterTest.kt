package com.wing.folderplayer.data.search

import com.wing.folderplayer.data.source.MusicFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The browser's filter: names of the folder on screen only, every word, case / width / kana insensitive. */
class NameFilterTest {
    private fun dir(name: String) = MusicFile(name, "/Music/$name", true, 0, 0, "s")
    private fun song(name: String) = MusicFile(name, "/Music/$name", false, 0, 0, "s")

    private val files = listOf(
        dir("Bill Evans - Waltz for Debby"),
        dir("Bill Evans - Sunday at the Village Vanguard"),
        dir("John Coltrane - Blue Train"),
        dir("ヨルシカ - だから僕は音楽を辞めた"),
        dir("ＹＯＡＳＯＢＩ - THE BOOK"),
        song("01 Waltz.flac"),
    )

    private fun names(filter: String) = NameFilter.apply(files, filter).map { it.name }

    @Test fun emptyOrBlankFilterKeepsTheList() {
        assertSame(files, NameFilter.apply(files, ""))
        assertSame(files, NameFilter.apply(files, "  　 "))
    }

    @Test fun caseInsensitiveSubstring() {
        assertEquals(listOf("John Coltrane - Blue Train"), names("coltrane"))
        assertEquals(listOf("Bill Evans - Waltz for Debby", "01 Waltz.flac"), names("WALTZ"))
    }

    @Test fun everyWordMustMatchInAnyOrder() {
        assertEquals(listOf("Bill Evans - Sunday at the Village Vanguard"), names("village evans"))
        assertEquals(emptyList<String>(), names("evans train"))
        // An ideographic space separates words too.
        assertEquals(listOf("Bill Evans - Sunday at the Village Vanguard"), names("evans　sunday"))
    }

    @Test fun widthAndKanaDoNotMatter() {
        assertEquals(listOf("ＹＯＡＳＯＢＩ - THE BOOK"), names("yoasobi"))
        assertEquals(listOf("ヨルシカ - だから僕は音楽を辞めた"), names("よるしか"))
        assertEquals(listOf("ヨルシカ - だから僕は音楽を辞めた"), names("ﾖﾙｼｶ"))
        assertEquals(listOf("ヨルシカ - だから僕は音楽を辞めた"), names("ダカラ"))
    }

    @Test fun keepsTheOrderOfTheList() {
        assertEquals(listOf("Bill Evans - Waltz for Debby", "Bill Evans - Sunday at the Village Vanguard"), names("bill"))
    }
}

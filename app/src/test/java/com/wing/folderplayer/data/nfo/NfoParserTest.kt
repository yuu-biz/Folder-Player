package com.wing.folderplayer.data.nfo

import com.wing.folderplayer.data.source.MusicFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** XML and text NFO, candidate order, XXE rejection, safe serialization. */
class NfoParserTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<album>
  <title>Blue &amp; Green</title>
  <artist>Some Artist</artist>
  <year>1999</year>
  <genre>Jazz</genre><genre>Fusion</genre>
  <label>Label X</label>
  <review>Great record.</review>
  <musicbrainzalbumid>abc-123</musicbrainzalbumid>
  <track><position>1</position><title>Intro</title><duration>1:02</duration></track>
  <track><position>2</position><title>Main Theme</title><duration>5:40</duration></track>
</album>"""

    @Test fun parsesXmlAlbum() {
        val nfo = NfoParser.parse(xml.toByteArray(), "Info.nfo")
        assertEquals(NfoInfo.Format.XML, nfo.format)
        assertEquals("Blue & Green", nfo.title)
        assertEquals("Some Artist", nfo.fields["Artist"])
        assertEquals("1999", nfo.fields["Year"])
        assertEquals("Jazz / Fusion", nfo.fields["Genre"])
        assertEquals("Label X", nfo.fields["Label"])
        assertEquals("Great record.", nfo.description)
        assertEquals(listOf(NfoTrack(1, "Intro", "1:02"), NfoTrack(2, "Main Theme", "5:40")), nfo.tracks)
        assertEquals(listOf("musicbrainzalbumid" to "abc-123"), nfo.extra)
    }

    @Test fun parsesGbkEncodedXmlByContentNotDeclaration() {
        val gbk = """<?xml version="1.0" encoding="GBK"?><album><title>专辑</title></album>""".toByteArray(charset("GB18030"))
        assertEquals("专辑", NfoParser.parse(gbk, "info.nfo").title)
    }

    @Test fun rejectsDoctypeAndExternalEntities() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE album [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><album><title>&xxe;</title></album>"""
        try {
            NfoParser.parse(xxe.toByteArray(), "Info.nfo")
            fail("DOCTYPE accepted")
        } catch (e: NfoRejectedException) {
        }
        val billion = """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;">]><album><title>&lol2;</title></album>"""
        try {
            NfoParser.parse(billion.toByteArray(), "Info.nfo")
            fail("entity expansion accepted")
        } catch (e: NfoRejectedException) {
        }
    }

    @Test fun parsesSceneStyleTextNfo() {
        val text = """
            Artist.......: Some Artist
            Album........: Night Drive
            Genre........: Electronic
            Year.........: 2020
            ------------------------------
            01. Opening [3:21]
            02. Highway (4:05)
            03. Last Exit
            ------------------------------
            Recorded live in 2019.
        """.trimIndent()
        val nfo = NfoParser.parse(text.toByteArray(), "album.nfo")
        assertEquals(NfoInfo.Format.TEXT, nfo.format)
        assertEquals("Night Drive", nfo.title)
        assertEquals("Some Artist", nfo.fields["Artist"])
        assertEquals("Electronic", nfo.fields["Genre"])
        assertEquals(NfoTrack(1, "Opening", "3:21"), nfo.tracks[0])
        assertEquals(NfoTrack(2, "Highway", "4:05"), nfo.tracks[1])
        assertEquals(NfoTrack(3, "Last Exit", null), nfo.tracks[2])
        assertEquals(3, nfo.tracks.size)
        assertEquals("Recorded live in 2019.", nfo.description)
    }

    @Test fun candidateOrderIsFixedAndCaseInsensitive() {
        fun f(n: String) = MusicFile(n, "/A/$n", false, 1, 0, "s")
        val names = NfoParser.candidates(listOf(f("zeta.nfo"), f("ALBUM.NFO"), f("Info.nfo"), f("folder.nfo"), f("cover.jpg"), f("alpha.nfo"))).map { it.name }
        assertEquals(listOf("Info.nfo", "folder.nfo", "ALBUM.NFO", "alpha.nfo", "zeta.nfo"), names)
    }

    @Test fun serializationEscapesAndRoundTrips() {
        val keep = NfoParser.parse(xml.toByteArray(), "Info.nfo")
        val out = NfoParser.toXml("T <&> \"x\"", null, "Review with <tags> & stuff", "Bio", keep)
        assertTrue(out.contains("T &lt;&amp;&gt; &quot;x&quot;"))
        val back = NfoParser.parse(out.toByteArray(), "Info.nfo")
        assertEquals("T <&> \"x\"", back.title)
        assertEquals("Some Artist", back.artist)
        assertEquals("Review with <tags> & stuff", back.description)
        assertEquals("Bio", back.fields["Artist Bio"])
        assertEquals(2, back.tracks.size)
        assertEquals("abc-123", back.extra.toMap()["musicbrainzalbumid"])
    }

    @Test fun emptyTextGivesEmptyInfo() {
        val nfo = NfoParser.parse(ByteArray(0), "x.nfo")
        assertNull(nfo.title)
        assertTrue(nfo.tracks.isEmpty())
    }
}

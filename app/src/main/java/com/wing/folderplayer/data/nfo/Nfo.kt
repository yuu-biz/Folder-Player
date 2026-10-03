package com.wing.folderplayer.data.nfo

import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SafeXml
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.TextDecoding
import org.w3c.dom.Element
import org.w3c.dom.Node

data class NfoTrack(val position: Int?, val title: String, val duration: String?)

/**
 * Album information from an NFO file. [fields] keeps display order ("Artist", "Year", …). Elements this app does not
 * interpret are preserved in [extra] (XML) so a rewrite does not lose them.
 */
data class NfoInfo(
    val fileName: String,
    val title: String?,
    val fields: LinkedHashMap<String, String>,
    val description: String?,
    val tracks: List<NfoTrack>,
    val format: Format,
    val extra: List<Pair<String, String>> = emptyList(),
) {
    enum class Format { XML, TEXT }

    val artist: String? get() = fields["Artist"]
}

class NfoRejectedException(msg: String) : Exception(msg)

/** Parsing and writing of album NFO files: Kodi-style XML `<album>` and the scene-style text layout. */
object NfoParser {
    /** Candidate order; names compare case-insensitively. Other *.nfo files follow, sorted by name. */
    val PREFERRED = listOf("info.nfo", "folder.nfo", "album.nfo")

    fun candidates(entries: List<MusicFile>): List<MusicFile> {
        val nfos = entries.filter { !it.isDirectory && SourcePath.extension(it.name) == "nfo" }
        val preferred = nfos.filter { it.name.lowercase() in PREFERRED }
            .sortedWith(compareBy<MusicFile>({ PREFERRED.indexOf(it.name.lowercase()) }, { it.name }))
        return preferred + (nfos - preferred.toSet()).sortedBy { it.name.lowercase() }
    }

    fun parse(bytes: ByteArray, fileName: String): NfoInfo {
        val text = TextDecoding.decode(bytes)
        val head = text.trimStart().take(512)
        return if (head.startsWith("<?xml", true) || head.startsWith("<album", true)) parseXml(text, fileName)
        else parseText(text, fileName)
    }

    fun parseXml(text: String, fileName: String): NfoInfo {
        if (SafeXml.hasDoctype(text)) throw NfoRejectedException("DOCTYPE / entity declarations are not allowed in NFO files")
        val doc = SafeXml.parseText(text)
        val root = doc.documentElement
        root.normalize()
        fun first(name: String): String? = childText(root, name)
        fun all(name: String): List<String> = children(root, name).mapNotNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) }
        val fields = LinkedHashMap<String, String>()
        first("artist")?.let { fields["Artist"] = it }
        first("year")?.let { fields["Year"] = it }
        first("releasedate")?.let { fields["Release Date"] = it }
        all("genre").takeIf { it.isNotEmpty() }?.let { fields["Genre"] = it.joinToString(" / ") }
        all("style").takeIf { it.isNotEmpty() }?.let { fields["Style"] = it.joinToString(" / ") }
        all("tag").takeIf { it.isNotEmpty() }?.let { fields["Tags"] = it.joinToString(" / ") }
        first("label")?.let { fields["Label"] = it }
        first("website")?.let { fields["Website"] = it }
        first("artistbio")?.let { fields["Artist Bio"] = it }
        val tracks = children(root, "track").map { t ->
            NfoTrack(childText(t, "position")?.toIntOrNull(), childText(t, "title") ?: "", childText(t, "duration"))
        }
        val known = setOf("title", "sorttitle", "artist", "year", "releasedate", "genre", "style", "tag", "label", "website", "track", "review", "plot", "outline", "artistbio")
        val extra = (0 until root.childNodes.length).map { root.childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName !in known }
            .map { it.tagName to (it.textContent?.trim() ?: "") }
        return NfoInfo(
            fileName = fileName,
            title = first("title") ?: first("sorttitle"),
            fields = fields,
            description = first("review") ?: first("plot") ?: first("outline"),
            tracks = tracks,
            format = NfoInfo.Format.XML,
            extra = extra,
        )
    }

    private fun children(e: Element, name: String): List<Element> =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE && (it as Element).tagName.equals(name, true) }
            .map { it as Element }

    private fun childText(e: Element, name: String): String? =
        children(e, name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private val FIELD = Regex("^([^.:]{1,40}?)\\.{2,}\\s*:\\s*(.*)$")
    private val FIELD_SIMPLE = Regex("^([A-Za-z][A-Za-z ]{1,30}?)\\s*:\\s*(.+)$")
    private val TRACK = Regex("^\\s*(\\d{1,3})\\.\\s*(.+?)\\s*(?:\\[(\\d{1,2}:\\d{2}(?::\\d{2})?)])?\\s*$")
    private val TRACK_PAREN = Regex("^(.*?)\\s*[(\\[](\\d{1,2}:\\d{2}(?::\\d{2})?)[)\\]]\\s*$")
    private val SEPARATOR = Regex("^-{5,}$")

    fun parseText(text: String, fileName: String): NfoInfo {
        val fields = LinkedHashMap<String, String>()
        val tracks = ArrayList<NfoTrack>()
        val description = ArrayList<String>()
        var title: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || SEPARATOR.matches(line)) continue
            FIELD.matchEntire(line)?.let { m ->
                val key = normalizeKey(m.groupValues[1].trim())
                val value = m.groupValues[2].trim()
                if (value.isNotEmpty()) {
                    if (key.equals("Title", true) || key.equals("Album", true)) title = title ?: value else fields[key] = value
                }
                return@let
            } ?: TRACK.matchEntire(line)?.let { m ->
                var title = m.groupValues[2].trim()
                var duration = m.groupValues[3].ifEmpty { null }
                if (duration == null) TRACK_PAREN.matchEntire(title)?.takeIf { it.groupValues[1].isNotBlank() }?.let { p ->
                    title = p.groupValues[1].trim(); duration = p.groupValues[2]
                }
                tracks.add(NfoTrack(m.groupValues[1].toIntOrNull(), title, duration))
            } ?: TRACK_PAREN.matchEntire(line)?.takeIf { it.groupValues[1].isNotBlank() }?.let { m ->
                tracks.add(NfoTrack(null, m.groupValues[1].trim(), m.groupValues[2]))
            } ?: FIELD_SIMPLE.matchEntire(line)?.takeIf { tracks.isEmpty() && description.isEmpty() }?.let { m ->
                val key = normalizeKey(m.groupValues[1].trim())
                if (key.equals("Title", true) || key.equals("Album", true)) title = title ?: m.groupValues[2].trim()
                else fields[key] = m.groupValues[2].trim()
            } ?: description.add(line)
        }
        return NfoInfo(fileName, title, fields, description.joinToString("\n").ifBlank { null }, tracks, NfoInfo.Format.TEXT)
    }

    private fun normalizeKey(k: String): String = k.split(' ').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

    /** Serializes album info as Kodi-style XML. Text is escaped; nothing from the content is executed. */
    fun toXml(title: String?, artist: String?, review: String?, artistBio: String?, keep: NfoInfo? = null): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<album>\n")
        fun el(name: String, v: String?) { if (!v.isNullOrBlank()) sb.append("  <").append(name).append('>').append(escape(v)).append("</").append(name).append(">\n") }
        el("title", title ?: keep?.title)
        el("artist", artist ?: keep?.artist)
        keep?.fields?.forEach { (k, v) ->
            when (k) {
                "Year" -> el("year", v)
                "Release Date" -> el("releasedate", v)
                "Genre" -> v.split(" / ").forEach { el("genre", it) }
                "Label" -> el("label", v)
                "Website" -> el("website", v)
                "Tags" -> v.split(" / ").forEach { el("tag", it) }
            }
        }
        el("review", review ?: keep?.description)
        el("artistbio", artistBio ?: keep?.fields?.get("Artist Bio"))
        keep?.tracks?.forEach { t ->
            sb.append("  <track>\n")
            t.position?.let { sb.append("    <position>").append(it).append("</position>\n") }
            sb.append("    <title>").append(escape(t.title)).append("</title>\n")
            t.duration?.let { sb.append("    <duration>").append(escape(it)).append("</duration>\n") }
            sb.append("  </track>\n")
        }
        keep?.extra?.forEach { (k, v) -> if (k.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*"))) el(k, v) }
        sb.append("</album>\n")
        return sb.toString()
    }

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (c.code < 0x20 && c != '\n' && c != '\t' && c != '\r') Unit else append(c)
        }
    }
}

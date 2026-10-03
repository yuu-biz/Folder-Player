package com.wing.folderplayer.i18n

import com.wing.folderplayer.utils.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every translation (values-<locale>/strings.xml) has exactly the strings and plurals of the English base, with the
 * same format placeholders. New locales are picked up from the res directory, and a string added only to English
 * fails here for every locale until it is translated (see scripts/i18n/gen_translations.py).
 */
class StringResourcesParityTest {
    private class Res(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private val resDir: File = listOf(File("src/main/res"), File("app/src/main/res")).first { File(it, "values/strings.xml").isFile }
    private val base = parse(File(resDir, "values/strings.xml"))
    private val translations: Map<String, Res> = resDir.listFiles()!!
        .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").isFile }
        .associate { it.name.removePrefix("values-") to parse(File(it, "strings.xml")) }
        .toSortedMap()

    private fun parse(f: File): Res {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = e.textContent
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[e.getAttribute("name")] = (0 until items.length).map { items.item(it) as Element }
                        .associate { it.getAttribute("quantity") to it.textContent }
                }
            }
        }
        return Res(strings, plurals)
    }

    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")
    private fun placeholders(s: String) = placeholder.findAll(s).map { it.value }.sorted().toList()

    /** Everything that differs from the base; empty when the translation is complete. */
    private fun problems(base: Res, t: Res): List<String> {
        val p = mutableListOf<String>()
        (base.strings.keys - t.strings.keys).forEach { p += "missing string $it" }
        (t.strings.keys - base.strings.keys).forEach { p += "unknown string $it" }
        (base.plurals.keys - t.plurals.keys).forEach { p += "missing plurals $it" }
        (t.plurals.keys - base.plurals.keys).forEach { p += "unknown plurals $it" }
        for ((k, v) in t.strings) {
            val en = base.strings[k] ?: continue
            if (placeholders(v) != placeholders(en)) p += "placeholders of $k: ${placeholders(v)} vs ${placeholders(en)}"
            if (v.isBlank()) p += "empty string $k"
        }
        for ((k, items) in t.plurals) {
            val en = base.plurals[k] ?: continue
            if ("other" !in items) p += "plurals $k has no 'other'"
            (items.keys - setOf("zero", "one", "two", "few", "many", "other")).forEach { p += "plurals $k: bad quantity $it" }
            for ((q, v) in items) if (placeholders(v) != placeholders(en.getValue("other"))) p += "placeholders of $k/$q"
        }
        return p
    }

    @Test fun everyTranslationHasTheBaseStringsWithTheSamePlaceholders() {
        assertTrue("translations found: ${translations.keys}", translations.keys.containsAll(listOf("fr", "it", "ja", "zh-rCN", "zh-rTW")))
        assertTrue("base has strings", base.strings.size > 200)
        val all = translations.mapValues { (_, t) -> problems(base, t) }.filterValues { it.isNotEmpty() }
        assertEquals("translations differing from values/strings.xml", emptyMap<String, List<String>>(), all)
    }

    @Test fun aStringAddedOnlyToEnglishIsReportedAsMissing() {
        val ja = translations.getValue("ja")
        val grown = Res(base.strings + ("new_feature_title" to "New feature %1\$s"), base.plurals + ("new_count" to mapOf("one" to "%d x", "other" to "%d xs")))
        assertEquals(listOf("missing string new_feature_title", "missing plurals new_count"), problems(grown, ja))
        val changed = Res(base.strings + ("settings_title" to "Settings for %1\$s"), base.plurals)
        assertEquals(listOf("placeholders of settings_title: [] vs [%1\$s]"), problems(changed, ja))
    }

    @Test fun japaneseIsActuallyTranslated() {
        val ja = translations.getValue("ja")
        assertEquals(base.strings.keys, ja.strings.keys)
        assertEquals(base.plurals.keys, ja.plurals.keys)
        // Japanese has a single plural form.
        ja.plurals.forEach { (k, items) -> assertEquals("quantities of $k", setOf("other"), items.keys) }
        // Strings that are the same in every language (product name, "OK").
        val sameAsEnglish = setOf("common_ok", "settings_about_version")
        val kana = Regex("[\\u3040-\\u30FF\\u4E00-\\u9FFF]")
        val untranslated = ja.strings.filter { (k, v) -> k !in sameAsEnglish && !kana.containsMatchIn(v) }.keys
        assertEquals("ja strings without Japanese text", emptySet<String>(), untranslated)
        val notJa = ja.plurals.filterValues { items -> !kana.containsMatchIn(items.getValue("other")) }.keys
        assertEquals("ja plurals without Japanese text", emptySet<String>(), notJa)
        assertEquals("設定", ja.strings["settings_title"])
    }

    @Test fun languagePickerLocaleConfigAndResourcesAgree() {
        fun tag(dir: String) = dir.replace("-r", "-")
        val resourceTags = translations.keys.map(::tag).toSet() + "en"
        assertEquals("AppLocale.TAGS vs values-* directories", resourceTags, AppLocale.TAGS.filter { it.isNotEmpty() }.toSet())
        assertEquals("日本語", AppLocale.LANGUAGES.toMap()["ja"])
        assertEquals("system default first", "", AppLocale.TAGS.first())
        val config = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resDir, "xml/locales_config.xml"))
        val locales = config.getElementsByTagName("locale")
        val configTags = (0 until locales.length).map { (locales.item(it) as Element).getAttribute("android:name") }.toSet()
        assertEquals("locales_config.xml vs values-* directories", resourceTags, configTags)
    }
}

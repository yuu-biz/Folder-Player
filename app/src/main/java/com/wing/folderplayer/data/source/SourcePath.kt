package com.wing.folderplayer.data.source

/**
 * Source-relative paths. A path always starts with "/" (the source root), uses "/" separators, never ends with "/"
 * except for the root, and never escapes the root. Names are kept verbatim (no URL decoding happens here).
 */
object SourcePath {
    const val ROOT = "/"

    class EscapesRootException(path: String) : IllegalArgumentException("path escapes source root: $path")

    fun normalize(raw: String): String {
        val parts = ArrayDeque<String>()
        for (seg in raw.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> {
                    if (parts.isEmpty()) throw EscapesRootException(raw)
                    parts.removeLast()
                }
                else -> parts.addLast(seg)
            }
        }
        return if (parts.isEmpty()) ROOT else parts.joinToString("/", prefix = "/")
    }

    fun child(parent: String, name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')) {
            "invalid entry name: $name"
        }
        val p = normalize(parent)
        return if (p == ROOT) "/$name" else "$p/$name"
    }

    fun parent(path: String): String? {
        val p = normalize(path)
        if (p == ROOT) return null
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) ROOT else p.substring(0, idx)
    }

    fun name(path: String): String {
        val p = normalize(path)
        return if (p == ROOT) "" else p.substring(p.lastIndexOf('/') + 1)
    }

    fun segments(path: String): List<String> = normalize(path).split('/').filter { it.isNotEmpty() }

    /** Joins a configured root (inside a share/server) with a source-relative path. */
    fun join(root: String, relative: String): String {
        val r = normalize(root)
        val rel = normalize(relative)
        return when {
            r == ROOT -> rel
            rel == ROOT -> r
            else -> r + rel
        }
    }

    /** Inverse of [join]: returns the source-relative path of [absolute] under [root], or null if outside it. */
    fun relativize(root: String, absolute: String): String? {
        val r = normalize(root)
        val a = normalize(absolute)
        if (r == ROOT) return a
        if (a == r) return ROOT
        return if (a.startsWith("$r/")) a.substring(r.length) else null
    }

    fun isAncestorOrSelf(ancestor: String, path: String): Boolean {
        val a = normalize(ancestor)
        val p = normalize(path)
        return a == ROOT || p == a || p.startsWith("$a/")
    }

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun baseName(name: String): String = if (name.contains('.')) name.substringBeforeLast('.') else name
}

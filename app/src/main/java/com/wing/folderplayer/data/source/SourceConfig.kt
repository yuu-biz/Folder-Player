package com.wing.folderplayer.data.source

import androidx.annotation.Keep
import java.util.UUID

/**
 * Connection protocol of a source. LOCAL and WEBDAV keep their public-main names so stored JSON stays readable.
 * FTPS is FTP with [SourceConfig.useTls]. FAVORITES is a virtual list, not a protocol, and is not a SourceType.
 */
@Keep
enum class SourceType { LOCAL, WEBDAV, SMB, FTP, SAF }

/**
 * A configured source. Every constructor parameter has a default so Gson uses the no-arg constructor and fields
 * missing from older JSON get these defaults instead of null.
 *
 * Secrets are never stored here: the password lives in [CredentialStore] under [credentialRef] (defaults to [id]).
 *
 * Field use per type:
 *  - LOCAL: [url] = absolute root directory.
 *  - SAF: [url] = persisted tree URI.
 *  - WEBDAV: [url] = base URL, [path] = root path below it, [username].
 *  - SMB: [host], [port] (0 = 445), [share], [path] = root inside the share, [username], [domain].
 *  - FTP: [host], [port] (0 = 21), [path] = root, [username], [useTls] (explicit FTPS), [tlsPinnedSha256].
 */
@Keep
data class SourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: SourceType = SourceType.LOCAL,
    val url: String = "",
    val path: String? = null,
    val username: String = "",
    val host: String = "",
    val port: Int = 0,
    val share: String = "",
    val domain: String = "",
    val useTls: Boolean = false,
    /** Explicit trust for a self-signed FTPS server: SHA-256 of the leaf certificate (hex, colons optional). */
    val tlsPinnedSha256: String = "",
    /** SMB guest / FTP anonymous login chosen explicitly by the user (never an automatic fallback). */
    val anonymous: Boolean = false,
    /** Remote fav.json path inside this source used for favorites sync ("" = not a sync target). */
    val syncPath: String = "",
    val credentialRef: String = "",
    /** Auto-detected volume (internal storage / SD card); not persisted, cannot be edited or deleted. */
    @Transient val builtIn: Boolean = false,
    /** Incremented when connection settings change so caches keyed by source are invalidated. */
    val revision: Int = 0,
) {
    val effectiveCredentialRef: String get() = credentialRef.ifEmpty { id }

    val effectivePort: Int
        get() = when {
            port > 0 -> port
            type == SourceType.SMB -> 445
            type == SourceType.FTP -> 21
            else -> 0
        }

    val isNetwork: Boolean get() = type == SourceType.WEBDAV || type == SourceType.SMB || type == SourceType.FTP

    /** Normalized root path inside the server/share ("/" when unset). */
    val rootPath: String get() = SourcePath.normalize(path ?: "/")

    /** Human readable location without secrets (used in lists and logs). */
    fun describe(): String = when (type) {
        SourceType.LOCAL -> url
        SourceType.SAF -> url
        SourceType.WEBDAV -> url.trimEnd('/') + (path?.takeIf { it.isNotBlank() }?.let { "/" + it.trim('/') } ?: "")
        SourceType.SMB -> "smb://$host" + (if (port > 0) ":$port" else "") + "/$share" + rootPath.takeIf { it != "/" }.orEmpty()
        SourceType.FTP -> (if (useTls) "ftps://" else "ftp://") + host + (if (port > 0) ":$port" else "") + rootPath.takeIf { it != "/" }.orEmpty()
    }

    /** True if the change between this and [other] affects how the source is reached (caches/connections must reset). */
    fun connectionDiffers(other: SourceConfig): Boolean =
        type != other.type || url != other.url || path != other.path || username != other.username ||
            host != other.host || port != other.port || share != other.share || domain != other.domain ||
            useTls != other.useTls || tlsPinnedSha256 != other.tlsPinnedSha256 || anonymous != other.anonymous
}

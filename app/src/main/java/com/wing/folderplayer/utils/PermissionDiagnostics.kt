package com.wing.folderplayer.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType

/** Read access state for shared media and SAF folders, shown in Settings so a missing cover can be explained. */
object PermissionDiagnostics {
    enum class Access { GRANTED, PARTIAL, DENIED, NOT_APPLICABLE }

    data class Report(
        val sdk: Int,
        val audio: Access,
        val images: Access,
        val notifications: Access,
        /** SAF source name → persisted read permission still present. */
        val safFolders: List<Pair<String, Boolean>>,
    )

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** Permissions to request at start-up / from the diagnostics screen for this OS version. */
    fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun report(ctx: Context): Report {
        val sdk = Build.VERSION.SDK_INT
        val audio: Access
        val images: Access
        if (sdk >= 33) {
            audio = if (granted(ctx, Manifest.permission.READ_MEDIA_AUDIO)) Access.GRANTED else Access.DENIED
            images = when {
                granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) -> Access.GRANTED
                sdk >= 34 && granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
                else -> Access.DENIED
            }
        } else {
            val legacy = if (granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)) Access.GRANTED else Access.DENIED
            audio = legacy
            images = legacy
        }
        val notifications = if (sdk >= 33) {
            if (granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) Access.GRANTED else Access.DENIED
        } else Access.NOT_APPLICABLE
        val persisted = ctx.contentResolver.persistedUriPermissions
        val saf = if (SourceRegistry.isInitialized) {
            SourceRegistry.sources.value.filter { it.type == SourceType.SAF }.map { s ->
                s.name to persisted.any { it.uri.toString() == s.url && it.isReadPermission }
            }
        } else emptyList()
        return Report(sdk, audio, images, notifications, saf)
    }
}

package com.wing.folderplayer

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.wing.folderplayer.data.artwork.SourceImageFetcher
import com.wing.folderplayer.data.source.SourceRegistry

class FolderPlayerApp : Application(), ImageLoaderFactory {
    companion object {
        lateinit var context: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        com.wing.folderplayer.utils.CrashHandler.init(this)
        // Migrates public-main data on first start of this build, then serves sources by sourceId.
        SourceRegistry.init(this)
    }

    /** Coil resolves fpsrc:// images through SourceRegistry; memory cache is bounded to avoid OOM in long grids. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SourceImageFetcher.Factory(this@FolderPlayerApp)) }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}

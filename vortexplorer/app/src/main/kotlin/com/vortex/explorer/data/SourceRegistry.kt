package com.vortex.explorer.data

import android.content.Context
import android.net.Uri
import com.vortex.explorer.core.FileSource
import com.vortex.explorer.core.SmbSource
import com.vortex.explorer.core.WebDavSource
import com.vortex.explorer.sources.GallerySource
import com.vortex.explorer.sources.SafSource

/**
 * Opens saved sources on first use and keeps them open, so an SMB session or
 * a WebDAV connection pool is reused across screens.
 */
class SourceRegistry(private val context: Context, private val store: SourceStore) {
    private val open = HashMap<String, FileSource>()

    @Synchronized
    fun get(id: String): FileSource? {
        open[id]?.let { return it }
        val spec = store.find(id) ?: return null
        return create(spec).also { open[id] = it }
    }

    /** Builds a source without registering it — for "test connection" before saving. */
    fun create(spec: SourceSpec, password: String = store.password(spec.id)): FileSource = when (spec) {
        is SourceSpec.Gallery -> GallerySource(context)
        is SourceSpec.Folder -> SafSource(context, spec.id, spec.name, Uri.parse(spec.treeUri))
        is SourceSpec.Smb -> SmbSource(
            id = spec.id,
            displayName = spec.name,
            host = spec.host,
            share = spec.share,
            username = spec.user,
            password = password,
            domain = spec.domain,
            port = spec.port,
            basePath = spec.path,
        )
        is SourceSpec.WebDav -> WebDavSource(
            id = spec.id,
            displayName = spec.name,
            baseUrl = spec.url,
            username = spec.user.ifEmpty { null },
            password = password.ifEmpty { null },
        )
    }

    /** Drops a cached source so the next [get] reconnects with fresh settings. */
    @Synchronized
    fun forget(id: String) {
        open.remove(id)?.let { runCatching { it.close() } }
    }

    @Synchronized
    fun closeAll() {
        open.values.forEach { runCatching { it.close() } }
        open.clear()
    }
}

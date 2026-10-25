package com.vortex.view.data

import android.net.Uri
import com.vortex.view.core.Folder
import com.vortex.view.core.Storage

/**
 * Storage the app can also pick folders in. [SafStorage] on a phone; the
 * on-device tests use plain files in the app's own storage instead.
 */
interface Places : Storage {
    /** Keeps access to a picked folder across restarts. */
    fun remember(tree: Uri)

    fun forget(tree: Uri)

    fun hasAccess(folder: Folder): Boolean

    fun folder(tree: Uri): Folder

    /** Where the folder picker should start: inside [folder]. */
    fun startingPoint(folder: Folder): Uri?
}

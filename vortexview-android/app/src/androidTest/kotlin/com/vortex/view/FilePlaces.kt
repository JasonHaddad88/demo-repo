package com.vortex.view

import android.net.Uri
import com.vortex.view.core.Folder
import com.vortex.view.core.MoveException
import com.vortex.view.core.Names
import com.vortex.view.core.Photo
import com.vortex.view.data.Places
import java.io.File

/**
 * Plain folders in the app's own storage, standing in for folders picked in
 * Android's folder picker, which a test can't drive. Photos are file:// URIs,
 * so the app loads and shows them exactly as it does real ones.
 */
class FilePlaces : Places {
    private fun file(id: String) = File(Uri.parse(id).path!!)

    private fun photo(file: File) = Photo(Uri.fromFile(file).toString(), file.name, file.length(), file.lastModified())

    override fun remember(tree: Uri) {}

    override fun forget(tree: Uri) {}

    override fun hasAccess(folder: Folder): Boolean = true

    override fun folder(tree: Uri): Folder = Folder(tree.toString(), File(tree.path!!).name)

    override fun startingPoint(folder: Folder): Uri? = null

    override fun list(folder: Folder): List<Photo> =
        (file(folder.id).listFiles() ?: throw MoveException("Can't read ${folder.name}"))
            .filter { it.isFile && Names.isPhoto(it.name) }
            .map(::photo)

    override fun names(folder: Folder): Set<String> =
        file(folder.id).list()?.mapTo(HashSet()) { it.lowercase() } ?: emptySet()

    override fun exists(folder: Folder): Boolean = file(folder.id).isDirectory

    override fun exists(photo: Photo): Boolean = file(photo.id).isFile

    override fun sameFolder(a: Folder, b: Folder): Boolean = file(a.id).canonicalPath == file(b.id).canonicalPath

    override fun move(photo: Photo, from: Folder, to: Folder, name: String): Photo {
        val destination = File(file(to.id), name)
        if (!file(photo.id).renameTo(destination)) throw MoveException("Couldn't move ${photo.name}")
        return photo(destination)
    }
}

package com.vortex.view.core

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Plain files: what the tests use, and anything with a real file system. */
class FileStorage : Storage {
    fun folder(file: File): Folder = Folder(file.absolutePath, file.name)

    override fun list(folder: Folder): List<Photo> {
        val files = File(folder.id).listFiles() ?: throw MoveException("Can't read ${folder.name}")
        return files
            .filter { it.isFile && Names.isPhoto(it.name) }
            .map { Photo(it.absolutePath, it.name, it.length(), it.lastModified()) }
    }

    override fun names(folder: Folder): Set<String> =
        File(folder.id).list()?.mapTo(HashSet()) { it.lowercase() } ?: emptySet()

    override fun exists(folder: Folder): Boolean = File(folder.id).isDirectory

    override fun exists(photo: Photo): Boolean = File(photo.id).isFile

    override fun sameFolder(a: Folder, b: Folder): Boolean =
        runCatching { File(a.id).canonicalFile == File(b.id).canonicalFile }.getOrDefault(a.id == b.id)

    override fun move(photo: Photo, from: Folder, to: Folder, name: String): Photo {
        val source = File(photo.id)
        val destination = File(to.id, name)
        try {
            // A rename on the same drive; a copy, then removal, across drives.
            Files.move(source.toPath(), destination.toPath())
        } catch (e: IOException) {
            throw MoveException("Couldn't move ${photo.name}: ${e.message}", e)
        }
        return Photo(destination.absolutePath, destination.name, destination.length(), destination.lastModified())
    }
}

package com.vortex.view.core

/**
 * A folder the app may read and write. [id] means something only to the
 * [Storage] that made it: a file path in tests, a document-tree URI on a phone.
 */
data class Folder(val id: String, val name: String)

/** A photo inside a folder. [id] is likewise the storage's own handle. */
data class Photo(
    val id: String,
    val name: String,
    val size: Long = -1,
    val modified: Long = 0,
)

/** Something a person can fix; the message is shown to them as is. */
class MoveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Where photos live. Everything the gallery does to files goes through here. */
interface Storage {
    /** The photos directly inside [folder], in no particular order. */
    fun list(folder: Folder): List<Photo>

    /** Every name inside [folder], photo or not, lower-cased: for picking a free name. */
    fun names(folder: Folder): Set<String>

    fun exists(folder: Folder): Boolean

    fun exists(photo: Photo): Boolean

    fun sameFolder(a: Folder, b: Folder): Boolean

    /**
     * Moves [photo] out of [from] into [to], naming it [name] there — a name
     * the caller has checked is free. Returns the photo at its new home.
     */
    fun move(photo: Photo, from: Folder, to: Folder, name: String): Photo
}

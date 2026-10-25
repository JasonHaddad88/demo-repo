package com.vortex.view.core

/** One move, remembered so it can be undone. */
data class Move(
    val photo: Photo,
    val from: Folder,
    val to: Folder,
    val moved: Photo,
    val index: Int,
) {
    val renamed: Boolean get() = photo.name != moved.name
}

/**
 * The folder you are going through and the photo you are on. Not thread-safe:
 * the app calls it from one background thread at a time.
 */
class Gallery(private val storage: Storage) {
    var folder: Folder? = null
        private set
    private val list = ArrayList<Photo>()
    val photos: List<Photo> get() = list
    var index: Int = 0
        private set
    private val history = ArrayList<Move>()
    val canUndo: Boolean get() = history.isNotEmpty()

    val current: Photo? get() = list.getOrNull(index)

    /** Opens [folder], starting at the photo called [startAt] if it is there. */
    fun open(folder: Folder, startAt: String? = null) {
        if (!storage.exists(folder)) throw MoveException("The folder ${folder.name} isn't there any more — open it again")
        val photos = scan(folder)
        this.folder = folder
        list.clear()
        list.addAll(photos)
        index = startAt?.let { name -> list.indexOfFirst { it.name == name } }?.takeIf { it >= 0 } ?: 0
    }

    /** Reads the folder again, staying on the same photo when it is still there. */
    fun reload() {
        val folder = folder ?: return
        val currentName = current?.name
        val photos = scan(folder)
        list.clear()
        list.addAll(photos)
        val same = list.indexOfFirst { it.name == currentName }
        index = if (same >= 0) same else index.coerceAtMost((list.size - 1).coerceAtLeast(0))
    }

    fun neighbour(offset: Int): Photo? = list.getOrNull(index + offset)

    /** Steps forward or back; false at either end. */
    fun go(delta: Int): Boolean {
        if (list.isEmpty()) return false
        val target = (index + delta).coerceIn(0, list.size - 1)
        val changed = target != index
        index = target
        return changed
    }

    fun goTo(position: Int) {
        if (list.isNotEmpty()) index = position.coerceIn(0, list.size - 1)
    }

    /** Moves the photo on screen into [target]; the next photo takes its place. */
    fun moveCurrent(target: Folder): Move {
        val photo = current ?: throw MoveException("There's no photo to move")
        val from = folder ?: throw MoveException("There's no photo to move")
        if (storage.sameFolder(from, target)) throw MoveException("${photo.name} is already in ${target.name}")
        if (!storage.exists(target)) {
            throw MoveException("The folder ${target.name} isn't there any more — choose it again")
        }
        val name = Names.unique(photo.name, storage.names(target))
        val moved = storage.move(photo, from, target, name)
        val record = Move(photo, from, target, moved, index)
        history += record
        list.removeAt(index)
        if (index >= list.size) index = (list.size - 1).coerceAtLeast(0)
        return record
    }

    /**
     * Puts the last moved photo back where it was and shows it again — even
     * after another folder was opened. Returns the move that undid it.
     */
    fun undo(): Move? {
        val last = history.lastOrNull() ?: return null
        if (!storage.exists(last.moved)) {
            history.removeAt(history.size - 1)
            throw MoveException("Can't undo: ${last.moved.name} is no longer in ${last.to.name}")
        }
        if (!storage.exists(last.from)) throw MoveException("Can't undo: the folder ${last.from.name} is gone")
        val name = Names.unique(last.photo.name, storage.names(last.from))
        val back = storage.move(last.moved, last.to, last.from, name)
        history.removeAt(history.size - 1)

        val open = folder
        if (open != null && storage.sameFolder(open, last.from)) {
            val position = last.index.coerceAtMost(list.size)
            list.add(position, back)
            index = position
        }
        return Move(last.moved, last.to, last.from, back, last.index)
    }

    private fun scan(folder: Folder): List<Photo> =
        storage.list(folder).sortedWith(compareBy(Names.naturalOrder) { it.name })
}

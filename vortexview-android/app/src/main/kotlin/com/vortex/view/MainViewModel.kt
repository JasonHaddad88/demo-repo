package com.vortex.view

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.view.core.Folder
import com.vortex.view.core.Gallery
import com.vortex.view.core.MoveException
import com.vortex.view.core.Photo
import com.vortex.view.data.Places
import com.vortex.view.data.Prefs
import com.vortex.view.data.SafStorage
import com.vortex.view.media.PhotoLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

const val MAX_TARGETS = 9

/** A snapshot of the gallery for the screen to draw. */
data class UiState(
    val folder: Folder? = null,
    val photos: List<Photo> = emptyList(),
    val index: Int = 0,
    val targets: List<Folder> = emptyList(),
    val canUndo: Boolean = false,
    val busy: Boolean = false,
) {
    val current: Photo? get() = photos.getOrNull(index)
}

data class Message(val text: String, val error: Boolean = false, val undoable: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel @JvmOverloads constructor(
    app: Application,
    private val storage: Places = SafStorage(app),
    private val prefs: Prefs = Prefs(app),
) : AndroidViewModel(app) {
    val loader = PhotoLoader(app)
    private val gallery = Gallery(storage)

    // The gallery is touched by the file-work thread and, for quick steps, the
    // main thread: one at a time.
    private val lock = ReentrantLock()
    private val worker = Dispatchers.IO.limitedParallelism(1)

    var state by mutableStateOf(UiState(targets = prefs.targets))
        private set

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 8)
    val messages: SharedFlow<Message> = _messages

    init {
        val folder = prefs.folder
        if (folder != null && storage.hasAccess(folder)) {
            background { gallery.open(folder, startAt = prefs.lastPhoto) }
        }
    }

    // -- folders ------------------------------------------------------------

    fun openFolder(tree: Uri) = background {
        storage.remember(tree)
        val folder = storage.folder(tree)
        gallery.open(folder)
        prefs.folder = folder
        prefs.lastPhoto = null
        val count = gallery.photos.size
        say(if (count == 0) "No photos in ${folder.name}" else "$count photo${if (count == 1) "" else "s"} in ${folder.name}")
    }

    /** Sets folder [slot] (or adds one, when [slot] is null) to the folder picked. */
    fun setTarget(tree: Uri, slot: Int?) = background {
        storage.remember(tree)
        val folder = storage.folder(tree)
        val open = gallery.folder
        if (open != null && storage.sameFolder(open, folder)) {
            throw MoveException("That's the folder you're going through — choose a different one to move photos into")
        }
        val targets = state.targets.toMutableList()
        val existing = targets.indexOfFirst { storage.sameFolder(it, folder) }
        if (existing >= 0 && existing != slot) {
            say("${folder.name} is already folder ${existing + 1}")
            return@background
        }
        val position = if (slot != null && slot < targets.size) {
            val replaced = targets.set(slot, folder)
            if (!storage.sameFolder(replaced, folder)) release(replaced)
            slot
        } else {
            if (targets.size >= MAX_TARGETS) throw MoveException("You can have up to $MAX_TARGETS folders — remove one first")
            targets += folder
            targets.size - 1
        }
        prefs.targets = targets
        state = state.copy(targets = targets)
        say("Tap “${position + 1}  ${folder.name}” to move the photo you're on there")
    }

    fun removeTarget(slot: Int) = background {
        val targets = state.targets.toMutableList()
        if (slot !in targets.indices) return@background
        val removed = targets.removeAt(slot)
        release(removed)
        prefs.targets = targets
        state = state.copy(targets = targets)
        say("Removed ${removed.name} from the list (the folder itself is untouched)")
    }

    /** Where the folder picker should open: in the folder you're going through. */
    fun pickerStart(): Uri? = state.folder?.let { runCatching { storage.startingPoint(it) }.getOrNull() }

    private fun release(folder: Folder) {
        val open = gallery.folder
        if (open == null || !storage.sameFolder(open, folder)) storage.forget(Uri.parse(folder.id))
    }

    // -- the point of it all ------------------------------------------------

    fun moveTo(slot: Int) = background {
        val target = state.targets.getOrNull(slot) ?: return@background
        val move = gallery.moveCurrent(target)
        loader.forget(move.photo)
        val note = if (move.renamed) " as ${move.moved.name}" else ""
        val left = gallery.photos.size
        say("Moved ${move.photo.name} → ${target.name}$note · $left left", undoable = true)
    }

    fun undo() = background {
        val move = gallery.undo()
        if (move == null) say("Nothing to undo") else say("Put ${move.moved.name} back in ${move.to.name}")
    }

    fun reload() = background { gallery.reload() }

    /**
     * Steps right away, on the calling thread, so a swipe lands without a
     * flicker. False at either end, or while a move is still running.
     */
    fun step(delta: Int): Boolean {
        if (!lock.tryLock()) return false
        try {
            if (!gallery.go(delta)) return false
            publish()
            return true
        } finally {
            lock.unlock()
        }
    }

    // -- plumbing -----------------------------------------------------------

    private fun background(block: () -> Unit) {
        viewModelScope.launch(worker) {
            lock.withLock {
                // Every write to [state] happens under the lock, so none is lost.
                state = state.copy(busy = true)
                try {
                    block()
                } catch (e: MoveException) {
                    say(e.message ?: "That didn't work", error = true)
                } catch (e: SecurityException) {
                    say("VortexView wasn't given access to that folder — choose it again", error = true)
                } catch (e: Exception) {
                    say("Something went wrong: ${e.message ?: e.javaClass.simpleName}", error = true)
                } finally {
                    publish()
                }
            }
        }
    }

    /** Copies the gallery into [state]. Called with [lock] held. */
    private fun publish() {
        val current = gallery.current
        state = state.copy(
            folder = gallery.folder,
            photos = gallery.photos.toList(),
            index = gallery.index,
            canUndo = gallery.canUndo,
            busy = false,
        )
        if (current != null) prefs.lastPhoto = current.name
    }

    private fun say(text: String, error: Boolean = false, undoable: Boolean = false) {
        _messages.tryEmit(Message(text, error, undoable))
    }

    override fun onCleared() {
        loader.close()
    }
}

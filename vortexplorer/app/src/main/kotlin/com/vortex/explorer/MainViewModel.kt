package com.vortex.explorer

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.explorer.core.Capability
import com.vortex.explorer.core.Conflict
import com.vortex.explorer.core.Copier
import com.vortex.explorer.core.Entry
import com.vortex.explorer.core.EntryFilter
import com.vortex.explorer.core.EntryKind
import com.vortex.explorer.core.ExportOptions
import com.vortex.explorer.core.Exporter
import com.vortex.explorer.core.FileSource
import com.vortex.explorer.core.MediaTypes
import com.vortex.explorer.core.Names
import com.vortex.explorer.core.Origin
import com.vortex.explorer.core.SeenStore
import com.vortex.explorer.core.ShuffleMode
import com.vortex.explorer.core.ShuffleOptions
import com.vortex.explorer.core.ShuffleResult
import com.vortex.explorer.core.Shuffler
import com.vortex.explorer.core.SourceException
import com.vortex.explorer.core.TransferProgress
import com.vortex.explorer.core.TransferReport
import com.vortex.explorer.core.Walker
import com.vortex.explorer.data.LastShuffle
import com.vortex.explorer.data.Preferences
import com.vortex.explorer.data.Settings
import com.vortex.explorer.data.SourceRegistry
import com.vortex.explorer.data.SourceSpec
import com.vortex.explorer.data.SourceStore
import com.vortex.explorer.media.ImageLoader
import com.vortex.explorer.sources.GallerySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed class Screen {
    data object Home : Screen()
    data class Browse(val sourceId: String, val path: String, val title: String) : Screen()
    data object Shuffle : Screen()
    data class Viewer(
        val items: List<Entry>,
        val start: Int,
        val autoplay: Boolean,
        val fromShuffle: Boolean,
    ) : Screen()

    /** Choosing a destination folder; [sourceId] null means "pick a source first". */
    data class Pick(val request: PickRequest, val sourceId: String? = null, val path: String? = null, val title: String = "") : Screen()
    data object Settings : Screen()
}

sealed class PickRequest {
    abstract val verb: String

    data class Copy(val items: List<Entry>, val move: Boolean) : PickRequest() {
        override val verb: String get() = if (move) "Move here" else "Copy here"
    }

    data class Export(val order: List<Entry>, val options: ExportOptions, val newFolder: String?) : PickRequest() {
        override val verb: String get() = "Export here"
    }
}

data class Busy(
    val title: String,
    val detail: String = "",
    val fraction: Float? = null,
    val cancellable: Boolean = false,
)

data class ShuffleSession(
    val label: String,
    val origins: List<Pair<String, String>>,
    val extra: List<Entry>,
    val kinds: Set<EntryKind>,
    val recursive: Boolean,
    val pool: List<Entry>,
    val result: ShuffleResult,
    val options: ShuffleOptions,
    val problems: List<String>,
    val truncated: Boolean,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Application get() = getApplication()
    private val store = SourceStore(app)
    private val settings = Settings(app)
    val registry = SourceRegistry(app, store)
    val images = ImageLoader(app, registry)
    private val seen = SeenStore(File(app.filesDir, "seen.txt"))

    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = backStack.last()

    val sources = mutableStateListOf<SourceSpec>().apply { addAll(store.all()) }
    var prefs by mutableStateOf(settings.load())
        private set
    var busy by mutableStateOf<Busy?>(null)
        private set
    var session by mutableStateOf<ShuffleSession?>(null)
        private set
    var lastShuffle by mutableStateOf(settings.lastShuffle())
        private set
    var mix by mutableStateOf(setOf<String>())
    var seenCount by mutableIntStateOf(seen.size)
        private set

    /** Bumped after anything that changes folder contents, so open listings reload. */
    var revision by mutableIntStateOf(0)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    private val cancelRequested = AtomicBoolean(false)
    private var job: Job? = null

    // -- navigation -------------------------------------------------------

    fun go(screen: Screen) {
        backStack.add(screen)
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    private fun leavePicking() {
        while (screen is Screen.Pick && backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun cancelPick() = leavePicking()

    /** True when [path] is [folder] or below it: "/a/b" is inside "/a", "/ab" is not. */
    private fun isInside(path: String, folder: String): Boolean =
        path == folder ||
            path.startsWith("$folder/") ||
            // Document URIs encode the separator: …/document/primary%3ADCIM%2FCamera
            path.startsWith("$folder%2F", ignoreCase = true)

    fun say(message: String) {
        _messages.tryEmit(message)
    }

    fun updatePrefs(change: (Preferences) -> Preferences) {
        prefs = change(prefs)
        settings.save(prefs)
    }

    fun source(id: String): FileSource? = registry.get(id)

    fun spec(id: String): SourceSpec? = sources.firstOrNull { it.id == id }

    // -- sources ----------------------------------------------------------

    fun addFolder(uri: Uri) {
        val resolver = context.contentResolver
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching { resolver.takePersistableUriPermission(uri, readWrite) }.isSuccess ||
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        if (!persisted) say("Android did not let VortexPlorer keep access; it may ask again later")
        val name = folderName(uri)
        store.save(SourceSpec.Folder(SourceSpec.newId(), name, uri.toString()))
        refreshSources()
        say("Added $name")
    }

    private fun folderName(uri: Uri): String {
        val root = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        }.getOrNull()
        val queried = root?.let {
            runCatching {
                context.contentResolver.query(it, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
        }
        return queried?.takeIf { it.isNotBlank() }
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/') }
                .getOrNull()?.ifBlank { null }
            ?: "Folder"
    }

    fun saveNetworkSource(spec: SourceSpec, password: String) {
        store.save(spec, password)
        registry.forget(spec.id)
        refreshSources()
        say("Saved ${spec.name}")
    }

    /** Null when the connection works, otherwise what went wrong. */
    suspend fun testSource(spec: SourceSpec, password: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            registry.create(spec, password).use { it.list(it.rootPath) }
            null
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }

    fun storedPassword(id: String): String = store.password(id)

    fun removeSource(spec: SourceSpec) {
        if (spec is SourceSpec.Gallery) return
        if (spec is SourceSpec.Folder) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(spec.treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        store.remove(spec.id)
        registry.forget(spec.id)
        mix = mix - spec.id
        refreshSources()
        say("Removed ${spec.name}")
    }

    private fun refreshSources() {
        sources.clear()
        sources.addAll(store.all())
    }

    // -- browsing ---------------------------------------------------------

    suspend fun list(sourceId: String, path: String): List<Entry> = withContext(Dispatchers.IO) {
        val source = registry.get(sourceId) ?: throw SourceException("that source is no longer saved")
        val showHidden = prefs.showHidden
        source.list(path)
            .filter { showHidden || !it.isHidden }
            .sortedWith(compareBy<Entry>({ !it.isFolder }, { it.name.lowercase() }))
    }

    /** Where a whole-source shuffle starts: everything in the gallery, the top of anything else. */
    fun shuffleRoot(sourceId: String): String? =
        if (sourceId == SourceSpec.GALLERY_ID) GallerySource.ALL else registry.get(sourceId)?.rootPath

    // -- shuffling --------------------------------------------------------

    fun optionsFromPrefs(seed: String? = null) = ShuffleOptions(
        mode = prefs.mode,
        seed = seed,
        noSameFolderTwice = prefs.noSameFolderTwice,
        bias = prefs.bias.toDouble(),
    )

    /**
     * Gathers every matching file under [origins] (plus [extra] files picked
     * by hand), shuffles them, and opens the result.
     */
    fun shuffle(
        label: String,
        origins: List<Pair<String, String>>,
        kinds: Set<EntryKind> = prefs.kinds,
        recursive: Boolean = prefs.includeSubfolders,
        options: ShuffleOptions = optionsFromPrefs(),
        extra: List<Entry> = emptyList(),
        startAt: Int = 0,
        play: Boolean = false,
    ) {
        runJob("Gathering files…", cancellable = true) { cancelled ->
            val walkOrigins = origins.mapNotNull { (id, path) -> registry.get(id)?.let { Origin(it, path) } }
            val filter = EntryFilter(kinds, includeHidden = prefs.showHidden)
            var lastUpdate = 0L
            val walk = Walker().collect(walkOrigins, filter, recursive, cancelled) { folders, files ->
                val now = SystemClock.uptimeMillis()
                if (now - lastUpdate > PROGRESS_INTERVAL_MS) {
                    lastUpdate = now
                    progress("Gathering files…", "$files found in $folders folders")
                }
            }
            if (walk.cancelled) return@runJob
            val pool = walk.items + extra.filter { filter.accepts(it) }
            if (pool.isEmpty()) {
                say(walk.problems.firstOrNull()?.let { "Nothing to shuffle — $it" } ?: "Nothing to shuffle there")
                return@runJob
            }
            progress("Shuffling ${pool.size} files…", "")
            val result = Shuffler.shuffle(pool, options, seen::isSeen)
            val newSession = ShuffleSession(
                label, origins, extra, kinds, recursive, pool, result,
                options.copy(seed = result.seed), walk.problems, walk.truncated,
            )
            withContext(Dispatchers.Main) {
                session = newSession
                remember(newSession, startAt)
                if (screen != Screen.Shuffle) go(Screen.Shuffle)
                if (play) {
                    go(Screen.Viewer(result.items, startAt.coerceIn(0, result.items.lastIndex), autoplay = false, fromShuffle = true))
                }
            }
        }
    }

    /** Same files, new order — or a specific seed/mode when [options] says so. */
    fun reshuffle(options: ShuffleOptions? = null) {
        val current = session ?: return
        val next = options ?: current.options.copy(seed = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { Shuffler.shuffle(current.pool, next, seen::isSeen) }
            val updated = current.copy(result = result, options = next.copy(seed = result.seed))
            session = updated
            remember(updated, 0)
        }
    }

    /** The next lap of a looping slideshow; never opens on the file just shown. */
    suspend fun nextRound(): ShuffleResult? {
        val current = session ?: return null
        val result = withContext(Dispatchers.Default) {
            Shuffler.nextRound(current.pool, current.result, current.options, seen::isSeen)
        }
        val updated = current.copy(result = result, options = current.options.copy(seed = result.seed))
        session = updated
        remember(updated, 0)
        return result
    }

    fun pickOne(): Entry? {
        val current = session ?: return null
        return Shuffler.pickOne(current.pool, current.options.copy(seed = null), seen::isSeen)
    }

    fun resumeLast() {
        val last = lastShuffle ?: return
        shuffle(
            label = last.label,
            origins = last.origins,
            kinds = last.kinds,
            recursive = last.includeSubfolders,
            options = ShuffleOptions(
                mode = last.mode,
                seed = last.seed,
                noSameFolderTwice = last.noSameFolderTwice,
                bias = last.bias.toDouble(),
            ),
            startAt = last.position,
            play = true,
        )
    }

    private fun remember(value: ShuffleSession, position: Int) {
        // Hand-picked files cannot be found again by walking, so only folder shuffles resume.
        if (value.origins.isEmpty()) return
        val last = LastShuffle(
            label = value.label,
            origins = value.origins,
            kinds = value.kinds,
            includeSubfolders = value.recursive,
            mode = value.options.mode,
            seed = value.result.seed,
            noSameFolderTwice = value.options.noSameFolderTwice,
            bias = value.options.bias.toFloat(),
            position = position,
        )
        settings.saveLastShuffle(last)
        lastShuffle = last
    }

    // -- viewing ----------------------------------------------------------

    fun viewed(entry: Entry, position: Int, fromShuffle: Boolean) {
        if (prefs.markSeen) {
            viewModelScope.launch(Dispatchers.IO) {
                seen.markSeen(entry)
                val count = seen.size
                withContext(Dispatchers.Main) { seenCount = count }
            }
        }
        if (fromShuffle) {
            settings.savePosition(position)
            lastShuffle = lastShuffle?.copy(position = position)
        }
    }

    fun forgetSeen() {
        seen.clear()
        seenCount = 0
        say("Forgot every file you've seen")
    }

    /** Hands a file to another app: video and audio players, mostly. */
    fun openWith(entry: Entry) {
        val mime = entry.mime ?: MediaTypes.mimeFor(entry.name)
        if (entry.path.startsWith("content://")) {
            launchView(Uri.parse(entry.path), mime, entry.name)
            return
        }
        runJob("Fetching ${entry.name}…", cancellable = true) { cancelled ->
            val folder = File(context.cacheDir, "open").apply { mkdirs() }
            val hourAgo = System.currentTimeMillis() - 60 * 60 * 1000
            folder.listFiles()?.filter { it.lastModified() < hourAgo }?.forEach { it.delete() }
            val file = File(folder, Names.safe(entry.name))
            val source = registry.get(entry.sourceId) ?: throw SourceException("that source is no longer saved")
            source.open(entry.path).use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        if (cancelled()) {
                            file.delete()
                            return@runJob
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        if (entry.size > 0) progress("Fetching ${entry.name}…", "", total.toFloat() / entry.size)
                    }
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            withContext(Dispatchers.Main) { launchView(uri, mime, entry.name) }
        }
    }

    private fun launchView(uri: Uri, mime: String, name: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            say("No app on this phone can open $name")
        }
    }

    // -- changing files -----------------------------------------------------

    fun mkdir(sourceId: String, path: String, name: String) = runJob("Creating folder…") {
        val source = registry.get(sourceId) ?: throw SourceException("that source is no longer saved")
        source.mkdir(path, name)
        changed("Created $name")
    }

    fun rename(entry: Entry, newName: String) = runJob("Renaming…") {
        val source = registry.get(entry.sourceId) ?: throw SourceException("that source is no longer saved")
        source.rename(entry, newName)
        changed("Renamed to $newName")
    }

    fun delete(entries: List<Entry>) = runJob("Deleting…", cancellable = true) { cancelled ->
        var done = 0
        val problems = ArrayList<String>()
        for (entry in entries) {
            if (cancelled()) break
            val source = registry.get(entry.sourceId)
            if (source == null) {
                problems += "${entry.name}: source no longer saved"
                continue
            }
            try {
                source.delete(entry)
                done++
            } catch (e: Exception) {
                problems += "${entry.name}: ${e.message}"
            }
            progress("Deleting…", entry.name, done.toFloat() / entries.size)
        }
        changed(
            when {
                problems.isEmpty() -> "Deleted $done"
                else -> "Deleted $done, ${problems.size} failed — ${problems.first()}"
            },
        )
    }

    fun copyHere(request: PickRequest.Copy, sourceId: String, path: String) {
        if (request.items.all { it.sourceId == sourceId && it.parent == path }) {
            say("They are already in this folder")
            return
        }
        // Copying or moving a folder into itself would never finish.
        if (request.items.any { it.isFolder && it.sourceId == sourceId && isInside(path, it.path) }) {
            say("Cannot put a folder inside itself")
            return
        }
        val title = if (request.move) "Moving…" else "Copying…"
        runJob(title, cancellable = true) { cancelled ->
            val to = registry.get(sourceId) ?: throw SourceException("that destination is no longer saved")
            val report = Copier(registry::get).copy(
                request.items, to, path, Conflict.RENAME, request.move, cancelled,
            ) { progress(title, describe(it), fraction(it)) }
            withContext(Dispatchers.Main) { leavePicking() }
            changed(summary(report, if (request.move) "Moved" else "Copied"))
        }
    }

    fun exportHere(request: PickRequest.Export, sourceId: String, path: String) {
        runJob("Exporting shuffle…", cancellable = true) { cancelled ->
            val to = registry.get(sourceId) ?: throw SourceException("that destination is no longer saved")
            val folder = request.newFolder?.let { wanted ->
                val taken = to.list(path).map { it.name }.toSet()
                to.mkdir(path, Names.unique(Names.safe(wanted), taken)).path
            } ?: path
            val report = Exporter.export(request.order, registry::get, to, folder, request.options, cancelled) {
                progress("Exporting shuffle…", describe(it), fraction(it))
            }
            withContext(Dispatchers.Main) { leavePicking() }
            changed(summary(report, "Exported"))
        }
    }

    /** Renames a folder's files with shuffled number prefixes, for players that sort by name. */
    fun reshuffleInPlace(sourceId: String, path: String) {
        runJob("Renaming in shuffled order…", cancellable = true) { cancelled ->
            val source = registry.get(sourceId) ?: throw SourceException("that source is no longer saved")
            if (!source.can(Capability.RENAME)) throw SourceException("${source.displayName} cannot rename files")
            val files = source.list(path).filter { !it.isFolder && !it.isHidden && !it.name.endsWith(".m3u") }
            if (files.size < 2) {
                say("Nothing to reshuffle in this folder")
                return@runJob
            }
            val order = Shuffler.shuffle(files, optionsFromPrefs()).items
            val report = Exporter.reshuffleInPlace(order, source, ExportOptions(), cancelled) {
                progress("Renaming in shuffled order…", it.current, fraction(it))
            }
            changed(
                if (report.ok) {
                    "Renumbered ${report.written.size} files in a new random order"
                } else {
                    summary(report, "Renumbered")
                },
            )
        }
    }

    // -- jobs -------------------------------------------------------------

    private fun runJob(title: String, cancellable: Boolean = false, block: suspend (isCancelled: () -> Boolean) -> Unit) {
        if (job?.isActive == true) {
            say("Still busy — wait for the current task to finish")
            return
        }
        cancelRequested.set(false)
        busy = Busy(title, cancellable = cancellable)
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block { cancelRequested.get() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = null
            }
        }
    }

    fun cancelJob() {
        cancelRequested.set(true)
        busy = busy?.copy(detail = "Stopping…", cancellable = false)
    }

    private fun progress(title: String, detail: String, fraction: Float? = null) {
        viewModelScope.launch {
            if (busy != null) busy = Busy(title, detail, fraction, cancellable = !cancelRequested.get())
        }
    }

    private suspend fun changed(message: String) {
        withContext(Dispatchers.Main) {
            revision++
            say(message)
        }
    }

    private fun describe(progress: TransferProgress): String =
        "${progress.filesDone} of ${progress.filesTotal}" +
            if (progress.current.isNotEmpty()) " · ${progress.current}" else ""

    private fun fraction(progress: TransferProgress): Float? = when {
        progress.bytesTotal > 0 -> (progress.bytesDone.toDouble() / progress.bytesTotal).toFloat().coerceIn(0f, 1f)
        progress.filesTotal > 0 -> progress.filesDone.toFloat() / progress.filesTotal
        else -> null
    }

    private fun summary(report: TransferReport, verb: String): String {
        val files = report.written.count { !it.isFolder && !it.name.endsWith(".m3u") }
        return when {
            report.cancelled -> "Stopped — $verb $files before that"
            report.failed.isEmpty() && report.skipped.isEmpty() -> "$verb $files"
            report.failed.isEmpty() -> "$verb $files, skipped ${report.skipped.size} already there"
            else -> "$verb $files, ${report.failed.size} failed — ${report.failed.first().first.name}: ${report.failed.first().second}"
        }
    }

    override fun onCleared() {
        registry.closeAll()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 200L
    }
}

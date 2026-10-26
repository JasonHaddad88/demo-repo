package com.vortex.view

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vortex.view.core.Folder
import com.vortex.view.media.Decoded
import com.vortex.view.ui.Icons
import com.vortex.view.ui.PhotoStage
import com.vortex.view.ui.VortexTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light icons on the dark bars, whatever the phone's theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            VortexTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) { VortexViewApp(vm) }
            }
        }
    }
}

@Composable
fun VortexViewApp(vm: MainViewModel) {
    val state = vm.state
    val snackbar = remember { SnackbarHostState() }
    var lastError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // A newer message replaces the one showing, so a run of moves never queues up.
        vm.messages.collectLatest { message ->
            lastError = message.error
            val result = snackbar.showSnackbar(
                message.text,
                actionLabel = message.action?.label,
                duration = if (message.error) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (message.action) {
                    Action.UNDO -> vm.undo()
                    Action.NAME_ORDER -> vm.sortByName()
                    null -> {}
                }
            }
        }
    }

    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) vm.openFolder(tree)
    }
    var targetSlot by remember { mutableStateOf<Int?>(null) }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val slot = targetSlot
        targetSlot = null
        if (tree != null) vm.setTarget(tree, slot)
    }
    val chooseTarget: (Int?) -> Unit = { slot ->
        targetSlot = slot
        pickTarget.launch(vm.pickerStart())
    }

    var chrome by remember { mutableStateOf(true) }
    var shown by remember { mutableStateOf<Decoded?>(null) }
    val current = state.current
    LaunchedEffect(current?.id) { shown = current?.let { vm.loader.cached(it) } }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val maxSide = with(density) {
        maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx()
    }.coerceIn(1024, 4096)

    // Warm up the photos either side, so the next swipe is instant.
    LaunchedEffect(current?.id, state.photos.size) {
        val around = listOfNotNull(
            state.photos.getOrNull(state.index + 1),
            state.photos.getOrNull(state.index - 1),
            state.photos.getOrNull(state.index + 2),
        )
        vm.loader.prefetch(around, maxSide)
    }

    ImmersiveWhen(!chrome && current != null)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.folder == null -> Welcome(onOpen = { openFolder.launch(null) })
            current == null -> Finished(
                folder = state.folder,
                canUndo = state.canUndo,
                onUndo = vm::undo,
                onOpen = { openFolder.launch(vm.pickerStart()) },
            )
            else -> PhotoStage(
                photos = state.photos,
                index = state.index,
                loader = vm.loader,
                maxSide = maxSide,
                onStep = vm::step,
                onTap = { chrome = !chrome },
                onLoaded = { shown = it },
            )
        }

        if (state.folder != null) {
            AnimatedVisibility(
                visible = chrome || current == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopBar(
                    state = state,
                    shown = shown,
                    onUndo = vm::undo,
                    onShuffle = vm::shuffle,
                    onNameOrder = vm::sortByName,
                    onOpen = { openFolder.launch(vm.pickerStart()) },
                    onReload = vm::reload,
                )
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    data,
                    modifier = Modifier.padding(12.dp),
                    containerColor = if (lastError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
                    contentColor = if (lastError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary,
                )
            }
            if (state.folder != null && current != null) {
                AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut()) {
                    TargetBar(
                        targets = state.targets,
                        busy = state.busy,
                        onMove = vm::moveTo,
                        onChoose = chooseTarget,
                        onRemove = vm::removeTarget,
                    )
                }
            }
        }
    }
}

// -- screens ----------------------------------------------------------------

@Composable
private fun Welcome(onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Photos, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("VortexView", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(12.dp))
        Text(
            "Go through a folder of photos and move each one into a folder you choose, with one tap.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onOpen) {
            Icon(Icons.Folder, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open a folder of photos")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Your camera's photos are usually in DCIM › Camera.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Finished(folder: Folder?, canUndo: Boolean, onUndo: () -> Unit, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (canUndo) "All done" else "No photos here",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (canUndo) "No photos left in ${folder?.name.orEmpty()}." else "${folder?.name.orEmpty()} has no photos in it.",
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (canUndo) {
            OutlinedButton(onClick = onUndo) {
                Icon(Icons.Undo, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Put the last one back")
            }
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = onOpen) { Text("Open another folder") }
    }
}

// -- bars -------------------------------------------------------------------

private val BarColour = Color.Black.copy(alpha = 0.6f)
private val PanelColour = Color(0xFF1C1B24)

@Composable
private fun TopBar(
    state: UiState,
    shown: Decoded?,
    onUndo: () -> Unit,
    onShuffle: () -> Unit,
    onNameOrder: () -> Unit,
    onOpen: () -> Unit,
    onReload: () -> Unit,
) {
    val current = state.current
    var menu by remember { mutableStateOf(false) }
    Surface(color = BarColour, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        current?.name ?: state.folder?.name.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = buildList {
                        if (current != null) add("${state.index + 1} of ${state.photos.size}" + if (state.shuffled) " (shuffled)" else "")
                        if (shown != null) add("${shown.width} × ${shown.height}")
                        if (current != null && current.size >= 0) add(humanSize(current.size))
                        state.folder?.let { add(it.name) }
                    }
                    Text(
                        details.joinToString("  ·  "),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Lit up while the photos are shuffled; each tap deals a new order.
                IconButton(onClick = onShuffle, enabled = state.photos.size > 1) {
                    Icon(
                        Icons.Shuffle,
                        if (state.shuffled) "Shuffle again" else "Shuffle the order",
                        tint = if (state.shuffled) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = onUndo, enabled = state.canUndo) {
                    Icon(Icons.Undo, "Undo the last move", tint = if (state.canUndo) Color.White else Color.White.copy(alpha = 0.35f))
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.More, "More", tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Open another folder…") }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("Read the folder again") }, onClick = { menu = false; onReload() })
                        if (state.shuffled) {
                            DropdownMenuItem(text = { Text("Back to name order") }, onClick = { menu = false; onNameOrder() })
                        } else {
                            DropdownMenuItem(text = { Text("Shuffle the order") }, onClick = { menu = false; onShuffle() })
                        }
                    }
                }
            }
            // Only for slow work, such as copying onto an SD card.
            var slow by remember { mutableStateOf(false) }
            LaunchedEffect(state.busy) {
                slow = false
                if (state.busy) {
                    delay(400)
                    slow = true
                }
            }
            if (slow) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

/**
 * The panel at the bottom: a big button for each folder photos go into. Tap
 * one and the photo on screen moves there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetBar(
    targets: List<Folder>,
    busy: Boolean,
    onMove: (Int) -> Unit,
    onChoose: (Int?) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Surface(color = PanelColour, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 14.dp),
        ) {
            if (targets.isEmpty()) {
                Text(
                    "Where should photos go?",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                )
                Button(
                    onClick = { onChoose(null) },
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp).heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Folder, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Choose a folder to move photos into", textAlign = TextAlign.Center)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Move this photo to",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (targets.size < MAX_TARGETS) {
                        TextButton(onClick = { onChoose(null) }) {
                            Icon(Icons.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add folder")
                        }
                    }
                }
                // Every folder on screen at once: they wrap onto more rows rather than scroll away.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    targets.forEachIndexed { slot, folder ->
                        TargetButton(
                            slot = slot,
                            folder = folder,
                            enabled = !busy,
                            onMove = { onMove(slot) },
                            onChange = { onChoose(slot) },
                            onRemove = { onRemove(slot) },
                        )
                    }
                }
            }
        }
    }
}

/** Tap to move the photo here; press and hold to change or remove the folder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TargetButton(slot: Int, folder: Folder, enabled: Boolean, onMove: () -> Unit, onChange: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val first = slot == 0
    Box {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (first) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (first) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .heightIn(min = 52.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClickLabel = "Move the photo to ${folder.name}",
                    onLongClickLabel = "Change or remove this folder",
                    onClick = onMove,
                    onLongClick = { menu = true },
                ),
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.MoveTo, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Move photo here") }, onClick = { menu = false; onMove() })
            DropdownMenuItem(text = { Text("Change folder…") }, onClick = { menu = false; onChange() })
            DropdownMenuItem(text = { Text("Remove from the list") }, onClick = { menu = false; onRemove() })
        }
    }
}

// -- odds and ends ----------------------------------------------------------

/** Hides the status and navigation bars while the controls are hidden. */
@Composable
private fun ImmersiveWhen(hidden: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(hidden) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private fun humanSize(bytes: Long): String {
    var value = bytes.toDouble()
    for (unit in listOf("B", "KB", "MB", "GB")) {
        if (value < 1024 || unit == "GB") return if (unit == "B") "${value.toLong()} B" else "%.1f %s".format(value, unit)
        value /= 1024
    }
    return ""
}

package com.vortex.view

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vortex.view.core.Folder
import com.vortex.view.media.Decoded
import com.vortex.view.ui.ButtonKind
import com.vortex.view.ui.Icons
import com.vortex.view.ui.LocalVortex
import com.vortex.view.ui.MonoFont
import com.vortex.view.ui.PhotoStage
import com.vortex.view.ui.SectionHead
import com.vortex.view.ui.ThemePicker
import com.vortex.view.ui.VBadge
import com.vortex.view.ui.VLabel
import com.vortex.view.ui.VortexButton
import com.vortex.view.ui.VortexCard
import com.vortex.view.ui.VortexIconButton
import com.vortex.view.ui.VortexLogo
import com.vortex.view.ui.VortexMenu
import com.vortex.view.ui.VortexTheme
import com.vortex.view.ui.Wordmark
import com.vortex.view.ui.glassBar
import com.vortex.view.ui.vortexGround
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { VortexViewRoot(vm) }
    }
}

/** The app in the theme chosen in its theme picker. */
@Composable
fun VortexViewRoot(vm: MainViewModel) {
    VortexTheme(vm.palette) { VortexViewApp(vm) }
}

@Composable
fun VortexViewApp(vm: MainViewModel) {
    val v = LocalVortex.current
    val state = vm.state
    val snackbar = remember { SnackbarHostState() }
    var last by remember { mutableStateOf<Message?>(null) }
    LaunchedEffect(Unit) {
        // A newer message replaces the one showing, so a run of moves never queues up.
        vm.messages.collectLatest { message ->
            last = message
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
    var picking by remember { mutableStateOf(false) }
    if (picking) ThemePicker(vm.palette, onPick = vm::setTheme, onDismiss = { picking = false })

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

    SystemBars(light = v.light, hidden = !chrome && current != null)

    Box(Modifier.fillMaxSize().vortexGround(v).testTag("vortexview")) {
        when {
            state.folder == null -> Welcome(onOpen = { openFolder.launch(null) }, onTheme = { picking = true })
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
                    onTheme = { picking = true },
                )
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            SnackbarHost(snackbar) { data -> MessageCard(data, error = last?.error == true) }
            if (state.folder != null && current != null) {
                AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut()) {
                    TargetPanel(
                        targets = state.targets,
                        sent = state.sent,
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

/** The first screen: the brand, what it does in four numbered steps, and the way in. */
@Composable
private fun Welcome(onOpen: () -> Unit, onTheme: () -> Unit) {
    val v = LocalVortex.current
    Column(Modifier.fillMaxSize()) {
        BrandBar(onTheme)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp),
        ) {
            SectionHead("Sort your photos", Modifier.padding(start = 4.dp, top = 8.dp, bottom = 14.dp))
            VortexCard(Modifier.fillMaxWidth()) {
                Text(
                    "Go through a folder of photos and move each one where it belongs, with one tap.",
                    color = v.text,
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                )
                Spacer(Modifier.height(18.dp))
                val steps = listOf(
                    "Open a folder",
                    "Choose the folders they go into",
                    "Tap a folder to move the photo there",
                    "Swipe to skip; shuffle to mix them up",
                )
                steps.forEachIndexed { i, step -> StepRow(i + 1, step, first = i == 0) }
                Spacer(Modifier.height(22.dp))
                VortexButton(
                    "Open a folder of photos",
                    onClick = onOpen,
                    kind = ButtonKind.PRIMARY,
                    icon = Icons.Folder,
                    lit = true,
                    big = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Camera photos are usually in DCIM › Camera.",
                    color = v.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                VLabel("Theme", colour = v.muted)
                Spacer(Modifier.width(10.dp))
                VortexButton(v.name, onClick = onTheme, icon = Icons.Palette)
            }
        }
    }
}

/** One line of Tawla's lesson list: a mono number and a title; the first one lit. */
@Composable
private fun StepRow(number: Int, text: String, first: Boolean) {
    val v = LocalVortex.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(if (first) v.accentWash else Color.Transparent, shape)
            .border(1.dp, if (first) v.borderStrong else Color.Transparent, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("%02d".format(number), color = v.muted, fontFamily = MonoFont, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = if (first) v.accent2 else v.subtle, fontSize = 15.sp)
    }
}

/** The glass bar with the logo and wordmark, as across the top of Tawla. */
@Composable
private fun BrandBar(onTheme: () -> Unit) {
    val v = LocalVortex.current
    Row(
        Modifier
            .fillMaxWidth()
            .glassBar(v)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VortexLogo(30.dp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { Wordmark() }
        VortexIconButton(Icons.Palette, "Theme: ${v.name}", onClick = onTheme)
    }
}

@Composable
private fun Finished(folder: Folder?, canUndo: Boolean, onUndo: () -> Unit, onOpen: () -> Unit) {
    val v = LocalVortex.current
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        VortexCard(Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
            SectionHead(if (canUndo) "All done" else "No photos here")
            Spacer(Modifier.height(10.dp))
            Text(
                if (canUndo) "No photos left in ${folder?.name.orEmpty()}." else "${folder?.name.orEmpty()} has no photos in it.",
                color = v.text,
                fontSize = 17.sp,
            )
            Spacer(Modifier.height(20.dp))
            if (canUndo) {
                VortexButton("Put the last one back", onClick = onUndo, icon = Icons.Undo, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }
            VortexButton(
                "Open another folder",
                onClick = onOpen,
                kind = ButtonKind.PRIMARY,
                icon = Icons.Folder,
                lit = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// -- bars -------------------------------------------------------------------

@Composable
private fun TopBar(
    state: UiState,
    shown: Decoded?,
    onUndo: () -> Unit,
    onShuffle: () -> Unit,
    onNameOrder: () -> Unit,
    onOpen: () -> Unit,
    onReload: () -> Unit,
    onTheme: () -> Unit,
) {
    val v = LocalVortex.current
    val current = state.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().glassBar(v)) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VortexLogo(22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    current?.name ?: state.folder?.name.orEmpty(),
                    color = v.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current != null) {
                        // Mono, zero-padded, as Tawla prints its numbers.
                        val width = state.photos.size.toString().length
                        Text(
                            "%0${width}d / %d".format(state.index + 1, state.photos.size),
                            color = v.accent2,
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                        )
                        if (state.shuffled) {
                            Spacer(Modifier.width(6.dp))
                            VBadge("shuffled", v.accent)
                        }
                    }
                    val details = buildList {
                        if (shown != null) add("${shown.width} × ${shown.height}")
                        if (current != null && current.size >= 0) add(humanSize(current.size))
                    }
                    if (details.isNotEmpty()) {
                        Text(
                            "  " + details.joinToString(" · "),
                            color = v.muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // Lit up while the photos are shuffled; each tap deals a new order.
            VortexIconButton(
                Icons.Shuffle,
                if (state.shuffled) "Shuffle again" else "Shuffle the order",
                onClick = onShuffle,
                enabled = state.photos.size > 1,
                lit = state.shuffled,
            )
            VortexIconButton(Icons.Undo, "Undo the last move", onClick = onUndo, enabled = state.canUndo)
            Box {
                VortexIconButton(Icons.More, "More", onClick = { menu = true })
                VortexMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuItem("Open another folder…") { menu = false; onOpen() }
                    MenuItem("Read the folder again") { menu = false; onReload() }
                    if (state.shuffled) {
                        MenuItem("Back to name order") { menu = false; onNameOrder() }
                    } else {
                        MenuItem("Shuffle the order") { menu = false; onShuffle() }
                    }
                    MenuItem("Theme: ${v.name}…") { menu = false; onTheme() }
                }
            }
        }
        Progress(state)
    }
}

/**
 * How far through the folder you are, as a hairline in the brand gradient
 * under the bar; while slow work runs (a copy onto an SD card), it pulses.
 */
@Composable
private fun Progress(state: UiState) {
    val v = LocalVortex.current
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy) {
        slow = false
        if (state.busy) {
            delay(400)
            slow = true
        }
    }
    val total = state.photos.size
    val target = if (total == 0) 0f else (state.index + 1f) / total
    val fraction by animateFloatAsState(target, label = "progress")
    if (slow) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = v.accent, trackColor = v.border)
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .drawBehind {
                    drawRect(v.border)
                    drawRect(
                        Brush.horizontalGradient(listOf(v.brand1, v.brand2)),
                        size = size.copy(width = size.width * fraction),
                    )
                },
        )
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    val v = LocalVortex.current
    DropdownMenuItem(text = { Text(text, color = v.text, fontSize = 15.sp) }, onClick = onClick)
}

/**
 * The panel at the bottom: a big button for each folder photos go into. Tap
 * one and the photo on screen moves there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetPanel(
    targets: List<Folder>,
    sent: Map<String, Int>,
    busy: Boolean,
    onMove: (Int) -> Unit,
    onChoose: (Int?) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val v = LocalVortex.current
    Column(
        Modifier
            .fillMaxWidth()
            .glassBar(v, lineAtBottom = false)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
    ) {
        if (targets.isEmpty()) {
            SectionHead("Where should photos go?", Modifier.padding(bottom = 12.dp))
            VortexButton(
                "Choose a folder to move photos into",
                onClick = { onChoose(null) },
                kind = ButtonKind.PRIMARY,
                icon = Icons.Folder,
                lit = true,
                big = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                SectionHead("Move this photo to", Modifier.weight(1f))
                if (targets.size < MAX_TARGETS) {
                    VortexButton("Add folder", onClick = { onChoose(null) }, icon = Icons.Add)
                }
            }
            // Every folder on screen at once: they wrap onto more rows rather than scroll away.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                targets.forEachIndexed { slot, folder ->
                    TargetButton(
                        folder = folder,
                        count = sent[folder.id] ?: 0,
                        first = slot == 0,
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

/** Tap to move the photo here; press and hold to change or remove the folder. */
@Composable
private fun TargetButton(
    folder: Folder,
    count: Int,
    first: Boolean,
    enabled: Boolean,
    onMove: () -> Unit,
    onChange: () -> Unit,
    onRemove: () -> Unit,
) {
    val v = LocalVortex.current
    var menu by remember { mutableStateOf(false) }
    Box {
        VortexButton(
            folder.name,
            onClick = onMove,
            onLongClick = { menu = true },
            kind = if (first) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
            icon = Icons.MoveTo,
            lit = first,
            enabled = enabled,
            big = true,
            trailing = if (count > 0) ({ VBadge("$count", if (first) v.accent else v.accent2) }) else null,
        )
        VortexMenu(expanded = menu, onDismiss = { menu = false }) {
            MenuItem("Move photo here") { menu = false; onMove() }
            MenuItem("Change folder…") { menu = false; onChange() }
            MenuItem("Remove from the list") { menu = false; onRemove() }
        }
    }
}

/** The message after a move, as a Tawla card, its button an outlined Vortex one. */
@Composable
private fun MessageCard(data: SnackbarData, error: Boolean) {
    val v = LocalVortex.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
            .background(v.bg, shape)
            .background(v.surface2, shape)
            .border(1.dp, if (error) v.danger.copy(alpha = 0.6f) else v.borderStrong, shape)
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            data.visuals.message,
            color = if (error) v.danger else v.text,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        )
        val action = data.visuals.actionLabel
        if (action != null) {
            Spacer(Modifier.width(10.dp))
            VortexButton(action, onClick = { data.performAction() }, kind = ButtonKind.PRIMARY)
        }
    }
}

// -- odds and ends ----------------------------------------------------------

/**
 * Status-bar icons dark on Nova (the one light theme) and light on the rest;
 * both bars hidden while the controls are.
 */
@Composable
private fun SystemBars(light: Boolean, hidden: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity() ?: return
    SideEffect {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }
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

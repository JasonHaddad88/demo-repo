@file:OptIn(ExperimentalFoundationApi::class)

package com.vortex.explorer.ui

import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vortex.explorer.MainViewModel
import com.vortex.explorer.Screen
import com.vortex.explorer.core.Entry
import com.vortex.explorer.core.EntryKind
import com.vortex.explorer.media.ImageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun ViewerScreen(vm: MainViewModel, screen: Screen.Viewer) {
    var items by remember(screen) { mutableStateOf(screen.items) }
    val pager = rememberPagerState(
        initialPage = screen.start.coerceIn(0, (screen.items.size - 1).coerceAtLeast(0)),
    ) { items.size }
    var playing by remember(screen) { mutableStateOf(screen.autoplay) }
    var chrome by remember(screen) { mutableStateOf(!screen.autoplay) }
    var zoomed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()

    // A slideshow keeps the screen awake; hidden controls mean truly full screen.
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(chrome) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        if (controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chrome) controller.show(WindowInsetsCompat.Type.systemBars())
            else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    LaunchedEffect(pager.settledPage, items) {
        items.getOrNull(pager.settledPage)?.let { vm.viewed(it, pager.settledPage, screen.fromShuffle) }
        zoomed = false
    }

    // Each settled page restarts the timer, so a manual swipe gets a full interval too.
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        snapshotFlow { pager.settledPage }.collectLatest { page ->
            delay(vm.prefs.slideSeconds * 1000L)
            val next = page + 1
            when {
                next < items.size -> pager.animateScrollToPage(next)
                screen.fromShuffle && vm.prefs.loopSlideshow -> {
                    val round = vm.nextRound() ?: return@collectLatest
                    items = round.items
                    pager.scrollToPage(0)
                }
                else -> {
                    playing = false
                    chrome = true
                    vm.say("That was the last one")
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (items.isEmpty()) {
            Text("Nothing to show", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed,
                key = { index -> items.getOrNull(index)?.key ?: index },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val entry = items.getOrNull(page) ?: return@HorizontalPager
                if (entry.kind == EntryKind.IMAGE) {
                    ZoomableImage(
                        entry = entry,
                        images = vm.images,
                        onTap = { chrome = !chrome },
                        onZoomChanged = { zoomed = it },
                    )
                } else {
                    MediaPage(entry, vm.images, onTap = { chrome = !chrome }, onOpen = { vm.openWith(entry) })
                }
            }
        }

        if (chrome && items.isNotEmpty()) {
            val current = items.getOrNull(pager.currentPage)
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                Row(
                    Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            current?.name.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${pager.currentPage + 1} of ${items.size}" +
                                (current?.let { vm.source(it.sourceId)?.displayName }?.let { " · $it" } ?: ""),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (current != null) {
                        IconButton(onClick = { vm.openWith(current) }) {
                            Icon(Icons.Filled.Share, "Open with another app", tint = Color.White)
                        }
                    }
                }
            }

            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Row(
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", tint = Color.White)
                    }
                    FilledIconButton(
                        onClick = {
                            playing = !playing
                            if (playing) chrome = false
                        },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(if (playing) VortexIcons.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play slideshow")
                    }
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(items.size - 1)) } }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", tint = Color.White)
                    }
                    if (screen.fromShuffle) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val round = vm.nextRound() ?: return@launch
                                    items = round.items
                                    pager.scrollToPage(0)
                                    vm.say("New order · seed ${round.seed}")
                                }
                            },
                        ) {
                            Icon(VortexIcons.Shuffle, "Shuffle again", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

private data class Loaded(val bitmap: ImageBitmap?, val loading: Boolean)

/** A photo that fits the screen; double-tap to zoom, then drag or pinch. */
@Composable
private fun ZoomableImage(
    entry: Entry,
    images: ImageLoader,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val maxSide = with(density) {
        maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx()
    }.coerceIn(1024, 4096)

    // Show the cached thumbnail at once, then swap in the full picture.
    val loaded by produceState(Loaded(images.cached(entry, THUMB_PX), loading = true), entry.key) {
        val full = runCatching { images.full(entry, maxSide) }.getOrNull()
        value = Loaded(full ?: value.bitmap, loading = false)
    }
    var scale by remember(entry.key) { mutableFloatStateOf(1f) }
    var offset by remember(entry.key) { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(entry.key) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                            onZoomChanged(false)
                        } else {
                            scale = 2.5f
                            onZoomChanged(true)
                        }
                    },
                )
            }
            .then(
                if (scale > 1f) {
                    Modifier.pointerInput(entry.key) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            if (scale <= 1f) {
                                offset = Offset.Zero
                                onZoomChanged(false)
                            } else {
                                offset += pan
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = loaded.bitmap
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = entry.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
            loaded.loading -> CircularProgressIndicator(color = Color.White)
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(VortexIcons.Photo, null, tint = Color.White, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Can't show ${entry.name}",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}

/** Videos, music and everything else: a preview and a button to open it elsewhere. */
@Composable
private fun MediaPage(entry: Entry, images: ImageLoader, onTap: () -> Unit, onOpen: () -> Unit) {
    Box(
        Modifier.fillMaxSize().pointerInput(entry.key) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Thumbnail(entry, images, Modifier.size(220.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                entry.name,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpen) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    when (entry.kind) {
                        EntryKind.VIDEO -> "Play video"
                        EntryKind.AUDIO -> "Play"
                        else -> "Open with…"
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Opens in your video or music app",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

package com.vortex.view.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vortex.view.core.Photo
import com.vortex.view.core.Region
import com.vortex.view.core.Viewport
import com.vortex.view.media.Decoded
import com.vortex.view.media.Detail
import com.vortex.view.media.PhotoLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How the photo on screen is zoomed. A new one is made for every photo, so each starts fitted. */
@Stable
class ZoomState {
    var viewport: Viewport? = null
        private set

    /** Bumped on every zoom or pan, so the photo is redrawn. */
    var version by mutableIntStateOf(0)
        private set

    /** Full-resolution pixels for the part on screen, once zoomed past the screen-sized copy. */
    var detail by mutableStateOf<Detail?>(null)

    val zoomed: Boolean
        get() {
            version // read, so whoever asks is told when it changes
            return viewport?.zoomed == true
        }

    fun attach(decoded: Decoded): Viewport {
        viewport?.let { if (it.imageWidth.toInt() == decoded.width && it.imageHeight.toInt() == decoded.height) return it }
        return Viewport(decoded.width, decoded.height).also { viewport = it }
    }

    fun changed() {
        version++
    }

    fun reset() {
        viewport?.reset()
        detail = null
        changed()
    }
}

/**
 * The photo you're on, with its neighbours waiting either side: swipe to go
 * to the next or previous one, pinch or double-tap to zoom, drag to look
 * around a zoomed photo, tap to show or hide the controls.
 */
@Composable
fun PhotoStage(
    photos: List<Photo>,
    index: Int,
    loader: PhotoLoader,
    maxSide: Int,
    onStep: (Int) -> Boolean,
    onTap: () -> Unit,
    onLoaded: (Decoded?) -> Unit,
) {
    val current = photos.getOrNull(index) ?: return
    val previous = photos.getOrNull(index - 1)
    val next = photos.getOrNull(index + 1)
    val zoom = remember(current.id) { ZoomState() }
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableFloatStateOf(1f) }

    BackHandler(enabled = zoom.zoomed) { zoom.reset() }

    // The gesture loops outlive recompositions; they read the latest of these.
    val ends by rememberUpdatedState(Pair(previous != null, next != null))
    val step by rememberUpdatedState(onStep)
    val tap by rememberUpdatedState(onTap)

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag("photo")
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(current.id) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { point ->
                        zoom.viewport?.let {
                            it.toggle(point.x.toDouble(), point.y.toDouble())
                            zoom.changed()
                        }
                    },
                )
            }
            .pointerInput(current.id) {
                photoGestures(zoom, swipe, scope, ends = { ends }, step = { direction -> step(direction) })
            },
    ) {
        // Each photo gets its own slot, keyed by the photo: a slot never shows
        // a photo other than its own, however the list moves underneath it.
        val offset = swipe.value
        if (offset > 0f && previous != null) {
            key(previous.id) {
                PhotoView(previous, loader, maxSide, null, Modifier.graphicsLayer { translationX = swipe.value - width })
            }
        }
        if (offset < 0f && next != null) {
            key(next.id) {
                PhotoView(next, loader, maxSide, null, Modifier.graphicsLayer { translationX = swipe.value + width })
            }
        }
        key(current.id) {
            PhotoView(current, loader, maxSide, zoom, Modifier.graphicsLayer { translationX = swipe.value }, onLoaded)
        }
    }
}

private suspend fun PointerInputScope.photoGestures(
    zoom: ZoomState,
    swipe: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    ends: () -> Pair<Boolean, Boolean>,
    step: (Int) -> Boolean,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var travel = Offset.Zero
        var pastSlop = false
        var pinched = false
        var swiping = false
        var dragX = 0f

        do {
            val event = awaitPointerEvent()
            val fingers = event.changes.count { it.pressed }
            if (fingers >= 2) pinched = true
            val zoomChange = event.calculateZoom()
            val pan = event.calculatePan()
            if (!pastSlop) {
                travel += pan
                pastSlop = abs(zoomChange - 1f) > 0.02f || travel.getDistance() > viewConfiguration.touchSlop
            }
            val viewport = zoom.viewport
            if (pastSlop && fingers > 0) {
                if (viewport != null && !swiping && (pinched || viewport.zoomed)) {
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (zoomChange != 1f && centroid != Offset.Unspecified) {
                        viewport.zoomBy(zoomChange.toDouble(), centroid.x.toDouble(), centroid.y.toDouble())
                    }
                    viewport.pan(pan.x.toDouble(), pan.y.toDouble())
                    zoom.changed()
                } else if (!pinched) {
                    swiping = true
                    dragX += pan.x
                    val (hasPrevious, hasNext) = ends()
                    // At either end the photo gives a little, and springs back.
                    val shown = if ((dragX > 0 && !hasPrevious) || (dragX < 0 && !hasNext)) dragX / 4 else dragX
                    scope.launch { swipe.snapTo(shown) }
                }
                event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
            }
            event.changes.firstOrNull { it.id == down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
        } while (event.changes.any { it.pressed })

        if (swiping) {
            val velocity = tracker.calculateVelocity().x
            val (hasPrevious, hasNext) = ends()
            val far = size.width * 0.25f
            val direction = when {
                dragX < 0 && hasNext && (dragX < -far || velocity < -FLING) -> 1
                dragX > 0 && hasPrevious && (dragX > far || velocity > FLING) -> -1
                else -> 0
            }
            scope.launch {
                if (direction == 0) {
                    swipe.animateTo(0f, spring())
                } else {
                    // Slide the next photo fully into place, then make it the current one.
                    swipe.animateTo(-direction * size.width.toFloat(), tween(durationMillis = 160))
                    if (step(direction)) swipe.snapTo(0f) else swipe.animateTo(0f, spring())
                }
            }
        }
    }
}

private const val FLING = 1200f // pixels a second

private sealed interface Loading {
    data object Waiting : Loading
    data class Ready(val decoded: Decoded) : Loading
    data class Failed(val reason: String) : Loading
}

@Composable
private fun PhotoView(
    photo: Photo,
    loader: PhotoLoader,
    maxSide: Int,
    zoom: ZoomState?,
    modifier: Modifier,
    onLoaded: (Decoded?) -> Unit = {},
) {
    // Keyed state: a different photo always starts from its own pixels, never the last one's.
    var result by remember(photo.id, maxSide) {
        mutableStateOf<Loading>(loader.cached(photo)?.let { Loading.Ready(it) } ?: Loading.Waiting)
    }
    LaunchedEffect(photo.id, maxSide) {
        if (result is Loading.Ready) return@LaunchedEffect
        result = try {
            Loading.Ready(loader.load(photo, maxSide))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Loading.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    LaunchedEffect(result) { onLoaded((result as? Loading.Ready)?.decoded) }
    val v = LocalVortex.current

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val loaded = result) {
            Loading.Waiting -> {
                // Only for slow photos: a quick one would just flash a spinner.
                var show by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(300)
                    show = true
                }
                if (show) CircularProgressIndicator(color = v.accent)
            }
            is Loading.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Photos, null, tint = v.muted, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Can't show ${photo.name}",
                    color = v.text,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${loaded.reason}\nYou can still move it, or swipe past it.",
                    color = v.muted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            is Loading.Ready -> PhotoCanvas(photo, loaded.decoded, loader, zoom)
        }
    }
}

@Composable
private fun PhotoCanvas(photo: Photo, decoded: Decoded, loader: PhotoLoader, zoom: ZoomState?) {
    val viewport = remember(decoded, zoom) { zoom?.attach(decoded) ?: Viewport(decoded.width, decoded.height) }

    if (zoom != null) {
        // Once zoomed in and settled, fetch full-resolution pixels for what's on screen.
        LaunchedEffect(decoded, zoom) {
            snapshotFlow { zoom.version }.collectLatest {
                delay(140)
                val wanted = detailWanted(viewport, decoded, zoom.detail)
                when {
                    wanted == null -> if (!viewport.zoomed) zoom.detail = null
                    else -> loader.detail(photo, decoded, wanted.first, wanted.second)?.let { zoom.detail = it }
                }
            }
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        zoom?.version // redraw on every zoom and pan
        viewport.resize(size.width, size.height)
        val scale = viewport.scale
        drawImage(
            decoded.bitmap,
            dstOffset = IntOffset(viewport.viewX(0.0).roundToInt(), viewport.viewY(0.0).roundToInt()),
            dstSize = IntSize(
                (viewport.imageWidth * scale).roundToInt().coerceAtLeast(1),
                (viewport.imageHeight * scale).roundToInt().coerceAtLeast(1),
            ),
            filterQuality = FilterQuality.High,
        )
        val detail = zoom?.detail
        if (detail != null && viewport.zoomed) {
            val region = detail.region
            drawImage(
                detail.bitmap,
                dstOffset = IntOffset(viewport.viewX(region.left).roundToInt(), viewport.viewY(region.top).roundToInt()),
                dstSize = IntSize(
                    (region.width * scale).roundToInt().coerceAtLeast(1),
                    (region.height * scale).roundToInt().coerceAtLeast(1),
                ),
                // Deep in, show the real pixels as crisp squares rather than a blur.
                filterQuality = if (scale * detail.sample >= 3) FilterQuality.None else FilterQuality.Low,
            )
        }
    }
}

/**
 * The region and shrink factor worth decoding for the view as it is now, or
 * null when the screen-sized copy is already sharp enough or [held] covers it.
 */
private fun detailWanted(viewport: Viewport, decoded: Decoded, held: Detail?): Pair<Region, Int>? {
    if (!viewport.zoomed) return null
    val sharpUpTo = decoded.bitmap.width / viewport.imageWidth
    if (viewport.scale <= sharpUpTo * 1.15) return null
    var sample = 1
    while (sample * 2 * viewport.scale <= 1.0) sample *= 2
    val visible = viewport.visible()
    if (held != null && held.sample == sample && held.region.contains(visible)) return null
    // A margin, so a small drag doesn't need a new decode.
    val marginX = visible.width * 0.15
    val marginY = visible.height * 0.15
    return Region(visible.left - marginX, visible.top - marginY, visible.right + marginX, visible.bottom + marginY) to sample
}

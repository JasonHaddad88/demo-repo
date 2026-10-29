package com.vortex.view.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.hypot

/*
 * The Vortex family's components, after VortexTawla's vortex.css: haloed
 * ground, glass bars, the gradient logo, letter-spaced uppercase headings,
 * outlined buttons that glow, and bordered cards.
 */

val MonoFont = FontFamily.Monospace

// -- ground -------------------------------------------------------------------

/**
 * The page ground: the theme's colour, its texture if it has one, and two soft
 * haloes, top left and bottom right, as Tawla's body background.
 */
fun Modifier.vortexGround(v: VortexPalette): Modifier = drawWithCache {
    val reach = hypot(size.width, size.height)
    val halo1 = Brush.radialGradient(0f to v.halo1, 0.6f to Color.Transparent, center = Offset.Zero, radius = reach)
    val halo2 = Brush.radialGradient(
        0f to v.halo2, 0.6f to Color.Transparent,
        center = Offset(size.width, size.height), radius = reach,
    )
    val carbon = if (v.texture == Texture.CARBON) carbonBrush(34.dp.toPx(), this, layoutDirection) else null
    val leafTile = 520.dp.toPx()
    onDrawBehind {
        drawRect(v.bg)
        if (carbon != null) drawRect(carbon)
        if (v.texture == Texture.LEAVES) drawLeaves(v.leafInk, leafTile)
        drawRect(halo1)
        drawRect(halo2)
    }
}

/** GTX's 2/2 twill carbon weave, from Tawla's carbon.svg: one tile, repeated. */
private fun carbonBrush(tile: Float, density: Density, layoutDirection: LayoutDirection): ShaderBrush {
    val px = tile.toInt().coerceAtLeast(8)
    val image = ImageBitmap(px, px)
    val half = px / 2f
    val dark = Color(0xFF0A0B0D)
    val sheen = Color(0xFF2C3138)
    CanvasDrawScope().draw(density, layoutDirection, androidx.compose.ui.graphics.Canvas(image), Size(px.toFloat(), px.toFloat())) {
        drawRect(Color(0xFF141619))
        // Bundles run vertically on one diagonal and horizontally on the other,
        // bright down the middle of each bundle, which is where the sheen comes from.
        for ((x, y) in listOf(0f to 0f, half to half)) {
            drawRect(
                Brush.horizontalGradient(listOf(dark, sheen, dark), startX = x, endX = x + half),
                topLeft = Offset(x, y), size = Size(half, half),
            )
        }
        for ((x, y) in listOf(half to 0f, 0f to half)) {
            drawRect(
                Brush.verticalGradient(listOf(dark, sheen, dark), startY = y, endY = y + half),
                topLeft = Offset(x, y), size = Size(half, half),
            )
        }
        val seam = Color.Black.copy(alpha = 0.45f)
        drawLine(seam, Offset(half, 0f), Offset(half, px.toFloat()))
        drawLine(seam, Offset(0f, half), Offset(px.toFloat(), half))
        drawLine(seam, Offset(0f, 0f), Offset(px.toFloat(), 0f))
        drawLine(seam, Offset(0f, 0f), Offset(0f, px.toFloat()))
    }
    return ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated))
}

/** Bamboo's ghost leaves, from Tawla's leaves.svg: (x, y, degrees, scale, opacity) on a 560 grid. */
private val LEAVES = listOf(
    floatArrayOf(48f, 86f, -24f, 1.15f, .85f), floatArrayOf(96f, 150f, 18f, .8f, .55f),
    floatArrayOf(210f, 52f, 41f, .95f, .7f), floatArrayOf(330f, 120f, -12f, 1.3f, .6f),
    floatArrayOf(430f, 38f, 63f, .75f, .8f), floatArrayOf(150f, 268f, -52f, 1.05f, .65f),
    floatArrayOf(268f, 318f, 9f, .88f, .8f), floatArrayOf(392f, 262f, -38f, 1.2f, .55f),
    floatArrayOf(62f, 392f, 27f, .92f, .75f), floatArrayOf(196f, 462f, -16f, 1.1f, .6f),
    floatArrayOf(340f, 432f, 48f, .82f, .8f), floatArrayOf(462f, 368f, -6f, 1.0f, .65f),
    floatArrayOf(470f, 494f, 33f, .9f, .7f), floatArrayOf(-30f, 220f, -8f, 1.1f, .6f),
    floatArrayOf(530f, 220f, -8f, 1.1f, .6f), floatArrayOf(250f, -26f, 72f, .95f, .7f),
    floatArrayOf(250f, 534f, 72f, .95f, .7f),
)

private val LeafPath = Path().apply {
    // A lanceolate bamboo leaf, about 72 long, tip to the right.
    moveTo(0f, 0f)
    cubicTo(20f, -9f, 46f, -6f, 72f, 7f)
    cubicTo(46f, 21f, 19f, 17f, 0f, 0f)
    close()
}

private fun DrawScope.drawLeaves(ink: Color, tile: Float) {
    val unit = tile / 560f
    var ty = 0f
    while (ty < size.height) {
        var tx = 0f
        while (tx < size.width) {
            for (leaf in LEAVES) {
                translate(tx + leaf[0] * unit, ty + leaf[1] * unit) {
                    rotate(leaf[2], pivot = Offset.Zero) {
                        scale(leaf[3] * unit, pivot = Offset.Zero) {
                            drawPath(LeafPath, ink.copy(alpha = ink.alpha * leaf[4]))
                        }
                    }
                }
            }
            tx += tile
        }
        ty += tile
    }
}

/** The glass of Tawla's top bar: translucent, with a hairline on the inner edge. */
fun Modifier.glassBar(v: VortexPalette, lineAtBottom: Boolean = true): Modifier = this
    .background(v.glass)
    .drawBehind {
        val y = if (lineAtBottom) size.height - 0.5f else 0.5f
        drawLine(v.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
    }

/**
 * A soft neon bloom around a rounded rectangle, as Tawla's `box-shadow: 0 0
 * 16px var(--glow)`: rings that fade outward. Drawn rather than blurred, so it
 * looks the same on every Android version.
 */
fun Modifier.glow(colour: Color, corner: Dp = 8.dp, reach: Dp = 12.dp, on: Boolean = true): Modifier =
    if (!on) this else drawBehind {
        val steps = 8
        val step = reach.toPx() / steps
        val radius = corner.toPx()
        for (i in 1..steps) {
            val grow = i * step
            val fade = 1f - i / (steps + 1f)
            drawRoundRect(
                colour.copy(alpha = colour.alpha * fade * fade * 0.45f),
                topLeft = Offset(-grow, -grow),
                size = Size(size.width + 2 * grow, size.height + 2 * grow),
                cornerRadius = CornerRadius(radius + grow),
                style = Stroke(step * 1.4f),
            )
        }
    }

// -- brand --------------------------------------------------------------------

/** The Vortex mark: a gradient square with a dark core, glowing. */
@Composable
fun VortexLogo(size: Dp = 30.dp, palette: VortexPalette = LocalVortex.current, glow: Boolean = true) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        if (glow) {
            drawCircle(
                Brush.radialGradient(listOf(palette.accentGlow, Color.Transparent), center = center, radius = s * 1.05f),
                radius = s * 1.05f,
            )
        }
        drawRoundRect(
            Brush.linearGradient(listOf(palette.brand1, palette.brand2), start = Offset.Zero, end = Offset(s, s)),
            cornerRadius = CornerRadius(s * 8 / 30),
        )
        val inset = s * 5 / 30
        drawRoundRect(
            palette.logoCore,
            topLeft = Offset(inset, inset),
            size = Size(s - 2 * inset, s - 2 * inset),
            cornerRadius = CornerRadius(s * 4 / 30),
        )
    }
}

/** "VORTEX VIEW", the second word in the brand gradient. */
@OptIn(ExperimentalTextApi::class)
@Composable
fun Wordmark(fontSize: TextUnit = 16.sp) {
    val v = LocalVortex.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "VORTEX",
            color = v.text,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.12.em,
        )
        Spacer(Modifier.width(fontSize.value.dp * 0.45f))
        Text(
            "VIEW",
            style = TextStyle(
                brush = Brush.linearGradient(listOf(v.brand1, v.brand2)),
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.12.em,
            ),
        )
    }
}

// -- type -----------------------------------------------------------------------

/** Tawla's `.section-head h2`: small, wide-spaced capitals in the muted ink. */
@Composable
fun SectionHead(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = LocalVortex.current.muted,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.18.em,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Tawla's `.label`. */
@Composable
fun VLabel(text: String, modifier: Modifier = Modifier, colour: Color = LocalVortex.current.subtle) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = colour,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.em,
    )
}

// -- surfaces -------------------------------------------------------------------

/** Tawla's `.card`: a bordered panel, a shade lighter at the top. */
@Composable
fun VortexCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val v = LocalVortex.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .background(Brush.verticalGradient(listOf(v.surface, v.surface2)), shape)
            .border(1.dp, v.border, shape)
            .padding(padding),
        content = content,
    )
}

/** Tawla's `.badge`: a small pill in a status colour. */
@Composable
fun VBadge(text: String, colour: Color = LocalVortex.current.accent2, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text.uppercase(),
        modifier = modifier
            .background(colour.copy(alpha = 0.10f), shape)
            .border(1.dp, colour.copy(alpha = 0.35f), shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = colour,
        fontSize = 10.sp,
        fontFamily = MonoFont,
        letterSpacing = 0.08.em,
        maxLines = 1,
    )
}

// -- buttons --------------------------------------------------------------------

enum class ButtonKind { PRIMARY, SECONDARY, DANGER }

/**
 * Tawla's `.btn`: transparent, outlined, wide-spaced capitals. Pressed (or
 * [lit], for the one that matters most) it fills with a wash of its colour,
 * the border goes solid and it glows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VortexButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    icon: ImageVector? = null,
    lit: Boolean = false,
    enabled: Boolean = true,
    big: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val v = LocalVortex.current
    val (ink, wash, bloom) = when (kind) {
        ButtonKind.PRIMARY -> Triple(v.accent, v.accentWash, v.accentGlow)
        ButtonKind.SECONDARY -> Triple(v.accent2, v.accent2Wash, v.accent2Glow)
        ButtonKind.DANGER -> Triple(v.danger, v.danger.copy(alpha = 0.12f), v.danger.copy(alpha = 0.35f))
    }
    val rest = if (kind == ButtonKind.DANGER) v.danger.copy(alpha = 0.35f) else v.borderStrong
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val on = enabled && (pressed || lit)
    val corner = if (big) 12.dp else 8.dp
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.35f)
            .glow(bloom, corner = corner, on = on)
            .background(if (on) wash else Color.Transparent, shape)
            .border(1.dp, if (on) ink else rest, shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .heightIn(min = if (big) 54.dp else 40.dp)
            .padding(horizontal = if (big) 18.dp else 15.dp, vertical = if (big) 12.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(if (big) 18.dp else 16.dp))
                Spacer(Modifier.width(if (big) 10.dp else 8.dp))
            }
            Text(
                text.uppercase(),
                color = ink,
                fontSize = if (big) 14.sp else 12.sp,
                fontWeight = if (big) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.1.em,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
    }
}

/** An icon-only control for the glass bar: outlined square, glows when [lit]. */
@Composable
fun VortexIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    lit: Boolean = false,
) {
    val v = LocalVortex.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val on = enabled && (pressed || lit)
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .padding(3.dp)
            .size(40.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .glow(v.accentGlow, on = on)
            .background(if (on) v.accentWash else Color.Transparent, shape)
            .border(1.dp, if (on) v.accent else v.border, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (lit) v.accent else v.text, modifier = Modifier.size(20.dp))
    }
}

/** A dropdown menu dressed as a Tawla card. */
@Composable
fun VortexMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val v = LocalVortex.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(0.dp, 6.dp),
        shape = RoundedCornerShape(10.dp),
        containerColor = v.surface2.compositeOverBg(v),
        border = BorderStroke(1.dp, v.borderStrong),
        content = content,
    )
}

private fun Color.compositeOverBg(v: VortexPalette): Color = compositeOver(v.bg)

// -- the theme chooser -----------------------------------------------------------

/** Tawla's theme select, as a list in the style of its lesson list. */
@Composable
fun ThemePicker(current: VortexPalette, onPick: (VortexPalette) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val v = LocalVortex.current
        VortexCard(
            Modifier
                .fillMaxWidth()
                .background(v.bg.copy(alpha = 0.92f), RoundedCornerShape(12.dp)),
            padding = PaddingValues(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 12.dp),
        ) {
            SectionHead("Theme", Modifier.padding(start = 8.dp, bottom = 10.dp))
            Themes.all.forEachIndexed { number, theme ->
                val chosen = theme.id == current.id
                val shape = RoundedCornerShape(8.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .background(if (chosen) v.accentWash else Color.Transparent, shape)
                        .border(1.dp, if (chosen) v.borderStrong else Color.Transparent, shape)
                        .clickable(role = Role.RadioButton) { onPick(theme) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%02d".format(number + 1), color = v.muted, fontFamily = MonoFont, fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                    VortexLogo(size = 26.dp, palette = theme, glow = false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            theme.name,
                            color = if (chosen) v.accent2 else v.text,
                            fontSize = 15.sp,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(theme.tagline, color = v.muted, fontSize = 12.sp, maxLines = 2)
                    }
                }
            }
            Spacer(Modifier.size(10.dp))
            VortexButton("Done", onClick = onDismiss, kind = ButtonKind.PRIMARY, modifier = Modifier.align(Alignment.End))
        }
    }
}

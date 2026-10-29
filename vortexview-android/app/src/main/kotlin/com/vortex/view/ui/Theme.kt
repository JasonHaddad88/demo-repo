package com.vortex.view.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.roundToInt

/**
 * The Vortex family's colour tokens, the same contract as VortexTawla's
 * assets/css/themes.css: every colour in the app comes from one of these,
 * so a theme changes the whole product at once.
 */
@Immutable
data class VortexPalette(
    val id: String,
    val name: String,
    val tagline: String,
    val light: Boolean,
    // ground
    val bg: Color,
    val halo1: Color,
    val halo2: Color,
    val surface: Color,
    val surface2: Color,
    val glass: Color,
    // ink
    val text: Color,
    val muted: Color,
    val subtle: Color,
    // line
    val border: Color,
    val borderStrong: Color,
    // accents
    val accent: Color,
    val accentGlow: Color,
    val accentWash: Color,
    val accentTint: Color,
    val accent2: Color,
    val accent2Glow: Color,
    val accent2Wash: Color,
    // brand
    val brand1: Color,
    val brand2: Color,
    val logoCore: Color,
    // status
    val danger: Color,
    val success: Color,
    val warning: Color,
    val texture: Texture = Texture.NONE,
    /** Bamboo's ghost leaves are painted in this. */
    val leafInk: Color = Color.Transparent,
)

/** Patterns some themes lay under the haloes, as Tawla does with its SVG tiles. */
enum class Texture { NONE, CARBON, LEAVES }

private fun hex(value: Long) = Color(0xFF000000 or value)

private fun rgba(r: Int, g: Int, b: Int, a: Double) = Color(r, g, b, (a * 255).roundToInt())

object Themes {
    // Values copied from VortexTawla's themes.css, token for token.

    val Vortex = VortexPalette(
        id = "vortex", name = "Vortex", tagline = "The original. Black, purple and cyan, neon glow.", light = false,
        bg = hex(0x06060a), halo1 = rgba(168, 85, 247, 0.10), halo2 = rgba(103, 232, 249, 0.08),
        surface = hex(0x0e0e18), surface2 = hex(0x14141f), glass = rgba(14, 14, 24, 0.72),
        text = hex(0xe2e8f0), muted = hex(0x7c8492), subtle = hex(0x9ca3af),
        border = rgba(168, 85, 247, 0.18), borderStrong = rgba(168, 85, 247, 0.45),
        accent = hex(0xa855f7), accentGlow = rgba(168, 85, 247, 0.45), accentWash = rgba(168, 85, 247, 0.10),
        accentTint = rgba(168, 85, 247, 0.22),
        accent2 = hex(0x67e8f9), accent2Glow = rgba(103, 232, 249, 0.40), accent2Wash = rgba(103, 232, 249, 0.10),
        brand1 = hex(0xa855f7), brand2 = hex(0x67e8f9), logoCore = hex(0x06060a),
        danger = hex(0xef4444), success = hex(0x34d399), warning = hex(0xfbbf24),
    )

    val Nova = VortexPalette(
        id = "nova", name = "Nova", tagline = "Brushed silver under glass, lit from the front.", light = true,
        bg = hex(0xdfe4ec), halo1 = rgba(255, 255, 255, 0.85), halo2 = rgba(120, 180, 255, 0.22),
        surface = hex(0xf2f5f9), surface2 = hex(0xe6ebf2), glass = rgba(246, 249, 253, 0.72),
        text = hex(0x131722), muted = hex(0x4d5768), subtle = hex(0x333c4b),
        border = rgba(60, 140, 235, 0.30), borderStrong = rgba(60, 140, 235, 0.62),
        accent = hex(0x2f7fd8), accentGlow = rgba(80, 165, 255, 0.55), accentWash = rgba(80, 165, 255, 0.14),
        accentTint = rgba(80, 165, 255, 0.26),
        accent2 = hex(0x1d6fbe), accent2Glow = rgba(120, 195, 255, 0.55), accent2Wash = rgba(120, 195, 255, 0.16),
        brand1 = hex(0x8fa3bd), brand2 = hex(0x4da3ff), logoCore = hex(0xeef2f7),
        danger = hex(0xc02626), success = hex(0x10714d), warning = hex(0x8a5a00),
    )

    val Atrium = VortexPalette(
        id = "atrium", name = "Atrium", tagline = "Smoked glass lit from behind, in electric cyan.", light = false,
        bg = hex(0x0b0e13), halo1 = rgba(90, 170, 255, 0.22), halo2 = rgba(30, 48, 74, 0.65),
        surface = rgba(38, 48, 62, 0.55), surface2 = rgba(24, 31, 41, 0.62), glass = rgba(16, 21, 28, 0.55),
        text = hex(0xeaf2fb), muted = hex(0x93a3b8), subtle = hex(0xbccbde),
        border = rgba(120, 190, 255, 0.22), borderStrong = rgba(120, 190, 255, 0.58),
        accent = hex(0x58c8ff), accentGlow = rgba(88, 200, 255, 0.55), accentWash = rgba(88, 200, 255, 0.12),
        accentTint = rgba(88, 200, 255, 0.24),
        accent2 = hex(0x8fe3ff), accent2Glow = rgba(143, 227, 255, 0.50), accent2Wash = rgba(143, 227, 255, 0.12),
        brand1 = hex(0x9fb4cc), brand2 = hex(0x58c8ff), logoCore = hex(0x0b0e13),
        danger = hex(0xff7a6e), success = hex(0x5ce0a8), warning = hex(0xffd166),
    )

    val Gtx = VortexPalette(
        id = "gtx", name = "GTX", tagline = "Carbon fibre, gloss and racing red.", light = false,
        bg = hex(0x08090b), halo1 = rgba(255, 47, 47, 0.16), halo2 = rgba(255, 255, 255, 0.04),
        surface = rgba(23, 25, 29, 0.86), surface2 = rgba(15, 17, 20, 0.92), glass = rgba(10, 11, 13, 0.72),
        text = hex(0xf5f7fa), muted = hex(0x9aa3ad), subtle = hex(0xccd3da),
        border = rgba(255, 47, 47, 0.34), borderStrong = rgba(255, 47, 47, 0.78),
        accent = hex(0xff2f2f), accentGlow = rgba(255, 47, 47, 0.55), accentWash = rgba(255, 47, 47, 0.12),
        accentTint = rgba(255, 47, 47, 0.24),
        accent2 = hex(0xd7dde3), accent2Glow = rgba(215, 221, 227, 0.40), accent2Wash = rgba(215, 221, 227, 0.10),
        brand1 = hex(0xff2f2f), brand2 = hex(0xd7dde3), logoCore = hex(0x08090b),
        danger = hex(0xff5c5c), success = hex(0x4ade80), warning = hex(0xfbbf24),
        texture = Texture.CARBON,
    )

    val Mono = VortexPalette(
        id = "mono", name = "Mono", tagline = "No colour at all: black, white and grey.", light = false,
        bg = hex(0x000000), halo1 = rgba(255, 255, 255, 0.05), halo2 = rgba(255, 255, 255, 0.03),
        surface = hex(0x0a0a0a), surface2 = hex(0x131313), glass = rgba(10, 10, 10, 0.80),
        text = hex(0xffffff), muted = hex(0x9a9a9a), subtle = hex(0xc9c9c9),
        border = rgba(255, 255, 255, 0.20), borderStrong = rgba(255, 255, 255, 0.55),
        accent = hex(0xffffff), accentGlow = rgba(255, 255, 255, 0.30), accentWash = rgba(255, 255, 255, 0.10),
        accentTint = rgba(255, 255, 255, 0.20),
        accent2 = hex(0xd0d0d0), accent2Glow = rgba(255, 255, 255, 0.26), accent2Wash = rgba(255, 255, 255, 0.08),
        brand1 = hex(0xffffff), brand2 = hex(0x7a7a7a), logoCore = hex(0x000000),
        danger = hex(0xff8f8f), success = hex(0xb7f0cf), warning = hex(0xf0dfa8),
    )

    val Bamboo = VortexPalette(
        id = "bamboo", name = "Bamboo", tagline = "A grove: leaf green and pale culm, ghost leaves behind.", light = false,
        bg = hex(0x0c1810), halo1 = rgba(126, 200, 116, 0.14), halo2 = rgba(26, 74, 40, 0.45),
        surface = hex(0x16281a), surface2 = hex(0x1b3120), glass = rgba(18, 41, 26, 0.78),
        text = hex(0xedf7e8), muted = hex(0x9ab894), subtle = hex(0xcfe6c8),
        border = rgba(126, 200, 116, 0.26), borderStrong = rgba(150, 220, 138, 0.58),
        accent = hex(0x7cc46e), accentGlow = rgba(124, 196, 110, 0.42), accentWash = rgba(124, 196, 110, 0.12),
        accentTint = rgba(124, 196, 110, 0.24),
        accent2 = hex(0xc3d977), accent2Glow = rgba(195, 217, 119, 0.40), accent2Wash = rgba(195, 217, 119, 0.12),
        brand1 = hex(0x7cc46e), brand2 = hex(0xc3d977), logoCore = hex(0x0c1810),
        danger = hex(0xe8836b), success = hex(0x8fd99a), warning = hex(0xe0c56a),
        texture = Texture.LEAVES, leafInk = rgba(150, 214, 138, 0.085),
    )

    val Qahwa = VortexPalette(
        id = "qahwa", name = "Qahwa", tagline = "The coffeehouse table. قهوة. Walnut and brass.", light = false,
        bg = hex(0x1b120a), halo1 = rgba(198, 142, 62, 0.12), halo2 = rgba(90, 46, 20, 0.35),
        surface = hex(0x2a1c11), surface2 = hex(0x332315), glass = rgba(42, 28, 17, 0.80),
        text = hex(0xf4e7d2), muted = hex(0xb79a76), subtle = hex(0xd8c3a2),
        border = rgba(198, 142, 62, 0.28), borderStrong = rgba(214, 168, 92, 0.60),
        accent = hex(0xd1a24c), accentGlow = rgba(209, 162, 76, 0.40), accentWash = rgba(209, 162, 76, 0.12),
        accentTint = rgba(209, 162, 76, 0.24),
        accent2 = hex(0x62a88b), accent2Glow = rgba(98, 168, 139, 0.38), accent2Wash = rgba(98, 168, 139, 0.12),
        brand1 = hex(0xd1a24c), brand2 = hex(0x8c5a2b), logoCore = hex(0x1b120a),
        danger = hex(0xd4674a), success = hex(0x7fbf92), warning = hex(0xe8b552),
    )

    /** In VortexTawla's order. */
    val all = listOf(Vortex, Nova, Atrium, Gtx, Mono, Bamboo, Qahwa)

    fun byId(id: String?): VortexPalette = all.firstOrNull { it.id == id } ?: Vortex
}

/** The theme in force, for components that draw with the tokens directly. */
val LocalVortex = staticCompositionLocalOf { Themes.Vortex }

@Composable
fun VortexTheme(palette: VortexPalette = Themes.Vortex, content: @Composable () -> Unit) {
    // Material's own components (menus, dialogs, progress) take the same tokens,
    // made opaque: a see-through menu over a photo can't be read.
    val surface = palette.surface.compositeOver(palette.bg)
    val surface2 = palette.surface2.compositeOver(palette.bg)
    val colors = if (palette.light) {
        lightColorScheme(
            primary = palette.accent, onPrimary = palette.surface,
            primaryContainer = palette.accentTint, onPrimaryContainer = palette.text,
            secondary = palette.accent2, onSecondary = palette.surface,
            secondaryContainer = palette.accent2Wash, onSecondaryContainer = palette.text,
            background = palette.bg, onBackground = palette.text,
            surface = surface, onSurface = palette.text, onSurfaceVariant = palette.muted,
            surfaceContainer = surface, surfaceContainerHigh = surface,
            outline = palette.borderStrong, outlineVariant = palette.border,
            error = palette.danger,
        )
    } else {
        darkColorScheme(
            primary = palette.accent, onPrimary = palette.bg,
            primaryContainer = palette.accentTint, onPrimaryContainer = palette.text,
            secondary = palette.accent2, onSecondary = palette.bg,
            secondaryContainer = palette.accent2Wash, onSecondaryContainer = palette.text,
            background = palette.bg, onBackground = palette.text,
            surface = surface2, onSurface = palette.text, onSurfaceVariant = palette.muted,
            surfaceContainer = surface2, surfaceContainerHigh = surface2,
            outline = palette.borderStrong, outlineVariant = palette.border,
            error = palette.danger,
        )
    }
    CompositionLocalProvider(LocalVortex provides palette) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

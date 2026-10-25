package com.vortex.explorer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The handful of glyphs the built-in icon set lacks, drawn on the usual
 * 24-unit grid. Black here; `Icon` tints them to the theme.
 */
object VortexIcons {
    val Shuffle: ImageVector by lazy {
        icon("Shuffle") {
            stroked {
                moveTo(3f, 7f); lineTo(8f, 7f); lineTo(16f, 17f); lineTo(21f, 17f)
                moveTo(18f, 14f); lineTo(21f, 17f); lineTo(18f, 20f)
                moveTo(3f, 17f); lineTo(8f, 17f); lineTo(16f, 7f); lineTo(21f, 7f)
                moveTo(18f, 4f); lineTo(21f, 7f); lineTo(18f, 10f)
            }
        }
    }

    val Folder: ImageVector by lazy {
        icon("Folder") {
            filled {
                moveTo(2f, 6f); lineTo(9f, 6f); lineTo(11f, 8f); lineTo(22f, 8f)
                lineTo(22f, 19f); lineTo(2f, 19f); close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        icon("Pause") {
            filled {
                moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
                moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
            }
        }
    }

    val Photo: ImageVector by lazy {
        icon("Photo") {
            stroked {
                moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            }
            filled {
                moveTo(6f, 17f); lineTo(10f, 11f); lineTo(13f, 15f); lineTo(15f, 12.5f); lineTo(18f, 17f); close()
                moveTo(14f, 7.5f); lineTo(16.5f, 7.5f); lineTo(16.5f, 10f); lineTo(14f, 10f); close()
            }
        }
    }

    val Video: ImageVector by lazy {
        icon("Video") {
            stroked {
                moveTo(3f, 6f); lineTo(16f, 6f); lineTo(16f, 18f); lineTo(3f, 18f); close()
            }
            filled {
                moveTo(16f, 10f); lineTo(21f, 7f); lineTo(21f, 17f); lineTo(16f, 14f); close()
            }
        }
    }

    val Music: ImageVector by lazy {
        icon("Music") {
            stroked {
                moveTo(9f, 17f); lineTo(9f, 5f); lineTo(19f, 3f); lineTo(19f, 15f)
            }
            filled {
                moveTo(5f, 15f); lineTo(9f, 15f); lineTo(9f, 19f); lineTo(5f, 19f); close()
                moveTo(15f, 13f); lineTo(19f, 13f); lineTo(19f, 17f); lineTo(15f, 17f); close()
            }
        }
    }

    val Document: ImageVector by lazy {
        icon("Document") {
            stroked {
                moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(6f, 21f); close()
                moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f)
            }
        }
    }

    /** A network share: two stacked server units. */
    val Server: ImageVector by lazy {
        icon("Server") {
            stroked {
                moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(4f, 10f); close()
                moveTo(4f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(4f, 20f); close()
            }
            filled {
                moveTo(6.5f, 6.5f); lineTo(8.5f, 6.5f); lineTo(8.5f, 7.5f); lineTo(6.5f, 7.5f); close()
                moveTo(6.5f, 16.5f); lineTo(8.5f, 16.5f); lineTo(8.5f, 17.5f); lineTo(6.5f, 17.5f); close()
            }
        }
    }

    /** A thumb drive: storage you can unplug. */
    val Drive: ImageVector by lazy {
        icon("Drive") {
            stroked {
                moveTo(9f, 2f); lineTo(15f, 2f); lineTo(15f, 7f); lineTo(9f, 7f); close()
                moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 21f); lineTo(7f, 21f); close()
            }
        }
    }

    val Copy: ImageVector by lazy {
        icon("Copy") {
            stroked {
                moveTo(8f, 8f); lineTo(20f, 8f); lineTo(20f, 20f); lineTo(8f, 20f); close()
                moveTo(4f, 16f); lineTo(4f, 4f); lineTo(16f, 4f)
            }
        }
    }

    /** A die, for "pick one at random". */
    val Dice: ImageVector by lazy {
        icon("Dice") {
            stroked {
                moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
            }
            filled {
                pip(8f, 8f); pip(16f, 8f); pip(12f, 12f); pip(8f, 16f); pip(16f, 16f)
            }
        }
    }

    /** Numbered files: what an export writes. */
    val Numbered: ImageVector by lazy {
        icon("Numbered") {
            stroked {
                moveTo(10f, 6f); lineTo(21f, 6f)
                moveTo(10f, 12f); lineTo(21f, 12f)
                moveTo(10f, 18f); lineTo(21f, 18f)
                moveTo(4f, 5f); lineTo(5.5f, 4f); lineTo(5.5f, 8f)
            }
        }
    }

    private fun PathBuilder.pip(x: Float, y: Float) {
        moveTo(x - 1.25f, y - 1.25f)
        lineTo(x + 1.25f, y - 1.25f)
        lineTo(x + 1.25f, y + 1.25f)
        lineTo(x - 1.25f, y + 1.25f)
        close()
    }

    private fun icon(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(build).build()

    private fun ImageVector.Builder.stroked(block: PathBuilder.() -> Unit) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    private fun ImageVector.Builder.filled(block: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = block)
    }
}

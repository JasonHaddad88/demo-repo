package com.vortex.view.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The glyphs the built-in icon set lacks, on the usual 24-unit grid. */
object Icons {
    val Undo: ImageVector by lazy {
        icon("Undo") {
            stroked {
                moveTo(9f, 14f); lineTo(4f, 9f); lineTo(9f, 4f)
                moveTo(4f, 9f); lineTo(14.5f, 9f)
                curveTo(18f, 9f, 20.5f, 11.5f, 20.5f, 15f)
                curveTo(20.5f, 18.5f, 18f, 20f, 14.5f, 20f)
                lineTo(11f, 20f)
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

    val Photos: ImageVector by lazy {
        icon("Photos") {
            stroked {
                moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
                moveTo(4f, 16f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(15.5f, 12.5f); lineTo(20f, 17f)
            }
            filled {
                moveTo(15.5f, 7.5f)
                arcTo(1.5f, 1.5f, 0f, true, true, 15.5f, 10.5f)
                arcTo(1.5f, 1.5f, 0f, true, true, 15.5f, 7.5f)
                close()
            }
        }
    }

    val Add: ImageVector by lazy {
        icon("Add") {
            stroked {
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(5f, 12f); lineTo(19f, 12f)
            }
        }
    }

    val More: ImageVector by lazy {
        icon("More") {
            filled {
                dot(12f, 5.5f)
                dot(12f, 12f)
                dot(12f, 18.5f)
            }
        }
    }

    private fun PathBuilder.dot(x: Float, y: Float) {
        moveTo(x, y - 1.75f)
        arcTo(1.75f, 1.75f, 0f, true, true, x, y + 1.75f)
        arcTo(1.75f, 1.75f, 0f, true, true, x, y - 1.75f)
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

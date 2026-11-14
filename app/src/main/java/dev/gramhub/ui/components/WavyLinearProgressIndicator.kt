package dev.gramhub.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * A linear progress bar drawn as a travelling wave, in the style of Google's
 * Material 3 wavy progress indicator.
 *
 * Material 3 1.3.x does not ship `LinearWavyProgressIndicator` yet (it arrived in
 * 1.4), so the shape is drawn by hand. The wave is a sine sampled across the
 * width and stroked with round caps; the coloured part is the same wave clipped
 * to the progress fraction.
 *
 * @param progress fraction in `0..1`, or `null` for an indeterminate bar that
 *   sweeps a segment back and forth.
 */
@Composable
fun WavyLinearProgressIndicator(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    height: Dp = 10.dp,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val indicatorColor = MaterialTheme.colorScheme.primary

    val transition = rememberInfiniteTransition(label = "wavy-progress")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "phase",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "sweep",
    )

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (size.width <= 0f) return@Canvas

        val strokeWidth = size.height * 0.3f
        val amplitude = (size.height - strokeWidth) / 2f
        val wavelength = max(size.height * 2.6f, 1f)
        val centerY = size.height / 2f

        fun wave(): Path {
            val path = Path()
            val steps = max(2, (size.width / 4f).toInt())
            for (i in 0..steps) {
                val x = size.width * i / steps
                val y = centerY +
                    amplitude * sin(2f * PI.toFloat() * (x / wavelength + phase))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }

        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        drawPath(wave(), color = trackColor, style = stroke)

        if (progress != null) {
            val fraction = progress().coerceIn(0f, 1f)
            if (fraction > 0f) {
                clipRect(right = size.width * fraction) {
                    drawPath(wave(), color = indicatorColor, style = stroke)
                }
                // Stop indicator, the round dot the Material 3 bar ends with.
                val endX = size.width * fraction
                val endY = centerY +
                    amplitude * sin(2f * PI.toFloat() * (endX / wavelength + phase))
                drawCircle(
                    color = indicatorColor,
                    radius = strokeWidth * 0.75f,
                    center = androidx.compose.ui.geometry.Offset(endX, endY),
                )
            }
        } else {
            val segment = size.width * 0.35f
            val start = (size.width + segment) * sweep - segment
            clipRect(left = start, right = start + segment) {
                drawPath(wave(), color = indicatorColor, style = stroke)
            }
        }
    }
}

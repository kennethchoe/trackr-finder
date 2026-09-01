package dev.kchoe.trackrfinder

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Icon avatar plus stacked title and status. The ring pulses only while a scan
 * is actually running, so the header doubles as a liveness indicator -- a
 * frozen ring means the radio stopped, which is otherwise invisible.
 */
@Composable
fun Header(
    title: String,
    status: String,
    scanning: Boolean,
    foundCount: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BeaconAvatar(scanning = scanning, active = foundCount > 0)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(active = foundCount > 0)
                Spacer(Modifier.width(6.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    val color = if (active) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(8.dp)) { drawCircle(color) }
}

/** Concentric arcs -- a beacon emitting -- inside a soft tinted disc. */
@Composable
private fun BeaconAvatar(scanning: Boolean, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "beacon")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )

    val accent = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer

    Box(
        modifier = Modifier.size(56.dp).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.minDimension / 2f

            drawCircle(color = container, radius = maxR, center = c)

            // Expanding ring, fading as it grows. Only while scanning.
            if (scanning) {
                val r = maxR * (0.35f + 0.65f * pulse)
                drawCircle(
                    color = accent.copy(alpha = (1f - pulse) * 0.55f),
                    radius = r,
                    center = c,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            // Static beacon: a dot with two arcs above it.
            drawCircle(color = accent, radius = 3.5.dp.toPx(), center = c)
            listOf(0.42f, 0.66f).forEachIndexed { i, frac ->
                drawArc(
                    color = accent.copy(alpha = if (active) 0.9f else 0.45f),
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(c.x - maxR * frac, c.y - maxR * frac),
                    size = androidx.compose.ui.geometry.Size(
                        maxR * frac * 2f, maxR * frac * 2f,
                    ),
                    style = Stroke(width = (2 - i * 0.4f).dp.toPx()),
                )
            }
        }
    }
}

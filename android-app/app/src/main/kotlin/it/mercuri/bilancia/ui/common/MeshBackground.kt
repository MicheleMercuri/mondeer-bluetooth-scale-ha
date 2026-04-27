package it.mercuri.bilancia.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.mercuri.bilancia.domain.MetricStatus
import it.mercuri.bilancia.ui.theme.StatusOk
import it.mercuri.bilancia.ui.theme.StatusOver
import it.mercuri.bilancia.ui.theme.StatusUnder

/**
 * Sfondo "mesh gradient" soft con 3 sfumature radiali sovrapposte. Le
 * tinte sono modulate dallo status del peso: verdi se ottimale, ambra
 * se borderline/sopra, blu se sotto. Lievi animazioni di posizione per
 * dare profondità e movimento (effetto wow).
 *
 * Usato come background della Home dietro la LazyColumn.
 */
@Composable
fun MeshBackground(
    status: MetricStatus,
    modifier: Modifier = Modifier,
) {
    val accent = when (status) {
        MetricStatus.UNDER -> StatusUnder
        MetricStatus.OPTIMAL -> StatusOk
        MetricStatus.OVER -> StatusOver
        MetricStatus.UNKNOWN -> Color(0xFF6B7280)
    }
    val baseSurface = MaterialTheme.colorScheme.background

    val infinite = rememberInfiniteTransition(label = "mesh-bg")
    // Ogni "blob" oscilla in posizione con periodi differenti, così il
    // mesh gradient sembra respirare invece di pulsare in fase.
    val phase1 by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phase1",
    )
    val phase2 by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 13000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phase2",
    )
    val phase3 by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 17000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phase3",
    )

    Box(modifier = modifier) {
        // Base surface
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = baseSurface)
        }

        // Mesh = 3 blob radiali blurrati e sovrapposti.
        Box(modifier = Modifier.fillMaxSize().blur(80.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Blob 1 (accent forte, alto a sx)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.35f),
                            Color.Transparent,
                        ),
                        center = Offset(w * (0.18f + 0.10f * phase1),
                                        h * (0.18f + 0.06f * phase1)),
                        radius = w * 0.55f,
                    ),
                )
                // Blob 2 (accent medio, basso a dx)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.22f),
                            Color.Transparent,
                        ),
                        center = Offset(w * (0.85f - 0.08f * phase2),
                                        h * (0.78f - 0.10f * phase2)),
                        radius = w * 0.50f,
                    ),
                )
                // Blob 3 (cool surface, centro-basso, dà profondità)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF202738).copy(alpha = 0.22f),
                            Color.Transparent,
                        ),
                        center = Offset(w * (0.55f + 0.05f * phase3),
                                        h * (0.50f + 0.08f * phase3)),
                        radius = w * 0.45f,
                    ),
                )
            }
        }
    }
}

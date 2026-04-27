package it.mercuri.bilancia.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.mercuri.bilancia.domain.MetricStatus
import it.mercuri.bilancia.ui.theme.GaugeBlue
import it.mercuri.bilancia.ui.theme.GaugeGreen
import it.mercuri.bilancia.ui.theme.GaugeOrange
import it.mercuri.bilancia.ui.theme.GaugeRed
import it.mercuri.bilancia.ui.theme.GaugeYellow
import it.mercuri.bilancia.ui.theme.StatusOk
import it.mercuri.bilancia.ui.theme.StatusOver
import it.mercuri.bilancia.ui.theme.StatusUnder
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gauge "Hero" del peso — semicerchio futuristico con:
 * - blur backlight pulsante dietro (effetto neon)
 * - anello sweep-gradient esterno spesso
 * - tick mark esterni ogni step
 * - marker animato (interpola fluidamente sulla posizione del peso)
 * - counter animato sul numero (count-up)
 * - status pill colorata sotto il numero
 *
 * Tutti gli effetti via Compose Canvas + Modifier.blur, zero librerie.
 */
@Composable
fun Hero3DGauge(
    weightKg: Double,
    rangeMin: Double,
    rangeMax: Double,
    bmi: Double?,
    bmiStatus: MetricStatus,
    weightStatus: MetricStatus,
) {
    // Asse esteso: ±40% del range, così il marker non collassa nei bordi.
    val span = (rangeMax - rangeMin).coerceAtLeast(1.0)
    val visualMin = rangeMin - span * 0.4
    val visualMax = rangeMax + span * 0.4
    val visualSpan = visualMax - visualMin

    // Frazione 0..1 per il marker. Animata: cambia smooth quando arriva una
    // nuova pesata (es. 67.9 → 67.7 fa scivolare il pallino, non scatta).
    val targetFraction = ((weightKg - visualMin) / visualSpan)
        .coerceIn(0.0, 1.0).toFloat()
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 1200),
        label = "gauge-marker-frac"
    )

    // Counter: interpolazione lineare dal vecchio al nuovo peso.
    val animatedWeight by animateFloatAsState(
        targetValue = weightKg.toFloat(),
        animationSpec = tween(durationMillis = 900),
        label = "gauge-weight-counter"
    )

    // Pulse del backlight: 0.85 → 1.0 → 0.85 in 2.5s, ciclico.
    val infinite = rememberInfiniteTransition(label = "gauge-backlight")
    val pulse by infinite.animateFloat(
        initialValue = 0.85f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val statusColor = when (weightStatus) {
        MetricStatus.UNDER -> StatusUnder
        MetricStatus.OPTIMAL -> StatusOk
        MetricStatus.OVER -> StatusOver
        MetricStatus.UNKNOWN -> Color.Gray
    }

    Box(
        modifier = Modifier.size(260.dp),
        contentAlignment = Alignment.Center
    ) {
        // Layer 1: blur backlight (neon glow pulsante dietro tutto). Il colore
        // del glow segue lo status del peso, così il gauge "respira" verde se
        // sei ottimale, ambra se borderline, rosso se fuori range.
        Box(
            modifier = Modifier
                .size(220.dp)
                .blur(40.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            statusColor.copy(alpha = 0.6f * pulse),
                            statusColor.copy(alpha = 0.0f),
                        )
                    )
                )
        )

        // Layer 2: sweep gradient ring + ticks + marker.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val ringInset = 36f
            val ringStroke = 28f

            // Anello base scuro (track dietro al gradient, attenua i salti
            // di colore del sweepGradient e dà profondità).
            drawArc(
                color = Color(0xFF202738),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = ringStroke, cap = StrokeCap.Round),
                topLeft = Offset(ringInset, ringInset),
                size = Size(w - 2 * ringInset, h - 2 * ringInset)
            )

            // Anello principale: sweep gradient (rosso→giallo→verde→giallo→rosso),
            // sempre 270° anche se il marker è oltre i bordi.
            val ringBrush = Brush.sweepGradient(
                colors = listOf(
                    GaugeRed,
                    GaugeOrange,
                    GaugeYellow,
                    GaugeGreen,
                    GaugeBlue,
                    GaugeGreen,
                    GaugeYellow,
                    GaugeOrange,
                    GaugeRed,
                ),
                center = Offset(w / 2f, h / 2f)
            )
            drawArc(
                brush = ringBrush,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = ringStroke, cap = StrokeCap.Round),
                topLeft = Offset(ringInset, ringInset),
                size = Size(w - 2 * ringInset, h - 2 * ringInset),
                alpha = 0.92f
            )

            // Tick marks per i bordi del range "ottimale" (min, max),
            // così l'utente vede dove cade il valore fisiologico target.
            val rangeMinFraction = ((rangeMin - visualMin) / visualSpan).toFloat()
            val rangeMaxFraction = ((rangeMax - visualMin) / visualSpan).toFloat()
            for (frac in listOf(rangeMinFraction, rangeMaxFraction)) {
                val angleDeg = 135f + 270f * frac
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val cx = w / 2f
                val cy = h / 2f
                val rOuter = (w / 2f) - ringInset + (ringStroke / 2f) + 4f
                val rInner = (w / 2f) - ringInset - (ringStroke / 2f) - 4f
                drawLine(
                    color = Color.White.copy(alpha = 0.65f),
                    start = Offset(
                        cx + (rInner * cos(angleRad)).toFloat(),
                        cy + (rInner * sin(angleRad)).toFloat()
                    ),
                    end = Offset(
                        cx + (rOuter * cos(angleRad)).toFloat(),
                        cy + (rOuter * sin(angleRad)).toFloat()
                    ),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }

            // Marker: pallino bianco bordato dello status color, sull'anello.
            val markerAngleDeg = 135f + 270f * animatedFraction
            val markerAngleRad = Math.toRadians(markerAngleDeg.toDouble())
            val rMarker = (w / 2f) - ringInset
            val mx = (w / 2f) + (rMarker * cos(markerAngleRad)).toFloat()
            val my = (h / 2f) + (rMarker * sin(markerAngleRad)).toFloat()
            // Halo pulsante (alone semitrasparente più grande)
            drawCircle(
                color = statusColor.copy(alpha = 0.35f * pulse),
                radius = ringStroke * 0.9f,
                center = Offset(mx, my),
            )
            drawCircle(
                color = Color.White,
                radius = ringStroke * 0.55f,
                center = Offset(mx, my),
            )
            drawCircle(
                color = statusColor,
                radius = ringStroke * 0.30f,
                center = Offset(mx, my),
            )
        }

        // Layer 3: contenuto centrale (numero + unità + IMC pill).
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "%.1f".format(animatedWeight),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "kg",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            if (bmi != null) {
                Spacer(modifier = Modifier.height(8.dp))
                StatusPill(
                    text = "IMC %.1f".format(bmi),
                    color = when (bmiStatus) {
                        MetricStatus.UNDER -> StatusUnder
                        MetricStatus.OPTIMAL -> StatusOk
                        MetricStatus.OVER -> if (bmi >= 30) StatusOver else GaugeOrange
                        MetricStatus.UNKNOWN -> Color.Gray
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

package it.mercuri.bilancia.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import it.mercuri.bilancia.domain.Metric
import it.mercuri.bilancia.domain.MetricStatus
import it.mercuri.bilancia.ui.theme.StatusOk
import it.mercuri.bilancia.ui.theme.StatusOver
import it.mercuri.bilancia.ui.theme.StatusUnder
import kotlin.math.cos
import kotlin.math.sin

/**
 * Card "futuristica" per una metrica BIA: a sinistra mini ring-gauge a
 * 270° con backlight neon pulsante e marker animato, a destra label +
 * valore + status pill + range.
 *
 * Sostituisce [MetricRangeCard]: stesso input, stesso comportamento
 * funzionale, layout più 3D/glassy.
 */
@Composable
fun MetricRingCard(
    metric: Metric,
    /** Estremi visualizzati sul ring (es. 5–50 per il grasso %). */
    visualMin: Double,
    visualMax: Double,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (metric.status) {
        MetricStatus.UNDER -> StatusUnder
        MetricStatus.OPTIMAL -> StatusOk
        MetricStatus.OVER -> StatusOver
        MetricStatus.UNKNOWN -> Color.Gray
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniRingGauge(
                    value = metric.value,
                    rangeMin = metric.rangeMin,
                    rangeMax = metric.rangeMax,
                    visualMin = visualMin,
                    visualMax = visualMax,
                    statusColor = statusColor,
                    modifier = Modifier.size(78.dp),
                )
                Spacer(modifier = Modifier.size(14.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = metric.label,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                    Text(
                        text = formatValue(metric),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(status = metric.status, color = statusColor)
                        if (metric.rangeMin != null && metric.rangeMax != null) {
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = "${formatRangeNumber(metric.rangeMin)}–" +
                                    "${formatRangeNumber(metric.rangeMax)} ${metric.unit}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.50f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniRingGauge(
    value: Double?,
    rangeMin: Double?,
    rangeMax: Double?,
    visualMin: Double,
    visualMax: Double,
    statusColor: Color,
    modifier: Modifier = Modifier,
) {
    val span = (visualMax - visualMin).coerceAtLeast(0.001)
    val targetFrac = if (value != null) {
        ((value - visualMin) / span).coerceIn(0.0, 1.0).toFloat()
    } else 0f
    val animatedFrac by animateFloatAsState(
        targetValue = targetFrac,
        animationSpec = tween(durationMillis = 900),
        label = "ringgauge-frac",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Layer 1: backlight neon soft
        Box(
            modifier = Modifier
                .size(60.dp)
                .blur(20.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            statusColor.copy(alpha = 0.55f),
                            statusColor.copy(alpha = 0.0f),
                        )
                    )
                )
        )

        // Layer 2: anello track + range ottimale + marker
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val inset = 10f
            val stroke = 10f

            // Anello track scuro (270° da 135° a 405°/45°)
            drawArc(
                color = Color(0xFF202738),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(inset, inset),
                size = Size(w - 2 * inset, h - 2 * inset),
            )

            // Fascia "range ottimale" colorata (verde se ottimale, in scala
            // di grigi neutri se non c'è un range definito).
            if (rangeMin != null && rangeMax != null) {
                val rmin = ((rangeMin - visualMin) / span).coerceIn(0.0, 1.0).toFloat()
                val rmax = ((rangeMax - visualMin) / span).coerceIn(0.0, 1.0).toFloat()
                val startAngle = 135f + 270f * rmin
                val sweep = 270f * (rmax - rmin)
                drawArc(
                    color = StatusOk.copy(alpha = 0.55f),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = Offset(inset, inset),
                    size = Size(w - 2 * inset, h - 2 * inset),
                )
            }

            // Marker pallino sul valore corrente.
            if (value != null) {
                val angleDeg = 135f + 270f * animatedFrac
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val cx = w / 2f
                val cy = h / 2f
                val r = (w / 2f) - inset
                val mx = cx + (r * cos(angleRad)).toFloat()
                val my = cy + (r * sin(angleRad)).toFloat()
                drawCircle(
                    color = statusColor.copy(alpha = 0.45f),
                    radius = stroke * 0.95f,
                    center = Offset(mx, my),
                )
                drawCircle(
                    color = Color.White,
                    radius = stroke * 0.55f,
                    center = Offset(mx, my),
                )
                drawCircle(
                    color = statusColor,
                    radius = stroke * 0.32f,
                    center = Offset(mx, my),
                )
            }
        }

        // Layer 3: numero al centro (compatto)
        Text(
            text = if (value != null) compactValue(value) else "—",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatusPill(status: MetricStatus, color: Color) {
    val label = when (status) {
        MetricStatus.UNDER -> "Basso"
        MetricStatus.OPTIMAL -> "Ottimale"
        MetricStatus.OVER -> "Alto"
        MetricStatus.UNKNOWN -> "—"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "${status.emoji} $label",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

private fun formatValue(m: Metric): String {
    val v = m.value ?: return "—"
    val rounded = if (m.unit == "kcal" || m.unit == "") "%.0f".format(v) else "%.1f".format(v)
    return "$rounded ${m.unit}".trimEnd()
}

private fun compactValue(v: Double): String =
    if (v >= 100) "%.0f".format(v) else "%.1f".format(v)

private fun formatRangeNumber(v: Double): String =
    if (v >= 100) "%.0f".format(v) else "%.1f".format(v)

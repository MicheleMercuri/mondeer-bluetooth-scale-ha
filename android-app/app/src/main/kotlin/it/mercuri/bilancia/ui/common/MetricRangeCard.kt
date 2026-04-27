package it.mercuri.bilancia.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.mercuri.bilancia.domain.Metric
import it.mercuri.bilancia.domain.MetricStatus
import it.mercuri.bilancia.ui.theme.GaugeOrange
import it.mercuri.bilancia.ui.theme.GaugeRed
import it.mercuri.bilancia.ui.theme.GaugeYellow
import it.mercuri.bilancia.ui.theme.StatusOk
import it.mercuri.bilancia.ui.theme.StatusOver
import it.mercuri.bilancia.ui.theme.StatusUnder

/**
 * Card che mostra una singola metrica biometrica con:
 *  - valore corrente in grande
 *  - range ottimale numerico
 *  - badge di stato (sotto/ottimale/sopra)
 *  - barra orizzontale colorata che rappresenta dove cade il valore
 *    nel range esteso (con la fascia verde nel mezzo che è il range ottimale)
 */
@Composable
fun MetricRangeCard(
    metric: Metric,
    /** Estremi visualizzati sulla barra (es. 0–60 per il grasso %). */
    visualMin: Double,
    visualMax: Double,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = metric.label,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    Text(
                        text = formatValue(metric),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                StatusBadge(metric.status)
            }
            Spacer(modifier = Modifier.height(8.dp))
            RangeBar(
                value = metric.value,
                rangeMin = metric.rangeMin,
                rangeMax = metric.rangeMax,
                visualMin = visualMin,
                visualMax = visualMax,
            )
            if (metric.rangeMin != null && metric.rangeMax != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Range: ${"%.1f".format(metric.rangeMin)}–${"%.1f".format(metric.rangeMax)} ${metric.unit}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun formatValue(m: Metric): String {
    val v = m.value ?: return "—"
    val rounded = if (m.unit == "kcal" || m.unit == "") "%.0f".format(v) else "%.1f".format(v)
    return "$rounded ${m.unit}".trimEnd()
}

@Composable
private fun StatusBadge(status: MetricStatus) {
    val (color, label) = when (status) {
        MetricStatus.UNDER -> StatusUnder to "Basso"
        MetricStatus.OPTIMAL -> StatusOk to "Ottimale"
        MetricStatus.OVER -> StatusOver to "Alto"
        MetricStatus.UNKNOWN -> Color.Gray to "—"
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 0.dp)
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(20.dp),
            color = color.copy(alpha = 0.12f),
        ) {
            Text(
                text = "${status.emoji} $label",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = color,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun RangeBar(
    value: Double?,
    rangeMin: Double?,
    rangeMax: Double?,
    visualMin: Double,
    visualMax: Double,
) {
    val barHeight = 10.dp
    val markerSize = 14.dp

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight + 4.dp)
    ) {
        val w = size.width
        val barY = (size.height - barHeight.toPx()) / 2
        val cornerRadius = CornerRadius(barHeight.toPx() / 2)

        // Sfondo grigio
        drawRoundRect(
            color = Color.LightGray.copy(alpha = 0.30f),
            topLeft = Offset(0f, barY),
            size = Size(w, barHeight.toPx()),
            cornerRadius = cornerRadius
        )

        // Fascia "ottimale" verde (range_min..range_max)
        if (rangeMin != null && rangeMax != null) {
            val left = ((rangeMin - visualMin) / (visualMax - visualMin)).coerceIn(0.0, 1.0).toFloat() * w
            val right = ((rangeMax - visualMin) / (visualMax - visualMin)).coerceIn(0.0, 1.0).toFloat() * w
            drawRoundRect(
                color = StatusOk.copy(alpha = 0.35f),
                topLeft = Offset(left, barY),
                size = Size(right - left, barHeight.toPx()),
                cornerRadius = cornerRadius
            )
        }

        // Marker valore corrente
        if (value != null) {
            val x = ((value - visualMin) / (visualMax - visualMin))
                .coerceIn(0.0, 1.0).toFloat() * w
            val markerColor = when {
                rangeMin != null && rangeMax != null && value < rangeMin -> StatusUnder
                rangeMin != null && rangeMax != null && value > rangeMax -> StatusOver
                else -> StatusOk
            }
            drawCircle(
                color = markerColor,
                radius = markerSize.toPx() / 2,
                center = Offset(x, size.height / 2)
            )
            drawCircle(
                color = Color.White,
                radius = (markerSize.toPx() / 2) - 3,
                center = Offset(x, size.height / 2)
            )
            drawCircle(
                color = markerColor,
                radius = (markerSize.toPx() / 2) - 5,
                center = Offset(x, size.height / 2)
            )
        }
    }
}

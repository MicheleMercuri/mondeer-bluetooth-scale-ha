package it.mercuri.bilancia.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.mercuri.bilancia.domain.TrendMetric
import it.mercuri.bilancia.domain.TrendPeriod
import it.mercuri.bilancia.domain.TrendPoint
import it.mercuri.bilancia.ui.theme.BluePrimary
import it.mercuri.bilancia.ui.theme.BluePrimaryDark

/**
 * Card grafico tendenza:
 *  - SegmentedButton settimana / mese / anno
 *  - Chip orizzontali per scegliere quale metrica
 *  - LineChart custom (Canvas Compose) — niente librerie esterne
 */
@Composable
fun TrendChartCard(
    period: TrendPeriod,
    onPeriodChange: (TrendPeriod) -> Unit,
    metric: TrendMetric,
    onMetricChange: (TrendMetric) -> Unit,
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Andamento ${metric.labelIt.lowercase()}",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TrendPeriod.entries.forEachIndexed { idx, p ->
                    SegmentedButton(
                        selected = p == period,
                        onClick = { onPeriodChange(p) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = idx, count = TrendPeriod.entries.size
                        ),
                    ) { Text(p.labelIt, fontSize = 12.sp) }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TrendMetric.entries) { m ->
                    FilterChip(
                        selected = m == metric,
                        onClick = { onMetricChange(m) },
                        label = { Text(m.labelIt, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (points.size < 2) {
                Text(
                    text = "Servono almeno 2 pesate nel periodo selezionato per disegnare il grafico.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                LineChartView(points = points)
                Spacer(modifier = Modifier.height(6.dp))
                MinMaxFooter(points)
            }
        }
    }
}

@Composable
private fun LineChartView(points: List<TrendPoint>) {
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val lineColor = BluePrimary
    val lineFillTop = BluePrimary.copy(alpha = 0.30f)
    val lineFillBottom = BluePrimary.copy(alpha = 0.02f)
    val pointColor = BluePrimaryDark

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        val w = size.width
        val h = size.height
        val padTop = 8f
        val padBottom = 8f
        val padLeft = 8f
        val padRight = 8f
        val drawW = w - padLeft - padRight
        val drawH = h - padTop - padBottom

        val values = points.map { it.value }
        val rawMin = values.min()
        val rawMax = values.max()
        val span = (rawMax - rawMin).takeIf { it > 1e-6 } ?: 1.0
        // Padding 5% sopra/sotto in modo che gli estremi non tocchino i bordi
        val paddedMin = rawMin - span * 0.10
        val paddedMax = rawMax + span * 0.10
        val paddedSpan = paddedMax - paddedMin

        // Grid orizzontale: 4 linee tratteggiate
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
        for (i in 0..3) {
            val y = padTop + drawH * (i / 3f)
            drawLine(
                color = gridColor,
                start = Offset(padLeft, y),
                end = Offset(w - padRight, y),
                strokeWidth = 1f,
                pathEffect = dash,
            )
        }

        // Mappa i punti su coordinate canvas
        val xs = (0 until points.size).map { padLeft + drawW * (it.toFloat() / (points.size - 1)) }
        val ys = values.map { v ->
            val norm = ((v - paddedMin) / paddedSpan).toFloat()
            // Inverti: y=0 in alto
            padTop + drawH * (1f - norm)
        }

        // Area sotto la linea (gradient)
        val areaPath = Path().apply {
            moveTo(xs.first(), h - padBottom)
            for (i in xs.indices) lineTo(xs[i], ys[i])
            lineTo(xs.last(), h - padBottom)
            close()
        }
        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineFillTop, lineFillBottom),
                startY = padTop,
                endY = h - padBottom
            )
        )

        // Linea
        val linePath = Path().apply {
            moveTo(xs.first(), ys.first())
            for (i in 1 until xs.size) lineTo(xs[i], ys[i])
        }
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 3.5f, cap = StrokeCap.Round)
        )

        // Punti (cerchietti)
        for (i in xs.indices) {
            drawCircle(
                color = Color.White,
                radius = 5f,
                center = Offset(xs[i], ys[i])
            )
            drawCircle(
                color = pointColor,
                radius = 3f,
                center = Offset(xs[i], ys[i])
            )
        }
    }
}

@Composable
private fun MinMaxFooter(points: List<TrendPoint>) {
    val values = points.map { it.value }
    val min = values.min()
    val max = values.max()
    val cur = values.last()
    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
        FooterItem(label = "Min", value = "%.1f".format(min), modifier = Modifier.weight(1f))
        FooterItem(label = "Attuale", value = "%.1f".format(cur), modifier = Modifier.weight(1f))
        FooterItem(label = "Max", value = "%.1f".format(max), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FooterItem(label: String, value: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

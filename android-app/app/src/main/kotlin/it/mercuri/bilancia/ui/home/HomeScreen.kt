package it.mercuri.bilancia.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import it.mercuri.bilancia.domain.Profile
import it.mercuri.bilancia.domain.TrendMetric
import it.mercuri.bilancia.domain.TrendPeriod
import it.mercuri.bilancia.domain.TrendPoint
import it.mercuri.bilancia.domain.WeighingSnapshot
import it.mercuri.bilancia.ui.common.Hero3DGauge
import it.mercuri.bilancia.ui.common.MeshBackground
import it.mercuri.bilancia.ui.common.MetricRingCard
import it.mercuri.bilancia.ui.common.TrendChartCard
import it.mercuri.bilancia.ui.theme.BluePrimary
import it.mercuri.bilancia.ui.theme.BluePrimaryDark
import it.mercuri.bilancia.ui.theme.GaugeBlue
import it.mercuri.bilancia.ui.theme.GaugeGreen
import it.mercuri.bilancia.ui.theme.GaugeOrange
import it.mercuri.bilancia.ui.theme.GaugeRed
import it.mercuri.bilancia.ui.theme.GaugeYellow
import it.mercuri.bilancia.ui.theme.StatusOk
import it.mercuri.bilancia.ui.theme.StatusOver
import it.mercuri.bilancia.ui.theme.StatusUnder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    profile: Profile = Profile.USER1,
    snapshot: WeighingSnapshot? = null,
    trendPoints: List<TrendPoint> = emptyList(),
    onSettingsClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    vm: HomeViewModel? = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    // Se il ViewModel è disponibile, usa lo state da MQTT/Room (stato reale).
    // Altrimenti (es. preview) cade sui parametri default.
    val state = vm?.state?.collectAsState()?.value
    val effectiveProfile = state?.profile ?: profile
    val effectiveSnapshot = state?.snapshot ?: snapshot
    val history = state?.history ?: emptyList()

    var period by remember { mutableStateOf(TrendPeriod.MONTH) }
    var trendMetric by remember { mutableStateOf(TrendMetric.WEIGHT) }

    // Costruisce i TrendPoint per la metrica selezionata, filtrando i null
    // (es. record preliminary che hanno solo weight). Si ricalcola
    // automaticamente quando cambia `trendMetric` o arriva un nuovo record.
    val effectiveTrendPoints = remember(history, trendMetric) {
        if (history.isEmpty()) trendPoints
        else history.mapNotNull { e ->
            val v: Double? = when (trendMetric) {
                TrendMetric.WEIGHT -> e.weightKg
                TrendMetric.BMI -> e.bmi
                TrendMetric.FAT -> e.fatPercent
                TrendMetric.WATER -> e.waterPercent
                TrendMetric.MUSCLE -> e.muscleKg
                TrendMetric.BONE -> e.boneKg
                TrendMetric.VISCERAL -> e.visceralFat?.toDouble()
            }
            v?.let {
                TrendPoint(
                    timestamp = parseHistoryInstant(e.measuredAtIso),
                    value = it,
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Header(profileName = effectiveProfile.displayName, onSettings = onSettingsClick)

        if (effectiveSnapshot == null) {
            EmptyState()
        } else {
            val s = effectiveSnapshot
            // Mesh gradient sotto la lista. Il colore segue lo status del
            // peso: verde se ottimale, ambra se sopra/sotto. Tutto soft +
            // animato per dare profondità senza distrarre dal contenuto.
            Box(modifier = Modifier.fillMaxSize()) {
                MeshBackground(
                    status = s.weightStatus,
                    modifier = Modifier.fillMaxSize(),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                item { WeightCard(s) }

                item {
                    TrendChartCard(
                        period = period,
                        onPeriodChange = { period = it },
                        metric = trendMetric,
                        onMetricChange = { trendMetric = it },
                        points = effectiveTrendPoints,
                    )
                }

                item {
                    SectionTitle("Composizione corporea")
                }

                item {
                    MetricRingCard(
                        metric = s.fat,
                        visualMin = 5.0,
                        visualMax = 50.0,
                    )
                }
                item {
                    MetricRingCard(
                        metric = s.water,
                        visualMin = 30.0,
                        visualMax = 70.0,
                    )
                }
                item {
                    MetricRingCard(
                        metric = s.muscle,
                        visualMin = 10.0,
                        visualMax = 60.0,
                    )
                }
                item {
                    MetricRingCard(
                        metric = s.bone,
                        visualMin = 1.5,
                        visualMax = 4.0,
                    )
                }
                item {
                    MetricRingCard(
                        metric = s.visceral,
                        visualMin = 1.0,
                        visualMax = 30.0,
                    )
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BmrCard(
                            kcal = s.basalKcal,
                            modifier = Modifier.weight(1f)
                        )
                        DeltaCard(
                            deltaPct = s.monthlyChangePercent,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Text(
                        text = "Ultima pesata: ${formatTime(s.measuredAt)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (!s.isComplete) {
                        Text(
                            text = "Pesata preliminare — composizione corporea " +
                                    "non aggiornata (resta sopra la bilancia 15-20 secondi)",
                            fontSize = 12.sp,
                            color = StatusOver,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun Header(profileName: String, onSettings: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = BluePrimary) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = Brush.verticalGradient(listOf(BluePrimary, BluePrimaryDark)))
                .padding(top = 36.dp, bottom = 18.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "WeighAi",
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Ciao, $profileName",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
                )
            }
            // Icon settings in top-right
            IconButton(
                onClick = onSettings,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Impostazioni",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun WeightCard(s: WeighingSnapshot) {
    // Card con gradiente sottile sullo sfondo (futuristico, dà profondità)
    // Nessun border esplicito: l'elevation + le ombre del gauge bastano.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Hero3DGauge(
                    weightKg = s.weightKg,
                    rangeMin = s.weightMinKg,
                    rangeMax = s.weightMaxKg,
                    bmi = s.bmi,
                    bmiStatus = s.bmiStatus,
                    weightStatus = s.weightStatus,
                )
                Spacer(modifier = Modifier.height(16.dp))
                WeightRangeBar(
                    weightKg = s.weightKg,
                    rangeMin = s.weightMinKg,
                    rangeMax = s.weightMaxKg,
                    status = s.weightStatus,
                )
            }
        }
    }
}

/**
 * Barra "Peso forma" come nel riferimento:
 *   - sfondo a 5 fasce colorate (rosso/arancione/verde/arancione/rosso)
 *     simbolizzando sottopeso / borderline / ottimale / borderline / sopra
 *   - marker triangolare con etichetta "X kg" sopra, posizionato dove cade
 *     il valore corrente
 *   - sotto la barra, range numerico "61 — 69 kg"
 *   - delta vs range ("in forma" / "+1.4 kg sopra il limite" / "−2 kg sotto")
 */
@Composable
private fun WeightRangeBar(
    weightKg: Double,
    rangeMin: Double,
    rangeMax: Double,
    status: MetricStatus,
) {
    // Asse esteso: ±20% rispetto al range, così il marker non si schiaccia
    // nei bordi anche se sei lievemente sotto/sopra.
    val span = (rangeMax - rangeMin).coerceAtLeast(1.0)
    val visualMin = rangeMin - span * 0.4
    val visualMax = rangeMax + span * 0.4
    val visualSpan = visualMax - visualMin
    val markerFraction = ((weightKg - visualMin) / visualSpan)
        .coerceIn(0.0, 1.0).toFloat()

    val deltaText: String = when (status) {
        MetricStatus.UNDER -> "%.1f kg sotto il limite".format(rangeMin - weightKg)
        MetricStatus.OPTIMAL -> "in forma"
        MetricStatus.OVER -> "%.1f kg oltre il limite".format(weightKg - rangeMax)
        MetricStatus.UNKNOWN -> "—"
    }
    val deltaColor = when (status) {
        MetricStatus.UNDER -> StatusUnder
        MetricStatus.OPTIMAL -> StatusOk
        MetricStatus.OVER -> StatusOver
        MetricStatus.UNKNOWN -> Color.Gray
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Marker triangolare con etichetta sopra la barra
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
            ) {
                // Etichetta + triangolo, posizionati con offset orizzontale
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 0.dp)
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Spacer(modifier = Modifier.fillMaxWidth(markerFraction))
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                // shift di 28dp a sinistra per centrare l'etichetta
                                // sul marker (la larghezza approssimativa del badge)
                                .offset(x = (-28).dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = deltaColor,
                            ) {
                                Text(
                                    text = "%.1f kg".format(weightKg),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            // Triangolo: usato Canvas piccolo
                            Canvas(modifier = Modifier.size(width = 10.dp, height = 6.dp)) {
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(size.width, 0f)
                                    lineTo(size.width / 2, size.height)
                                    close()
                                }
                                drawPath(path, deltaColor)
                            }
                        }
                    }
                }
            }
        }

        // La barra colorata vera
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val w = size.width
            val h = size.height
            val cornerR = h / 2

            // Calcolo i breakpoints in coordinate canvas
            val xMin = (((rangeMin - visualMin) / visualSpan).coerceIn(0.0, 1.0) * w).toFloat()
            val xMax = (((rangeMax - visualMin) / visualSpan).coerceIn(0.0, 1.0) * w).toFloat()
            // Border zone larghezza ~10% del range
            val borderW = (xMax - xMin) * 0.20f

            val gradient = Brush.horizontalGradient(
                0.0f to GaugeRed,
                (xMin - borderW).coerceAtLeast(0f) / w to GaugeOrange,
                xMin / w to GaugeYellow,
                ((xMin + xMax) / 2) / w to GaugeGreen,
                xMax / w to GaugeYellow,
                ((xMax + borderW) / w).coerceAtMost(1f) to GaugeOrange,
                1.0f to GaugeRed,
            )
            drawRoundRect(
                brush = gradient,
                topLeft = Offset(0f, 0f),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR)
            )

            // Tick verticali bianchi sui breakpoints (limiti del range ottimale)
            drawLine(
                color = Color.White,
                start = Offset(xMin, 0f),
                end = Offset(xMin, h),
                strokeWidth = 2f,
            )
            drawLine(
                color = Color.White,
                start = Offset(xMax, 0f),
                end = Offset(xMax, h),
                strokeWidth = 2f,
            )

            // Marker pallino bianco sul valore corrente
            val cx = markerFraction * w
            drawCircle(
                color = Color.White,
                radius = h * 0.65f,
                center = Offset(cx, h / 2)
            )
            drawCircle(
                color = deltaColor,
                radius = h * 0.40f,
                center = Offset(cx, h / 2)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Label range numerico + delta status
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "%.0f kg".format(rangeMin),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Text(
                text = deltaText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = deltaColor,
            )
            Text(
                text = "%.0f kg".format(rangeMax),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun BmrCard(kcal: Int?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Metabolismo basale",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Text(
                text = if (kcal != null) "$kcal kcal" else "—",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun DeltaCard(deltaPct: Double?, modifier: Modifier = Modifier) {
    val color = when {
        deltaPct == null -> Color.Gray
        deltaPct > 1.0 -> StatusOver
        deltaPct < -1.0 -> StatusUnder
        else -> StatusOk
    }
    val sign = if ((deltaPct ?: 0.0) >= 0) "+" else ""
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Δ ultimi 30 giorni",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Text(
                text = if (deltaPct != null) "$sign%.1f%%".format(deltaPct) else "—",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Sali sulla bilancia per la prima pesata",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

private fun bmiColor(bmi: Double, status: MetricStatus): Color = when (status) {
    MetricStatus.UNDER -> StatusUnder
    MetricStatus.OPTIMAL -> StatusOk
    MetricStatus.OVER -> if (bmi >= 30) StatusOver else GaugeOrange
    MetricStatus.UNKNOWN -> Color.Gray
}

private fun formatTime(t: Instant): String =
    LocalDateTime.ofInstant(t, ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))

/** Parser ISO local-time → Instant, usato per i punti del trend. */
private fun parseHistoryInstant(iso: String): Instant = try {
    LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .atZone(ZoneId.systemDefault())
        .toInstant()
} catch (_: Exception) {
    Instant.now()
}

// --- mock data per preview/sviluppo ---

private fun sampleSnapshot() = WeighingSnapshot(
    weightKg = 67.9,
    weightMinKg = 61.0,
    weightMaxKg = 69.0,
    weightStatus = MetricStatus.OPTIMAL,
    bmi = 22.9,
    bmiStatus = MetricStatus.OPTIMAL,
    fat = Metric("fat", "Massa grassa", 28.4, "%", 14.0, 24.0, MetricStatus.OVER),
    water = Metric("water", "Acqua corporea", 50.4, "%", 50.0, 65.0, MetricStatus.OPTIMAL),
    muscle = Metric("muscle", "Massa muscolare", 21.1, "kg", 18.0, 25.0, MetricStatus.OPTIMAL),
    bone = Metric("bone", "Massa ossea", 2.6, "kg", 2.0, 3.5, MetricStatus.OPTIMAL),
    visceral = Metric("visc", "Grasso viscerale", 10.0, "", 1.0, 12.0, MetricStatus.OPTIMAL),
    basalKcal = 1611,
    monthlyChangePercent = -0.7,
    measuredAt = Instant.now(),
    isComplete = true,
)

private fun sampleTrend(): List<TrendPoint> {
    val now = Instant.now()
    val values = listOf(68.4, 68.2, 67.9, 67.8, 68.1, 68.0, 67.9, 67.7, 67.5, 67.6, 67.8, 67.9)
    return values.mapIndexed { i, v ->
        TrendPoint(now.minusSeconds(((values.size - i - 1) * 86400L * 3)), v)
    }
}

package it.mercuri.bilancia.domain

import java.time.Instant

/** Profilo utente attivo sul telefono (single-user). */
enum class Profile(val slug: String, val displayName: String) {
    MICHELE("michele", "Michele"),
    MARIA_LUISA("maria_luisa", "Maria Luisa"),
    MATILDE("matilde", "Matilde");

    val mqttTopic: String get() = "bilancia_mondeer/peso_$slug/state"

    companion object {
        fun fromSlug(slug: String?): Profile? = entries.firstOrNull { it.slug == slug }
    }
}

/** Stato di una metrica rispetto al suo range ottimale. */
enum class MetricStatus {
    UNDER, OPTIMAL, OVER, UNKNOWN;

    val labelIt: String get() = when (this) {
        UNDER -> "Basso"
        OPTIMAL -> "Ottimale"
        OVER -> "Alto"
        UNKNOWN -> "—"
    }

    val emoji: String get() = when (this) {
        UNDER -> "🔵"
        OPTIMAL -> "✅"
        OVER -> "⚠️"
        UNKNOWN -> "—"
    }
}

/** Singola metrica biometrica con range, stato e unità. */
data class Metric(
    val id: String,
    val label: String,
    val value: Double?,
    val unit: String,
    val rangeMin: Double?,
    val rangeMax: Double?,
    val status: MetricStatus = MetricStatus.UNKNOWN,
)

/** Snapshot della pesata più recente (tutti i campi facoltativi
 *  perché un record preliminary può avere solo il peso). */
data class WeighingSnapshot(
    val weightKg: Double,
    val weightMinKg: Double,                // range "peso forma" del profilo
    val weightMaxKg: Double,
    val weightStatus: MetricStatus,         // dove cade il peso vs il range
    val bmi: Double?,
    val bmiStatus: MetricStatus,
    val fat: Metric,                        // grasso %
    val water: Metric,                      // acqua %
    val muscle: Metric,                     // muscoli kg
    val bone: Metric,                       // ossa kg
    val visceral: Metric,                   // grasso viscerale (intero)
    val basalKcal: Int?,                    // BMR
    val monthlyChangePercent: Double?,
    val measuredAt: Instant,
    val isComplete: Boolean,
)

/** Singolo punto della time series (per i grafici tendenza). */
data class TrendPoint(
    val timestamp: Instant,
    val value: Double,
)

/** Periodo del grafico tendenza. */
enum class TrendPeriod(val labelIt: String, val days: Int) {
    WEEK("7 giorni", 7),
    MONTH("1 mese", 30),
    YEAR("1 anno", 365),
}

/** Quale metrica visualizzare nel grafico. */
enum class TrendMetric(val labelIt: String, val unit: String) {
    WEIGHT("Peso", "kg"),
    BMI("IMC", ""),
    FAT("Grasso", "%"),
    WATER("Acqua", "%"),
    MUSCLE("Muscoli", "kg"),
    BONE("Ossa", "kg"),
    VISCERAL("Viscerale", ""),
}

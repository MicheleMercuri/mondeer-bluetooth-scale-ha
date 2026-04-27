package it.mercuri.bilancia.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.mercuri.bilancia.data.AppPrefs
import it.mercuri.bilancia.data.db.WeighingEntity
import it.mercuri.bilancia.data.repository.WeighingRepository
import it.mercuri.bilancia.domain.Metric
import it.mercuri.bilancia.domain.MetricStatus
import it.mercuri.bilancia.domain.Profile
import it.mercuri.bilancia.domain.TrendPoint
import it.mercuri.bilancia.domain.WeighingSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class HomeState(
    val profile: Profile,
    val snapshot: WeighingSnapshot?,
    /** History grezzo. La selezione metrica nel chart sceglie il campo
     *  giusto da queste entità (peso/bmi/fat/water/muscle/bone/viscerale). */
    val history: List<WeighingEntity>,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel @Inject constructor(
    private val repo: WeighingRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    init {
        // Avvia il subscriber MQTT (idempotente: se già started, no-op)
        repo.ensureStarted()
    }

    private val profile: Profile = prefs.selectedProfile ?: Profile.MICHELE

    private val latest: Flow<WeighingEntity?> = repo.latest(profile)
    private val last90Days: Flow<List<WeighingEntity>> = repo.range(
        profile = profile,
        sinceUnix = Instant.now().minusSeconds(90 * 86400L).epochSecond
    )

    val state: StateFlow<HomeState> = latest.flatMapLatest { lastEntity ->
        last90Days.map { history ->
            HomeState(
                profile = profile,
                snapshot = lastEntity?.toSnapshot(profile),
                history = history,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeState(profile, null, emptyList())
    )
}

// Range "ottimali" hardcoded per ciascun profilo della famiglia.
// Questi valori dovrebbero essere tenuti allineati con quelli usati nel
// listener Python (FAMILY_PROFILES) e nei sensor template di HA.
private fun rangesFor(profile: Profile): ProfileRanges = when (profile) {
    Profile.MICHELE -> ProfileRanges(
        weightMin = 61.0, weightMax = 69.0,
        bmiMin = 18.5, bmiMax = 25.0,
        fatMin = 14.0, fatMax = 24.0,
        waterMin = 50.0, waterMax = 65.0,
        muscleMin = 18.0, muscleMax = 25.0,
        boneMin = 2.0, boneMax = 3.5,
        visceralMin = 1.0, visceralMax = 12.0,
    )
    Profile.MARIA_LUISA -> ProfileRanges(
        weightMin = 49.0, weightMax = 55.0,
        bmiMin = 18.5, bmiMax = 25.0,
        fatMin = 21.0, fatMax = 33.0,
        waterMin = 45.0, waterMax = 60.0,
        muscleMin = 14.0, muscleMax = 20.0,
        boneMin = 1.8, boneMax = 3.0,
        visceralMin = 1.0, visceralMax = 9.0,
    )
    Profile.MATILDE -> ProfileRanges(
        weightMin = 53.0, weightMax = 60.0,
        bmiMin = 18.5, bmiMax = 25.0,
        fatMin = 21.0, fatMax = 33.0,
        waterMin = 50.0, waterMax = 65.0,
        muscleMin = 14.0, muscleMax = 20.0,
        boneMin = 1.8, boneMax = 3.0,
        visceralMin = 1.0, visceralMax = 6.0,
    )
}

private data class ProfileRanges(
    val weightMin: Double, val weightMax: Double,
    val bmiMin: Double, val bmiMax: Double,
    val fatMin: Double, val fatMax: Double,
    val waterMin: Double, val waterMax: Double,
    val muscleMin: Double, val muscleMax: Double,
    val boneMin: Double, val boneMax: Double,
    val visceralMin: Double, val visceralMax: Double,
)

private fun status(value: Double?, min: Double, max: Double): MetricStatus {
    val v = value ?: return MetricStatus.UNKNOWN
    return when {
        v < min -> MetricStatus.UNDER
        v > max -> MetricStatus.OVER
        else -> MetricStatus.OPTIMAL
    }
}

private fun parseInstant(iso: String): Instant = try {
    // Il listener Python scrive `measured_at` con `datetime.now().isoformat()`
    // che è in LOCAL time naive. Convertiamo usando il TZ del telefono
    // (assumendo che il telefono e il PC del listener siano nello stesso
    // fuso, cosa vera per uso domestico). Prima usavamo UTC -> 2h di errore.
    LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .atZone(ZoneId.systemDefault())
        .toInstant()
} catch (_: Exception) {
    Instant.now()
}

private fun WeighingEntity.toSnapshot(profile: Profile): WeighingSnapshot {
    val r = rangesFor(profile)
    return WeighingSnapshot(
        weightKg = weightKg,
        weightMinKg = r.weightMin,
        weightMaxKg = r.weightMax,
        weightStatus = status(weightKg, r.weightMin, r.weightMax),
        bmi = bmi,
        bmiStatus = status(bmi, r.bmiMin, r.bmiMax),
        fat = Metric("fat", "Massa grassa", fatPercent, "%", r.fatMin, r.fatMax,
            status(fatPercent, r.fatMin, r.fatMax)),
        water = Metric("water", "Acqua corporea", waterPercent, "%", r.waterMin, r.waterMax,
            status(waterPercent, r.waterMin, r.waterMax)),
        muscle = Metric("muscle", "Massa muscolare", muscleKg, "kg", r.muscleMin, r.muscleMax,
            status(muscleKg, r.muscleMin, r.muscleMax)),
        bone = Metric("bone", "Massa ossea", boneKg, "kg", r.boneMin, r.boneMax,
            status(boneKg, r.boneMin, r.boneMax)),
        visceral = Metric("visc", "Grasso viscerale",
            visceralFat?.toDouble(), "", r.visceralMin, r.visceralMax,
            status(visceralFat?.toDouble(), r.visceralMin, r.visceralMax)),
        basalKcal = basalKcal,
        monthlyChangePercent = monthlyChangePercent,
        measuredAt = parseInstant(measuredAtIso),
        isComplete = isComplete,
    )
}

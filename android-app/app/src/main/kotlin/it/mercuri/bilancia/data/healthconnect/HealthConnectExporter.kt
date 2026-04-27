package it.mercuri.bilancia.data.healthconnect

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import dagger.hilt.android.qualifiers.ApplicationContext
import it.mercuri.bilancia.data.db.WeighingEntity
import it.mercuri.bilancia.data.log.InAppLog
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge verso Health Connect. Quando arriva una pesata `is_complete=true`,
 * scriviamo i record corrispondenti in HC. Le altre app (Samsung Health,
 * Google Fit, Fitbit, MyFitnessPal, ...) li leggono da lì in automatico.
 *
 * Setup requirements:
 *  1. Health Connect installata sul telefono (preinstallata su Android 14+).
 *  2. Permessi WRITE_* concessi dall'utente — chiesti via UI Settings.
 *  3. Toggle `AppPrefs.healthConnectEnabled = true`.
 */
@Singleton
class HealthConnectExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client: HealthConnectClient? by lazy {
        runCatching {
            val status = HealthConnectClient.getSdkStatus(context)
            InAppLog.add(TAG, "SDK status=$status (1=AVAILABLE, 2=UNAVAILABLE, 3=UPDATE_REQUIRED)")
            if (status == HealthConnectClient.SDK_AVAILABLE) {
                val c = HealthConnectClient.getOrCreate(context)
                InAppLog.add(TAG, "HealthConnectClient creato OK")
                c
            } else {
                InAppLog.add(TAG, "HC non SDK_AVAILABLE → client = null")
                null
            }
        }.onFailure { e ->
            InAppLog.add(TAG, "client init FAILED: ${e.message}")
        }.getOrNull()
    }

    fun isAvailable(): Boolean = client != null

    /** SDK status code (per UI di diagnostica). */
    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    /** Lista dei permessi WRITE che chiediamo all'utente. */
    val permissions: Set<String> = setOf(
        WeightRecord::class,
        BodyFatRecord::class,
        BoneMassRecord::class,
        BasalMetabolicRateRecord::class,
        BodyWaterMassRecord::class,
        LeanBodyMassRecord::class,
    ).mapTo(mutableSetOf()) { kClass ->
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(kClass)
    }

    /** Activity result contract per richiedere i permessi. */
    val requestPermissionContract = PermissionController.createRequestPermissionResultContract()

    suspend fun hasAllPermissions(): Boolean {
        val c = client ?: run {
            InAppLog.add(TAG, "hasAllPermissions: client null")
            return false
        }
        return runCatching {
            val granted = c.permissionController.getGrantedPermissions()
            val missing = permissions - granted
            InAppLog.add(TAG, "permessi granted=${granted.size}/${permissions.size}" +
                if (missing.isEmpty()) " (tutti)" else " missing=${missing.size}")
            missing.isEmpty()
        }.onFailure {
            InAppLog.add(TAG, "hasAllPermissions FAILED: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * Scrive in HC tutti i campi disponibili dell'entità. No-op se HC non
     * disponibile, permessi mancanti, o `isComplete=false`. Best-effort:
     * un errore singolo su un record non blocca gli altri.
     */
    /**
     * Test write: scrive un singolo WeightRecord di test (peso=70.0kg
     * timestamp=now) per verificare che la pipeline WeighAi → HC funzioni.
     * Ritorna messaggio human-readable per la UI.
     */
    suspend fun testWrite(): String {
        val c = client ?: return "❌ Health Connect non disponibile sul device"
        if (!hasAllPermissions()) return "❌ Permessi Health Connect mancanti"
        return try {
            val now = Instant.now()
            val zone = ZoneId.systemDefault().rules.getOffset(now)
            c.insertRecords(listOf(
                WeightRecord(
                    time = now,
                    zoneOffset = zone,
                    weight = Mass.kilograms(70.0),
                    metadata = Metadata.manualEntry(),
                )
            ))
            "✅ Scrittura riuscita: 70.0 kg (now). Apri Health Connect → " +
                "Cronologia → Peso per verificare."
        } catch (e: Exception) {
            Log.e(TAG, "testWrite failed", e)
            "❌ Errore: ${e.message}"
        }
    }

    /**
     * Esporta una lista di entità — usato dal bottone "Sincronizza ora" per
     * fare backfill delle pesate che esistevano prima che l'utente
     * attivasse Health Connect. Ritorna il numero di entità effettivamente
     * scritte (con BIA completa).
     */
    suspend fun exportBatch(entities: List<WeighingEntity>): Int {
        var n = 0
        for (e in entities) {
            try {
                if (e.isComplete) {
                    export(e)
                    n++
                }
            } catch (ex: Exception) {
                Log.w(TAG, "exportBatch entry failed: ${ex.message}", ex)
            }
        }
        return n
    }

    suspend fun export(entity: WeighingEntity) {
        Log.i(TAG, "export() called for ts=${entity.scaleTimestampUnix} isComplete=${entity.isComplete}")
        if (!entity.isComplete) {
            Log.i(TAG, "skipping: not complete")
            return
        }
        val c = client ?: run {
            Log.w(TAG, "HC not available, skipping export")
            return
        }
        if (!hasAllPermissions()) {
            Log.w(TAG, "HC missing permissions, skipping export")
            return
        }

        val ts = parseInstant(entity.measuredAtIso)
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(ts)
        // HC 1.1.0-alpha07 non espone i factory `Metadata.manualEntry()`
        // (introdotti in alpha10). Costruttore base è ok: defaults
        // (id="", dataOrigin app, recordingMethod=UNKNOWN) sono accettati.
        val meta = Metadata.manualEntry()

        val records = buildList {
            add(WeightRecord(
                time = ts,
                zoneOffset = zoneOffset,
                weight = Mass.kilograms(entity.weightKg),
                metadata = meta,
            ))
            entity.fatPercent?.let {
                add(BodyFatRecord(
                    time = ts,
                    zoneOffset = zoneOffset,
                    percentage = Percentage(it),
                    metadata = meta,
                ))
            }
            entity.boneKg?.let {
                add(BoneMassRecord(
                    time = ts,
                    zoneOffset = zoneOffset,
                    mass = Mass.kilograms(it),
                    metadata = meta,
                ))
            }
            entity.basalKcal?.let { kcal ->
                // BasalMetabolicRateRecord è un rate (W o kcal/day). Da kcal/day
                // si ottiene il "metabolic rate" istantaneo come kcal/day.
                add(BasalMetabolicRateRecord(
                    time = ts,
                    zoneOffset = zoneOffset,
                    basalMetabolicRate = Power.kilocaloriesPerDay(kcal.toDouble()),
                    metadata = meta,
                ))
            }
            // BodyWaterMass: HC vuole massa in kg, non %. Convertiamo.
            entity.waterPercent?.let { pct ->
                val waterKg = entity.weightKg * pct / 100.0
                add(BodyWaterMassRecord(
                    time = ts,
                    zoneOffset = zoneOffset,
                    mass = Mass.kilograms(waterKg),
                    metadata = meta,
                ))
            }
            // LeanBodyMass = peso - massa grassa.
            entity.fatPercent?.let { fatPct ->
                val leanKg = entity.weightKg * (1.0 - fatPct / 100.0)
                add(LeanBodyMassRecord(
                    time = ts,
                    zoneOffset = zoneOffset,
                    mass = Mass.kilograms(leanKg),
                    metadata = meta,
                ))
            }
        }

        try {
            c.insertRecords(records)
            Log.i(TAG, "HC export OK: ${records.size} records for ts=${entity.scaleTimestampUnix}")
        } catch (e: Exception) {
            Log.e(TAG, "HC insertRecords failed: ${e.message}", e)
        }
    }

    private fun parseInstant(iso: String): Instant = try {
        // Listener Python scrive local time naive; usiamo il TZ del device
        // per la conversione a Instant UTC corretto.
        LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
    } catch (_: Exception) {
        Instant.now()
    }

    companion object {
        private const val TAG = "HealthConnectExporter"
    }
}

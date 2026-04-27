package it.mercuri.bilancia.data.repository

import it.mercuri.bilancia.data.AppPrefs
import it.mercuri.bilancia.data.db.WeighingDao
import it.mercuri.bilancia.data.db.WeighingEntity
import it.mercuri.bilancia.data.healthconnect.HealthConnectExporter
import it.mercuri.bilancia.data.mqtt.MqttSubscriber
import it.mercuri.bilancia.domain.Profile
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Punto unico di accesso alle pesate dell'utente attivo.
 *
 * - Avvia in lazy il subscriber MQTT al broker configurato (host+credenziali
 *   da [AppPrefs])
 * - Ogni nuovo messaggio viene salvato in Room (upsert con PK composta:
 *   `(profileSlug, scaleTimestampUnix)` ⇒ idempotente sui retain)
 * - La UI legge il latest e lo storico via [WeighingDao] (Flow)
 *
 * Lo scope del subscriber è "application-wide" (sopravvive a config
 * change/Activity recreate ma viene fermato se il processo è killato).
 */
@Singleton
class WeighingRepository @Inject constructor(
    private val dao: WeighingDao,
    private val prefs: AppPrefs,
    private val healthConnect: HealthConnectExporter,
) {
    // SupervisorJob: una eccezione in un child non cancella altri child né lo
    // scope. CoroutineExceptionHandler: cattura tutto quello che riemerge,
    // così l'app non crasha mai per problemi di rete/MQTT.
    private val errorHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("WeighingRepository", "Unhandled in repo scope: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)
    private var subscriberJob: Job? = null

    /** Avvia (una sola volta) il subscriber MQTT per il profilo configurato. */
    @Synchronized
    fun ensureStarted() {
        if (subscriberJob?.isActive == true) return
        val profile = prefs.selectedProfile ?: return
        if (prefs.mqttHost.isBlank()) return
        val subscriber = MqttSubscriber(
            host = prefs.mqttHost,
            port = prefs.mqttPort,
            username = prefs.mqttUser,
            password = prefs.mqttPassword,
        )
        subscriberJob = scope.launch {
            subscriber.observe(profile.slug)
                .catch { e ->
                    Log.e("WeighingRepository", "MQTT flow error: ${e.message}", e)
                }
                .collect { entity ->
                    dao.upsert(entity)
                    // Best-effort export verso Health Connect (Samsung
                    // Health, Google Fit, ecc. leggono da lì). No-op se HC
                    // non disponibile, permessi mancanti o is_complete=false.
                    if (prefs.healthConnectEnabled) {
                        try {
                            healthConnect.export(entity)
                        } catch (e: Exception) {
                            Log.w("WeighingRepository",
                                "HC export failed: ${e.message}", e)
                        }
                    }
                }
        }
    }

    /**
     * Forza una riconnessione MQTT: cancella il flow corrente e ne avvia uno
     * nuovo. Usato a `onResume` dell'Activity e quando l'app viene aperta da
     * deep link `weighai://` (push HA su pesata completa).
     *
     * Senza questo, dopo Doze/standby Android la connessione HiveMQ resta
     * "morta" finché non si riavvia il processo: il retained sul broker non
     * viene riconsegnato e l'utente vede dati vecchi.
     */
    @Synchronized
    fun refresh() {
        subscriberJob?.cancel()
        subscriberJob = null
        ensureStarted()
    }

    fun latest(profile: Profile): Flow<WeighingEntity?> =
        dao.latestFor(profile.slug).distinctUntilChanged()

    fun range(profile: Profile, sinceUnix: Long): Flow<List<WeighingEntity>> =
        dao.rangeFor(profile.slug, sinceUnix).distinctUntilChanged()

    suspend fun count(profile: Profile): Int = dao.countFor(profile.slug)

    /**
     * Backfill verso Health Connect: legge da Room le pesate degli ultimi
     * `days` giorni, le esporta tutte (skip preliminary). Usato dal bottone
     * "Sincronizza ora" del Setup. Ritorna il count effettivo esportato.
     */
    suspend fun backfillHealthConnect(profile: Profile, days: Int = 30): Int {
        val sinceUnix = java.time.Instant.now()
            .minusSeconds(days * 86400L).epochSecond
        val entities = dao.rangeForOnce(profile.slug, sinceUnix)
        return healthConnect.exportBatch(entities)
    }
}

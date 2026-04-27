package it.mercuri.bilancia.data.mqtt

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import it.mercuri.bilancia.data.db.WeighingEntity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Sottoscrittore MQTT del topic della pesata per UN profilo.
 *
 * Espone un [Flow] di [WeighingEntity] che emette ogni volta che il
 * broker pubblica un nuovo state. Resiliente: in caso di
 * timeout/errore di connect non crasha né chiude il flow, riprova
 * dopo backoff.
 */
class MqttSubscriber(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun observe(profileSlug: String): Flow<WeighingEntity> = callbackFlow {
        val topic = "bilancia_mondeer/peso_$profileSlug/state"
        var client: Mqtt3AsyncClient? = null

        // Loop di connessione con backoff: in caso di timeout/errore aspetta
        // e ritenta. Il flow resta aperto, l'app non crasha.
        suspend fun connectAndSubscribe() {
            val clientId = "weighai-${profileSlug}-${UUID.randomUUID()}"
            val c: Mqtt3AsyncClient = MqttClient.builder()
                .useMqttVersion3()
                .identifier(clientId)
                .serverHost(host)
                .serverPort(port)
                .automaticReconnectWithDefaultConfig()
                .buildAsync()

            try {
                val builder = c.connectWith().keepAlive(60).cleanSession(true)
                val finalBuilder = if (username.isNotBlank()) {
                    builder.simpleAuth()
                        .username(username)
                        .password(password.toByteArray())
                        .applySimpleAuth()
                } else builder
                finalBuilder.send().get(15, java.util.concurrent.TimeUnit.SECONDS)

                Log.i(TAG, "MQTT connected to $host:$port (clientId=$clientId)")

                c.subscribeWith()
                    .topicFilter(topic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .callback { publish ->
                        try {
                            val payload = publish.payload
                                .map { java.nio.charset.StandardCharsets.UTF_8.decode(it).toString() }
                                .orElse(null)
                                ?: return@callback
                            val parsed = json.decodeFromString<MqttWeighingPayload>(payload)
                            trySend(parsed.toEntity(profileSlug))
                        } catch (e: Exception) {
                            Log.w(TAG, "Bad payload on $topic: ${e.message}")
                        }
                    }
                    .send()
                    .get(10, java.util.concurrent.TimeUnit.SECONDS)

                Log.i(TAG, "Subscribed to $topic")
                client = c
            } catch (e: Exception) {
                Log.w(TAG, "MQTT connect/subscribe failed: ${e.javaClass.simpleName}: ${e.message}")
                try { c.disconnect() } catch (_: Exception) {}
                throw e
            }
        }

        var attempt = 0
        while (isActive && client == null) {
            try {
                connectAndSubscribe()
            } catch (_: Exception) {
                attempt++
                val backoffSec = (15L * attempt).coerceAtMost(120L)
                Log.w(TAG, "MQTT retry in ${backoffSec}s (attempt $attempt)")
                delay(backoffSec * 1000)
            }
        }

        awaitClose {
            try {
                client?.disconnect()
                Log.i(TAG, "MQTT disconnected (flow cancelled)")
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "MqttSubscriber"
    }
}

/**
 * Mappatura del JSON pubblicato dal listener Python sul topic
 * `bilancia_mondeer/peso_<slug>/state` (vedi ha_push.py::push_weight()).
 */
@Serializable
private data class MqttWeighingPayload(
    @SerialName("weight_kg") val weightKg: Double,
    @SerialName("fat_percent") val fatPercent: Double? = null,
    @SerialName("water_percent") val waterPercent: Double? = null,
    @SerialName("bone_kg") val boneKg: Double? = null,
    @SerialName("muscle_kg") val muscleKg: Double? = null,
    @SerialName("visceral_fat") val visceralFat: Int? = null,
    @SerialName("calorie_kcal") val calorieKcal: Int? = null,
    @SerialName("bmi") val bmi: Double? = null,
    @SerialName("is_complete") val isComplete: Boolean = false,
    @SerialName("measured_at") val measuredAt: String,
    @SerialName("scale_timestamp_unix") val scaleTimestampUnix: Long,
    @SerialName("monthly_change_pct") val monthlyChangePercent: Double? = null,
) {
    fun toEntity(profileSlug: String) = WeighingEntity(
        profileSlug = profileSlug,
        scaleTimestampUnix = scaleTimestampUnix,
        measuredAtIso = measuredAt,
        weightKg = weightKg,
        fatPercent = fatPercent,
        waterPercent = waterPercent,
        muscleKg = muscleKg,
        boneKg = boneKg,
        visceralFat = visceralFat,
        basalKcal = calorieKcal,
        bmi = bmi,
        monthlyChangePercent = monthlyChangePercent,
        isComplete = isComplete,
    )
}

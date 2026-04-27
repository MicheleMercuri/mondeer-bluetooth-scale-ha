package it.mercuri.bilancia.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import it.mercuri.bilancia.domain.Profile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage cifrato (Android Keystore-backed) per:
 *  - credenziali MQTT (host/port/user/password)
 *  - profilo selezionato (chi è il proprietario di questo telefono)
 *  - flag di setup completato
 *
 * Usato da SetupWizard per salvare e da MqttClient per leggere.
 */
@Singleton
class AppPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "weighai_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var setupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETE, value).apply()

    var mqttHost: String
        get() = prefs.getString(KEY_MQTT_HOST, "homeassistant.local") ?: "homeassistant.local"
        set(value) = prefs.edit().putString(KEY_MQTT_HOST, value).apply()

    var mqttPort: Int
        get() = prefs.getInt(KEY_MQTT_PORT, 1883)
        set(value) = prefs.edit().putInt(KEY_MQTT_PORT, value).apply()

    var mqttUser: String
        get() = prefs.getString(KEY_MQTT_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_USER, value).apply()

    var mqttPassword: String
        get() = prefs.getString(KEY_MQTT_PASS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_PASS, value).apply()

    var selectedProfile: Profile?
        get() = Profile.fromSlug(prefs.getString(KEY_PROFILE, null))
        set(value) = prefs.edit().putString(KEY_PROFILE, value?.slug).apply()

    /** Toggle "Sincronizza con Health Connect" del Setup. */
    var healthConnectEnabled: Boolean
        get() = prefs.getBoolean(KEY_HC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HC_ENABLED, value).apply()

    /**
     * Altezza profilo in cm — usata per calcolare BMI in Health Connect.
     * Default: profile-specific (User1 172, User2 169, User3 155).
     * Se l'utente cambia profilo, ricaricheremo il default.
     */
    var heightCm: Int
        get() = prefs.getInt(KEY_HEIGHT_CM, 170)
        set(value) = prefs.edit().putInt(KEY_HEIGHT_CM, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_MQTT_HOST = "mqtt_host"
        private const val KEY_MQTT_PORT = "mqtt_port"
        private const val KEY_MQTT_USER = "mqtt_user"
        private const val KEY_MQTT_PASS = "mqtt_password"
        private const val KEY_PROFILE = "profile"
        private const val KEY_HC_ENABLED = "hc_enabled"
        private const val KEY_HEIGHT_CM = "height_cm"
    }
}

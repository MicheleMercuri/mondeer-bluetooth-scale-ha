package it.mercuri.bilancia.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.mercuri.bilancia.data.AppPrefs
import it.mercuri.bilancia.data.healthconnect.HealthConnectExporter
import it.mercuri.bilancia.data.repository.WeighingRepository
import it.mercuri.bilancia.domain.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupState(
    val mqttHost: String = "homeassistant.local",
    val mqttPort: String = "1883",
    val mqttUser: String = "",
    val mqttPass: String = "",
    val profile: Profile? = null,
    val healthConnectEnabled: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val prefs: AppPrefs,
    val healthConnect: HealthConnectExporter,
    private val repo: WeighingRepository,
) : ViewModel() {

    /** Result del backfill HC (pesate effettivamente scritte). null = idle. */
    private val _backfillResult = MutableStateFlow<Int?>(null)
    val backfillResult: StateFlow<Int?> = _backfillResult.asStateFlow()

    fun backfillHealthConnect() {
        val profile = prefs.selectedProfile ?: return
        viewModelScope.launch {
            _backfillResult.value = -1 // -1 = "in corso"
            val n = runCatching {
                repo.backfillHealthConnect(profile, days = 30)
            }.getOrElse {
                android.util.Log.e("SetupVM", "backfill failed: ${it.message}", it)
                0
            }
            _backfillResult.value = n
        }
    }

    /** Test write Health Connect — restituisce stringa human-readable. */
    private val _hcTestResult = MutableStateFlow<String?>(null)
    val hcTestResult: StateFlow<String?> = _hcTestResult.asStateFlow()

    fun testHealthConnectWrite() {
        viewModelScope.launch {
            _hcTestResult.value = "⏳ Scrivo test record..."
            val msg = runCatching {
                healthConnect.testWrite()
            }.getOrElse { "❌ Eccezione: ${it.message}" }
            _hcTestResult.value = msg
        }
    }


    /** Disponibilità Health Connect sul device (HC SDK + provider installato). */
    val healthConnectAvailable: Boolean get() = healthConnect.isAvailable()

    private val _hcPermissionsGranted = MutableStateFlow(false)
    val hcPermissionsGranted: StateFlow<Boolean> = _hcPermissionsGranted.asStateFlow()

    init {
        refreshHcPermissions()
    }

    fun refreshHcPermissions() {
        viewModelScope.launch {
            _hcPermissionsGranted.value = healthConnect.hasAllPermissions()
        }
    }

    private val _state = MutableStateFlow(
        SetupState(
            mqttHost = prefs.mqttHost.ifEmpty { "homeassistant.local" },
            mqttPort = prefs.mqttPort.toString(),
            mqttUser = prefs.mqttUser,
            mqttPass = prefs.mqttPassword,
            profile = prefs.selectedProfile,
            healthConnectEnabled = prefs.healthConnectEnabled,
        )
    )
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun setHost(v: String) = _state.update { it.copy(mqttHost = v.trim()) }
    fun setPort(v: String) = _state.update { it.copy(mqttPort = v.filter(Char::isDigit)) }
    fun setUser(v: String) = _state.update { it.copy(mqttUser = v.trim()) }
    fun setPass(v: String) = _state.update { it.copy(mqttPass = v) }
    fun setProfile(p: Profile) = _state.update { it.copy(profile = p) }
    fun setHealthConnectEnabled(v: Boolean) =
        _state.update { it.copy(healthConnectEnabled = v) }

    fun isValid(): Boolean {
        val s = _state.value
        return s.mqttHost.isNotBlank()
            && s.mqttPort.toIntOrNull()?.let { it in 1..65535 } == true
            && s.mqttUser.isNotBlank()
            && s.mqttPass.isNotEmpty()
            && s.profile != null
    }

    /** Persist e marca setup completato. */
    fun save() {
        val s = _state.value
        prefs.mqttHost = s.mqttHost
        prefs.mqttPort = s.mqttPort.toIntOrNull() ?: 1883
        prefs.mqttUser = s.mqttUser
        prefs.mqttPassword = s.mqttPass
        prefs.selectedProfile = s.profile
        prefs.healthConnectEnabled = s.healthConnectEnabled
        prefs.setupComplete = true
    }
}

package it.mercuri.bilancia.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import it.mercuri.bilancia.data.healthconnect.HealthConnectExporter
import it.mercuri.bilancia.data.log.InAppLog
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import it.mercuri.bilancia.domain.Profile
import it.mercuri.bilancia.ui.theme.BluePrimary
import it.mercuri.bilancia.ui.theme.BluePrimaryDark
import androidx.compose.foundation.text.KeyboardOptions

@Composable
fun SetupScreen(
    onComplete: () -> Unit,
    vm: SetupViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Header()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Connessione MQTT",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Text(
                text = "I dati arrivano dal broker MQTT di Home Assistant. " +
                    "Inserisci indirizzo IP e credenziali (puoi crearle in HA → " +
                    "Mosquitto broker → Configuration → logins).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )

            OutlinedTextField(
                value = state.mqttHost,
                onValueChange = vm::setHost,
                label = { Text("Indirizzo broker") },
                placeholder = { Text("es. homeassistant.local") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.mqttPort,
                onValueChange = vm::setPort,
                label = { Text("Porta") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.mqttUser,
                onValueChange = vm::setUser,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = state.mqttPass,
                onValueChange = vm::setPass,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Nascondi password"
                                else "Mostra password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Tu chi sei?",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Text(
                text = "L'app mostra solo le pesate del proprietario di questo " +
                    "telefono. Niente dati degli altri familiari.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )

            Profile.entries.forEach { p ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = if (p == state.profile) 4.dp else 1.dp,
                    color = if (p == state.profile)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    onClick = { vm.setProfile(p) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = p == state.profile,
                            onClick = { vm.setProfile(p) }
                        )
                        Text(
                            text = p.displayName,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---- Sezione Health Connect ---------------------------------
            // Mostriamo SEMPRE la sezione, anche se HC non è disponibile —
            // così l'utente vede perché ("Health Connect non installato",
            // "aggiornamento richiesto", ecc.) invece di un nulla di fatto.
            val hcStatus = vm.healthConnect.sdkStatus()
            Text(
                text = "Sincronizzazione Health Connect",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Text(
                text = "Scrive ogni pesata in Health Connect: " +
                    "Samsung Health, Google Fit e altre app la leggeranno " +
                    "in automatico.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            if (!vm.healthConnectAvailable) {
                val msg = when (hcStatus) {
                    1 -> "OK"
                    2 -> "Health Connect non disponibile su questo dispositivo " +
                        "(richiede Android 8.0+)"
                    3 -> "Health Connect installato ma da aggiornare — " +
                        "aprilo dal Play Store"
                    else -> "Stato Health Connect: $hcStatus"
                }
                Text(
                    text = "⚠ $msg",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (vm.healthConnectAvailable) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Esporta in Health Connect",
                                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "Solo pesate con composizione corporea completa",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.55f),
                            )
                        }
                        Switch(
                            checked = state.healthConnectEnabled,
                            onCheckedChange = vm::setHealthConnectEnabled,
                        )
                    }
                }

                if (state.healthConnectEnabled) {
                    val hcGranted by vm.hcPermissionsGranted.collectAsState()
                    val launcher = rememberLauncherForActivityResult(
                        contract = vm.healthConnect.requestPermissionContract,
                    ) { granted ->
                        InAppLog.add("HC-UI",
                            "permission dialog returned, granted=${granted.size}")
                        vm.refreshHcPermissions()
                    }
                    if (!hcGranted) {
                        OutlinedButton(
                            onClick = {
                                val perms = vm.healthConnect.permissions
                                InAppLog.add("HC-UI",
                                    "click, ${perms.size} permessi:")
                                perms.forEach { p ->
                                    InAppLog.add("HC-UI", "  → $p")
                                }
                                try {
                                    launcher.launch(perms)
                                    InAppLog.add("HC-UI", "launcher.launch() chiamato")
                                } catch (e: Exception) {
                                    InAppLog.add("HC-UI",
                                        "launcher.launch() FAILED: ${e.message}")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Concedi permessi Health Connect")
                        }
                    } else {
                        Text(
                            "✅ Permessi concessi",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        // Backfill: esporta le pesate degli ultimi 30gg che
                        // erano già nel database PRIMA di attivare HC.
                        val backfillResult by vm.backfillResult.collectAsState()
                        OutlinedButton(
                            onClick = { vm.backfillHealthConnect() },
                            enabled = backfillResult != -1,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when (backfillResult) {
                                    -1 -> "Sincronizzo..."
                                    null -> "Sincronizza ultimi 30 giorni"
                                    else -> "Sincronizza ultimi 30 giorni"
                                }
                            )
                        }
                        backfillResult?.takeIf { it >= 0 }?.let { n ->
                            Text(
                                "$n pesate scritte in Health Connect",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.6f),
                            )
                        }
                        // Test write diagnostico
                        val hcTest by vm.hcTestResult.collectAsState()
                        OutlinedButton(
                            onClick = { vm.testHealthConnectWrite() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Test scrittura Health Connect")
                        }
                        hcTest?.let {
                            Text(
                                it,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            "Le pesate di Google Fit/Health appaiono solo se " +
                                "in quell'app hai abilitato Health Connect come " +
                                "sorgente dati.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = 0.5f),
                        )
                    }

                    // Debug log Health Connect — sempre visibile quando HC è
                    // abilitato. Permette all'utente di copiare e incollarmi
                    // il log per diagnosi remota.
                    Spacer(modifier = Modifier.height(6.dp))
                    DebugLogPanel()
                }
            }
            // --------------------------------------------------------------

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    vm.save()
                    onComplete()
                },
                enabled = vm.isValid(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Salva e continua", fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DebugLogPanel() {
    val lines by InAppLog.lines.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.85f),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Debug log",
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(
                    onClick = {
                        val text = lines.joinToString("\n")
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("WeighAi log", text))
                    },
                ) {
                    Text("Copia", fontSize = 11.sp, color = Color(0xFF80B4FF))
                }
                androidx.compose.material3.TextButton(
                    onClick = { InAppLog.clear() },
                ) {
                    Text("Pulisci", fontSize = 11.sp, color = Color(0xFFFF8080))
                }
            }
            if (lines.isEmpty()) {
                Text(
                    "(vuoto — clicca \"Concedi permessi\" o \"Test scrittura\" per vedere log)",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
            } else {
                lines.takeLast(15).forEach { line ->
                    Text(
                        text = line,
                        fontSize = 10.sp,
                        color = Color(0xFF9DEFA8),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Surface(modifier = Modifier.fillMaxWidth(), color = BluePrimary) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(BluePrimary, BluePrimaryDark)))
                .padding(top = 40.dp, bottom = 22.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "WeighAi",
                    color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Configurazione iniziale",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
                )
            }
        }
    }
}

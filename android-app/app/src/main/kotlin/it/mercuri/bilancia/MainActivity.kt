package it.mercuri.bilancia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import it.mercuri.bilancia.data.AppPrefs
import it.mercuri.bilancia.data.repository.WeighingRepository
import it.mercuri.bilancia.domain.Profile
import it.mercuri.bilancia.ui.home.HomeScreen
import it.mercuri.bilancia.ui.setup.SetupScreen
import it.mercuri.bilancia.ui.theme.BilanciaTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var repo: WeighingRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BilanciaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(prefs)
                }
            }
        }
    }

    // Riconnetti MQTT quando l'app torna in foreground. HiveMQ tende a
    // perdere la connessione durante Doze/standby Android: senza un refresh
    // esplicito al resume, l'utente vede dati vecchi finché non riavvia
    // l'app. Il refresh kill+restart del flow forza una nuova subscribe e
    // il broker rispedisce subito il retain corrente.
    override fun onResume() {
        super.onResume()
        repo.refresh()
    }
}

@Composable
private fun AppRoot(prefs: AppPrefs) {
    val navController = rememberNavController()
    // Track del profilo selezionato — quando cambia (setup completato) la
    // HomeScreen viene ricomposta con i dati giusti.
    var profile by remember { mutableStateOf(prefs.selectedProfile) }
    var setupDone by remember { mutableStateOf(prefs.setupComplete) }

    val startDestination = if (setupDone && profile != null) "home" else "setup"

    Scaffold { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("setup") {
                SetupScreen(onComplete = {
                    profile = prefs.selectedProfile
                    setupDone = true
                    navController.navigate("home") {
                        popUpTo("setup") { inclusive = true }
                    }
                })
            }
            composable("home") {
                HomeScreen(
                    profile = profile ?: Profile.USER1,
                    onSettingsClick = {
                        navController.navigate("setup")
                    },
                )
            }
        }
    }
}

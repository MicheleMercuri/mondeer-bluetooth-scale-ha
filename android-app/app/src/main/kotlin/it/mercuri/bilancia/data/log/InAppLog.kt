package it.mercuri.bilancia.data.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Buffer ring di log accessibile in-app — utile quando l'utente non ha
 * adb a disposizione. HealthConnectExporter chiama [add] per ogni evento
 * rilevante (init, permission check, export). SetupScreen mostra le
 * ultime [maxSize] righe in un'area selezionabile.
 *
 * Thread-safe: usa MutableStateFlow + sincronizzazione interna.
 */
object InAppLog {
    private const val maxSize = 100
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Synchronized
    fun add(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        Log.i(tag, msg)  // mantiene anche logcat per chi ha adb
        val cur = _lines.value
        val next = if (cur.size >= maxSize) cur.drop(1) + line else cur + line
        _lines.value = next
    }

    fun clear() {
        _lines.value = emptyList()
    }
}

package it.mercuri.bilancia.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Una pesata salvata localmente. La PK composta (profileSlug,
 * scaleTimestampUnix) garantisce idempotenza: se MQTT pubblica più volte
 * lo stesso retained message, l'INSERT OR IGNORE lo scarta.
 *
 * `isComplete = false` ⇒ record preliminary (fat/water/etc possono essere
 * NULL oppure ereditati dal merge nel listener Python).
 */
@Entity(
    tableName = "weighings",
    primaryKeys = ["profileSlug", "scaleTimestampUnix"],
    indices = [Index(value = ["profileSlug", "scaleTimestampUnix"])]
)
data class WeighingEntity(
    val profileSlug: String,
    val scaleTimestampUnix: Long,
    val measuredAtIso: String,
    val weightKg: Double,
    val fatPercent: Double?,
    val waterPercent: Double?,
    val muscleKg: Double?,
    val boneKg: Double?,
    val visceralFat: Int?,
    val basalKcal: Int?,
    val bmi: Double?,
    val monthlyChangePercent: Double?,
    val isComplete: Boolean,
)

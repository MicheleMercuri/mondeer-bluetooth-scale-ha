package it.mercuri.bilancia.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WeighingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WeighingEntity)

    @Query("""
        SELECT * FROM weighings
        WHERE profileSlug = :slug
        ORDER BY scaleTimestampUnix DESC
        LIMIT 1
    """)
    fun latestFor(slug: String): Flow<WeighingEntity?>

    @Query("""
        SELECT * FROM weighings
        WHERE profileSlug = :slug AND scaleTimestampUnix >= :sinceUnix
        ORDER BY scaleTimestampUnix ASC
    """)
    fun rangeFor(slug: String, sinceUnix: Long): Flow<List<WeighingEntity>>

    /** Lista one-shot (no Flow) per il backfill verso Health Connect. */
    @Query("""
        SELECT * FROM weighings
        WHERE profileSlug = :slug AND scaleTimestampUnix >= :sinceUnix
        ORDER BY scaleTimestampUnix ASC
    """)
    suspend fun rangeForOnce(slug: String, sinceUnix: Long): List<WeighingEntity>

    @Query("SELECT COUNT(*) FROM weighings WHERE profileSlug = :slug")
    suspend fun countFor(slug: String): Int

    @Query("DELETE FROM weighings WHERE profileSlug = :slug")
    suspend fun deleteAllFor(slug: String)
}

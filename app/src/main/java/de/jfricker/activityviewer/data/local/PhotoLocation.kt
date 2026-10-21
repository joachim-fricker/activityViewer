package de.jfricker.activityviewer.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Position und Land eines Fotos vom Handy (Cache, damit die EXIF-Daten nur einmal gelesen werden). */
@Entity(tableName = "photo_location")
data class PhotoLocationEntity(
    @PrimaryKey val id: Long,       // MediaStore-ID
    val modified: Long,             // DATE_MODIFIED (Sekunden), erkennt geaenderte Fotos
    val takenMs: Long,
    val lat: Double?,
    val lon: Double?,
    val country: String?            // ADM0_A3, z. B. CHE; null = ohne GPS oder ausserhalb aller Laender
)

data class PhotoKey(val id: Long, val modified: Long)

data class CountryStat(val country: String, val cnt: Int, val firstMs: Long, val lastMs: Long)

@Dao
interface PhotoLocationDao {
    @Query("SELECT id, modified FROM photo_location")
    suspend fun keys(): List<PhotoKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PhotoLocationEntity>)

    @Query("DELETE FROM photo_location WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM photo_location")
    suspend fun clear()

    @Query(
        """
        SELECT country, COUNT(*) AS cnt, MIN(takenMs) AS firstMs, MAX(takenMs) AS lastMs
        FROM photo_location
        WHERE country IS NOT NULL
        GROUP BY country
        ORDER BY cnt DESC
        """
    )
    fun countryStats(): Flow<List<CountryStat>>

    @Query("SELECT * FROM photo_location WHERE lat IS NOT NULL AND lon IS NOT NULL")
    suspend fun withPosition(): List<PhotoLocationEntity>

    @Query("SELECT * FROM photo_location WHERE country = :country ORDER BY takenMs DESC")
    suspend fun inCountry(country: String): List<PhotoLocationEntity>

    @Query("SELECT COUNT(*) FROM photo_location WHERE lat IS NOT NULL")
    fun withGpsCount(): Flow<Int>
}

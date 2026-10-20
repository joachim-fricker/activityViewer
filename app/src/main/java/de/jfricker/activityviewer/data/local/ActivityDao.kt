package de.jfricker.activityviewer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Query("SELECT id FROM activity")
    suspend fun allIds(): List<Long>

    @Query("SELECT COUNT(*) FROM activity")
    fun count(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(activity: ActivityEntity)

    /**
     * Summen je Kalenderjahr und Typ. Mit [cutoffKey] (siehe periodCutoffKey) werden nur
     * Aktivitaeten bis zum gleichen Tag im Jahr gezaehlt (Jahresvergleich "bis heute").
     */
    @Query(
        """
        SELECT year, typeKey,
               SUM(distanceM) AS distanceM,
               SUM(elevationGain) AS elevationGain,
               COUNT(*) AS cnt
        FROM activity
        WHERE (CAST(substr(startTime, 6, 2) AS INTEGER) - 1) * 100
              + CAST(substr(startTime, 9, 2) AS INTEGER) <= :cutoffKey
        GROUP BY year, typeKey
        ORDER BY year
        """
    )
    fun yearTypeSums(cutoffKey: Int): Flow<List<YearTypeSum>>

    /**
     * Wie [yearTypeSums], aber nach Wintersaison gruppiert: "year" ist das Startjahr der Saison.
     * Eine Saison beginnt am 1. des Monats [seasonStartMonth] (startTime = "yyyy-MM-dd HH:mm:ss").
     * [cutoffKey] begrenzt wie bei [yearTypeSums] auf den gleichen Tag innerhalb der Saison.
     */
    @Query(
        """
        SELECT CASE WHEN CAST(substr(startTime, 6, 2) AS INTEGER) >= :seasonStartMonth
                    THEN year ELSE year - 1 END AS year,
               typeKey,
               SUM(distanceM) AS distanceM,
               SUM(elevationGain) AS elevationGain,
               COUNT(*) AS cnt
        FROM activity
        WHERE typeKey IN (:types)
          AND ((CAST(substr(startTime, 6, 2) AS INTEGER) - :seasonStartMonth + 12) % 12) * 100
              + CAST(substr(startTime, 9, 2) AS INTEGER) <= :cutoffKey
        GROUP BY 1, typeKey
        ORDER BY 1
        """
    )
    fun seasonTypeSums(
        types: List<String>,
        seasonStartMonth: Int,
        cutoffKey: Int
    ): Flow<List<YearTypeSum>>

    @Query(
        """
        SELECT id, name, typeKey, startTime, distanceM, durationS, elevationGain, locationName,
               startLat, startLon
        FROM activity
        """
    )
    fun activityRows(): Flow<List<ActivityRow>>

    // ---- Track-Index fuer die Umkreissuche
    // trackSimplified: null = noch nicht geladen, "" = kein GPS-Track / Indoor

    @Query("SELECT id, typeKey, name, distanceM FROM activity WHERE trackSimplified IS NULL")
    suspend fun missingTrackIndex(): List<TrackIndexTodo>

    @Query("SELECT COUNT(*) FROM activity WHERE trackSimplified IS NULL")
    fun missingTrackIndexCount(): Flow<Int>

    @Query(
        """
        UPDATE activity
        SET minLat = :minLat, maxLat = :maxLat, minLon = :minLon, maxLon = :maxLon,
            trackSimplified = :track
        WHERE id = :id
        """
    )
    suspend fun updateTrackIndex(
        id: Long,
        minLat: Double?,
        maxLat: Double?,
        minLon: Double?,
        maxLon: Double?,
        track: String
    )

    /** Tracks, deren Bounding-Box die Such-Box schneidet (Vorfilter). */
    @Query(
        """
        SELECT id, trackSimplified FROM activity
        WHERE trackSimplified IS NOT NULL AND trackSimplified != ''
          AND maxLat >= :minLat AND minLat <= :maxLat
          AND maxLon >= :minLon AND minLon <= :maxLon
        """
    )
    suspend fun tracksInBox(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<TrackIndexRow>

    /** Alle Outdoor-Tracks (Indoor/kein GPS ist als "" gespeichert). */
    @Query(
        """
        SELECT id, typeKey, trackSimplified FROM activity
        WHERE trackSimplified IS NOT NULL AND trackSimplified != ''
        """
    )
    fun heatTracks(): Flow<List<HeatTrackRow>>

    @Query("SELECT * FROM activity WHERE id = :id")
    fun activity(id: Long): Flow<ActivityEntity?>
}

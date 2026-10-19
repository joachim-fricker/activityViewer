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

    @Query(
        """
        SELECT year, typeKey,
               SUM(distanceM) AS distanceM,
               SUM(elevationGain) AS elevationGain,
               COUNT(*) AS cnt
        FROM activity
        GROUP BY year, typeKey
        ORDER BY year
        """
    )
    fun yearTypeSums(): Flow<List<YearTypeSum>>

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

    @Query("SELECT * FROM activity WHERE id = :id")
    fun activity(id: Long): Flow<ActivityEntity?>
}

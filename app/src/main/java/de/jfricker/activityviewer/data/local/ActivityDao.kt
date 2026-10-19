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
        SELECT id, name, typeKey, startTime, distanceM, durationS, elevationGain, locationName
        FROM activity
        """
    )
    fun activityRows(): Flow<List<ActivityRow>>

    @Query("SELECT * FROM activity WHERE id = :id")
    fun activity(id: Long): Flow<ActivityEntity?>
}

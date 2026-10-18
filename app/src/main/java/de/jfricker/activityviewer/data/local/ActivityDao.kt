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
}

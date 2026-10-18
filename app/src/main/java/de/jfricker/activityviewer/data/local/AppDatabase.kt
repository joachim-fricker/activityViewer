package de.jfricker.activityviewer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ActivityEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
}

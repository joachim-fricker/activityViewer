package de.jfricker.activityviewer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ActivityEntity::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
}

/** v3: Bestzeiten Rad (5/10/20 km) - Spalten ergaenzen, bestehende Daten bleiben erhalten. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activity ADD COLUMN best5kS REAL")
        db.execSQL("ALTER TABLE activity ADD COLUMN best10kS REAL")
        db.execSQL("ALTER TABLE activity ADD COLUMN best20kS REAL")
    }
}

/** v4: Bestzeit 40 km und geschaetzte Leistung 10/20/30 s. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activity ADD COLUMN best40kS REAL")
        db.execSQL("ALTER TABLE activity ADD COLUMN bestP10W REAL")
        db.execSQL("ALTER TABLE activity ADD COLUMN bestP20W REAL")
        db.execSQL("ALTER TABLE activity ADD COLUMN bestP30W REAL")
    }
}

/** v5: verbessertes Leistungsmodell - geschaetzte Leistung neu berechnen lassen. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE activity SET bestP10W = NULL, bestP20W = NULL, bestP30W = NULL")
    }
}

/** v6: Leistung nur noch am Anstieg - neu berechnen lassen. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE activity SET bestP10W = NULL, bestP20W = NULL, bestP30W = NULL")
    }
}

/** v7: Leistung am Berg ueber 5/10/20 Minuten statt 10/20/30 Sekunden - Spalten umbenennen, neu berechnen. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activity RENAME COLUMN bestP10W TO bestP5mW")
        db.execSQL("ALTER TABLE activity RENAME COLUMN bestP20W TO bestP10mW")
        db.execSQL("ALTER TABLE activity RENAME COLUMN bestP30W TO bestP20mW")
        db.execSQL("UPDATE activity SET bestP5mW = NULL, bestP10mW = NULL, bestP20mW = NULL")
    }
}

/** v8: Aufzeichnungsgeraet (deviceId), wird fuer bestehende Touren nachgeladen. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activity ADD COLUMN deviceId INTEGER")
    }
}

package de.jfricker.activityviewer

import android.app.Application
import androidx.room.Room
import de.jfricker.activityviewer.data.ActivityRepository
import de.jfricker.activityviewer.data.LocationProvider
import de.jfricker.activityviewer.data.NetworkInfo
import de.jfricker.activityviewer.data.PhotoRepository
import de.jfricker.activityviewer.data.TokenStore
import de.jfricker.activityviewer.data.local.AppDatabase
import de.jfricker.activityviewer.data.local.MIGRATION_2_3
import de.jfricker.activityviewer.data.local.MIGRATION_3_4
import de.jfricker.activityviewer.data.local.MIGRATION_4_5
import de.jfricker.activityviewer.data.local.MIGRATION_5_6
import de.jfricker.activityviewer.data.local.MIGRATION_6_7
import de.jfricker.activityviewer.data.local.MIGRATION_7_8
import de.jfricker.activityviewer.data.remote.GitHubClient
import de.jfricker.activityviewer.data.remote.WorkflowClient
import org.maplibre.android.MapLibre

class ActivityViewerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this) // muss vor dem ersten MapView initialisiert sein
    }

    private val database by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "activities.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .fallbackToDestructiveMigration(true) // nur waehrend der Entwicklung
            .build()
    }
    val tokenStore by lazy { TokenStore(this) }
    val locationProvider by lazy { LocationProvider(this) }
    val networkInfo by lazy { NetworkInfo(this) }
    val photoRepository by lazy { PhotoRepository(this) }
    val repository by lazy {
        ActivityRepository(GitHubClient(), WorkflowClient(), database.activityDao())
    }
}

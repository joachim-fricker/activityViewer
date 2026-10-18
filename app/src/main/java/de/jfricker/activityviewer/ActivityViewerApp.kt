package de.jfricker.activityviewer

import android.app.Application
import androidx.room.Room
import de.jfricker.activityviewer.data.ActivityRepository
import de.jfricker.activityviewer.data.local.AppDatabase
import de.jfricker.activityviewer.data.remote.GitHubClient

class ActivityViewerApp : Application() {
    private val database by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "activities.db")
            .fallbackToDestructiveMigration(true) // nur waehrend der Entwicklung
            .build()
    }
    val repository by lazy { ActivityRepository(GitHubClient(), database.activityDao()) }
}

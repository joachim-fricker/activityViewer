package de.jfricker.activityviewer.data

import android.content.Context

/**
 * Speichert das GitHub-Token app-privat (Android-Sandbox).
 * Das Token gehoert NICHT in den Quellcode und nicht ins Repo.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun set(token: String) {
        prefs.edit().putString(KEY, token.trim()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "github_token"
    }
}

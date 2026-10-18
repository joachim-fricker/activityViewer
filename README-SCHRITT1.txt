Schritt 1: Sync (ids.txt -> Room)

Dieses ZIP enthaelt nur die Kotlin-Dateien unter
app/src/main/java/de/jfricker/activityviewer/
MainActivity.kt ersetzt die vorhandene Datei aus dem Template.

Manuell erledigen (bleibt bei dir, weil Versionen zu deinem Projekt passen muessen):
1. Root build.gradle.kts: Plugins KSP und kotlin("plugin.serialization") ergaenzen
2. app/build.gradle.kts: Plugins + Dependencies (Room, OkHttp, serialization, lifecycle-compose)
3. AndroidManifest.xml: <uses-permission android:name="android.permission.INTERNET" />
   und im <application>-Tag: android:name=".ActivityViewerApp"
Danach in Android Studio "Sync Now".

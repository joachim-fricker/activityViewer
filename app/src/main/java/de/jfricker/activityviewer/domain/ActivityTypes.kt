package de.jfricker.activityviewer.domain

/** Deutsche Kurzbezeichnungen der Garmin-typeKeys. */
private val TYPE_LABELS = mapOf(
    "cycling" to "Rad",
    "road_biking" to "Rennrad",
    "mountain_biking" to "MTB",
    "gravel_cycling" to "Gravel",
    "walking" to "Gehen",
    "running" to "Laufen",
    "mountaineering" to "Bergtour",
    "hiking" to "Wandern",
    "backcountry_skiing" to "Skitour",
    "other" to "Skitour" // alle "other" sind Skitouren
)

fun typeLabel(typeKey: String): String = TYPE_LABELS[typeKey] ?: typeKey

/** Garmin-Typen fuer Indoor-/virtuelle Radfahrten (z. B. Rouvy, Zwift, Rollentrainer). */
private val INDOOR_TYPES = setOf("virtual_ride", "indoor_cycling")

/**
 * Indoor-Aktivitaet? Rouvy liefert GPS-Koordinaten der virtuellen Strecke mit,
 * deshalb zusaetzlich am Namen erkennen.
 */
fun isIndoor(typeKey: String, name: String): Boolean =
    typeKey in INDOOR_TYPES || name.contains("rouvy", ignoreCase = true)

/** Wandern (ohne Laufen): hier interessiert keine Geschwindigkeit. */
private val NO_SPEED_TYPES = setOf("walking", "hiking", "mountaineering")

fun showsSpeed(typeKey: String): Boolean = typeKey !in NO_SPEED_TYPES

/** deviceId = 0: Geraet im Garmin-JSON nicht angegeben. */
const val NO_DEVICE = 0L

/** Die Uhr: ihre Max. Geschwindigkeit beim Radfahren ist unzuverlaessig (GPS-Spruenge). */
const val WATCH_DEVICE_ID = 3390593036L

/** Mehr ist bei keiner Aktivitaet realistisch, das ist ein GPS-Sprung. */
private const val MAX_PLAUSIBLE_SPEED_MS = 100.0 / 3.6

/**
 * Max. Geschwindigkeit, wenn sie glaubwuerdig ist, sonst null.
 * - Werte ueber 100 km/h werden immer verworfen.
 * - Radfahren: nur wenn das Geraet bekannt ist und nicht die Uhr (also Edge).
 *   Noch nicht nachgeladen (null) oder im JSON nicht angegeben (0) => nicht anzeigen.
 */
fun trustedMaxSpeed(typeKey: String, maxSpeed: Double?, deviceId: Long?): Double? {
    val v = maxSpeed?.takeIf { it > 0 } ?: return null
    if (v > MAX_PLAUSIBLE_SPEED_MS) return null
    if (typeKey in CYCLING_TYPES) {
        if (deviceId == null || deviceId == NO_DEVICE || deviceId == WATCH_DEVICE_ID) return null
    }
    return v
}

/** Touren, die nicht in die Rekorde gehoeren (versehentlich mit dem E-Bike aufgezeichnet). */
val RECORD_EXCLUDED_IDS = setOf(
    9304690413L,
    9107878454L,
    9110118441L,
    9076916831L,
    9125532196L
)

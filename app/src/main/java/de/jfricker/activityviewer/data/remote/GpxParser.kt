package de.jfricker.activityviewer.data.remote

import android.util.Xml
import de.jfricker.activityviewer.domain.GeoPoint
import de.jfricker.activityviewer.domain.TimedPoint
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.Instant
import java.time.format.DateTimeParseException

object GpxParser {
    /** Liest alle Trackpunkte (trkpt; falls keine vorhanden: Routenpunkte rtept). */
    fun parse(gpx: String): List<GeoPoint> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(gpx))
        val trk = ArrayList<GeoPoint>()
        val rte = ArrayList<GeoPoint>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val target = when (parser.name) {
                    "trkpt" -> trk
                    "rtept" -> rte
                    else -> null
                }
                if (target != null) {
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) target.add(GeoPoint(lat, lon))
                }
            }
            event = parser.next()
        }
        return trk.ifEmpty { rte }
    }

    /** Trackpunkte mit Zeitstempel (timeMs = 0, wenn der Punkt keine gueltige Zeit hat). */
    fun parseTimed(gpx: String): List<TimedPoint> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(gpx))
        val out = ArrayList<TimedPoint>()
        var lat: Double? = null
        var lon: Double? = null
        var time = 0L
        var ele: Double? = null
        var inPoint = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "trkpt" -> {
                        inPoint = true
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        time = 0L
                        ele = null
                    }
                    "ele" -> if (inPoint) ele = parser.nextText().trim().toDoubleOrNull()
                    "time" -> if (inPoint) {
                        time = try {
                            Instant.parse(parser.nextText().trim()).toEpochMilli()
                        } catch (e: DateTimeParseException) {
                            0L
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "trkpt") {
                    val la = lat
                    val lo = lon
                    if (la != null && lo != null) out.add(TimedPoint(la, lo, time, ele))
                    inPoint = false
                }
            }
            event = parser.next()
        }
        return out
    }
}

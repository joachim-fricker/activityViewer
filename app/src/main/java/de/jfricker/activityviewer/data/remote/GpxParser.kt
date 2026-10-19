package de.jfricker.activityviewer.data.remote

import android.util.Xml
import de.jfricker.activityviewer.domain.GeoPoint
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

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
}

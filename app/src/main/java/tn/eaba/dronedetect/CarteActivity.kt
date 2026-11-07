package tn.eaba.dronedetect

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import tn.eaba.dronedetect.databinding.ActivityCarteBinding
import java.io.File

class CarteActivity : AppCompatActivity() {

    private lateinit var b: ActivityCarteBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cache dans l'espace prive de l'application, agent nomme (OSM bloque les anonymes)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tuiles")
        }

        b = ActivityCarteBinding.inflate(layoutInflater)
        setContentView(b.root)

        val lat = intent.getDoubleExtra("lat", Double.NaN)
        val lon = intent.getDoubleExtra("lon", Double.NaN)
        val azimut = intent.getDoubleExtra("azimut", 0.0)
        val distance = intent.getDoubleExtra("distance", 500.0)
        val cap = intent.getDoubleExtra("cap", -1.0)
        val classe = intent.getStringExtra("classe") ?: "cible"
        val numero = intent.getIntExtra("numero", 0)

        val (dlat, dlon) = Geo.destination(lat, lon, azimut, distance)

        b.bandeau.text = ("CR N°%03d   %s\n" +
                "OBS  %.5f %.5f\n" +
                "CIBLE %.5f %.5f   AZ %03.0f°   D %.0f m")
            .format(numero, classe, lat, lon, dlat, dlon, azimut, distance)

        val carte = b.carte
        carte.setTileSource(TileSourceFactory.MAPNIK)
        carte.setMultiTouchControls(true)

        val obs = GeoPoint(lat, lon)
        val cible = GeoPoint(dlat, dlon)

        // Cercles de distance, comme sur un scope
        val pas = pasDistance(distance)
        for (i in 1..3) {
            val rayon = pas * i
            carte.overlays.add(Polygon().apply {
                points = Geo.cercle(lat, lon, rayon).map { GeoPoint(it.first, it.second) }
                fillColor = Color.TRANSPARENT
                outlinePaint.color = Color.argb(90, 120, 200, 140)
                outlinePaint.strokeWidth = 2f
            })
        }

        // Ligne de relevement observateur -> cible
        carte.overlays.add(Polyline().apply {
            setPoints(listOf(obs, cible))
            outlinePaint.color = Color.argb(200, 255, 190, 60)
            outlinePaint.strokeWidth = 5f
        })

        // Vecteur de cap de la cible
        if (cap >= 0.0) {
            val longueur = (distance / 3.0).coerceAtLeast(200.0)
            val (vlat, vlon) = Geo.destination(dlat, dlon, cap, longueur)
            carte.overlays.add(Polyline().apply {
                setPoints(listOf(cible, GeoPoint(vlat, vlon)))
                outlinePaint.color = Color.argb(230, 255, 70, 70)
                outlinePaint.strokeWidth = 8f
            })
        }

        carte.overlays.add(Marker(carte).apply {
            position = obs
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Observateur"
        })
        carte.overlays.add(Marker(carte).apply {
            position = cible
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = classe
            snippet = "AZ %03.0f°  D %.0f m".format(azimut, distance)
        })

        carte.post {
            carte.zoomToBoundingBox(
                BoundingBox.fromGeoPoints(listOf(obs, cible)).increaseByScale(1.6f),
                false, 60
            )
        }
    }

    /** Espacement des cercles, arrondi a une valeur lisible. */
    private fun pasDistance(d: Double): Double = when {
        d <= 300 -> 100.0
        d <= 1000 -> 250.0
        d <= 3000 -> 500.0
        d <= 10000 -> 2000.0
        else -> 5000.0
    }

    override fun onResume() { super.onResume(); b.carte.onResume() }
    override fun onPause() { super.onPause(); b.carte.onPause() }
}
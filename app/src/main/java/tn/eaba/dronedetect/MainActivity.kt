package tn.eaba.dronedetect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import tn.eaba.dronedetect.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var detector: Detector
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var derniereImage: Bitmap? = null
    @Volatile private var dernieresDetections: List<Detection> = emptyList()

    @Volatile private var positionCourante: Location? = null
    private var ecouteur: LocationListener? = null

    /** Cap de la camera arriere, en degres. -1 si la boussole n'est pas disponible. */
    @Volatile private var capCamera = -1f
    private var gestionnaireCapteurs: SensorManager? = null
    private var ecouteurCap: SensorEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            detector = Detector(this)
        } catch (e: Exception) {
            binding.infoLabel.text = "ERREUR : ${e.message}"
            Log.e("MainActivity", "Init detecteur", e)
            return
        }

        binding.btnAlerte.setOnClickListener { declencherAlerte() }
        binding.btnJournal.setOnClickListener {
            startActivity(Intent(this, JournalActivity::class.java))
        }

        demarrerBoussole()

        val manquantes = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (manquantes.isEmpty()) {
            startCamera(); demarrerPositionnement()
        } else {
            ActivityCompat.requestPermissions(this, manquantes.toTypedArray(), 10)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val iCam = permissions.indexOf(Manifest.permission.CAMERA)
        val camOk = iCam < 0 || grantResults.getOrNull(iCam) == PackageManager.PERMISSION_GRANTED
        if (camOk) startCamera() else binding.infoLabel.text = "Permission camera refusee"
        demarrerPositionnement()
    }

    // ---------- boussole ----------

    /** Le cap de la camera arriere est l'azimut de ce que l'operateur vise. */
    private fun demarrerBoussole() {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val capteur = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return

        val l = object : SensorEventListener {
            private val matrice = FloatArray(9)
            private val remap = FloatArray(9)
            private val angles = FloatArray(3)

            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrice, e.values)
                // telephone tenu verticalement, camera vers l'avant
                SensorManager.remapCoordinateSystem(
                    matrice, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap
                )
                SensorManager.getOrientation(remap, angles)
                var az = Math.toDegrees(angles[0].toDouble()).toFloat()
                if (az < 0f) az += 360f
                capCamera = az
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, capteur, SensorManager.SENSOR_DELAY_UI)
        gestionnaireCapteurs = sm
        ecouteurCap = l
    }

    private fun arreterBoussole() {
        ecouteurCap?.let { gestionnaireCapteurs?.unregisterListener(it) }
        ecouteurCap = null
    }

    // ---------- positionnement ----------

    private fun demarrerPositionnement() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        if (ecouteur != null) return

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) { positionCourante = loc }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        ecouteur = l

        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (!lm.isProviderEnabled(p)) continue
                lm.requestLocationUpdates(p, 2000L, 0f, l)
                val dernier = lm.getLastKnownLocation(p)
                if (dernier != null && positionCourante == null) positionCourante = dernier
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Positionnement", e)
        }
    }

    private fun arreterPositionnement() {
        ecouteur?.let {
            try {
                (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it)
            } catch (_: Exception) {}
        }
        ecouteur = null
    }

    private fun positionAlerte(): Location? {
        positionCourante?.let { return it }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        for (f in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            try { lm.getLastKnownLocation(f)?.let { return it } } catch (_: Exception) {}
        }
        return null
    }

    // ---------- camera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(analysisExecutor) { proxy ->
                try {
                    val bmp = rotate(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
                    val t0 = System.currentTimeMillis()
                    val results = detector.detect(bmp)
                    val ms = System.currentTimeMillis() - t0

                    derniereImage = bmp
                    dernieresDetections = results

                    val p = positionCourante
                    val gps = if (p == null) "GPS ---"
                    else "GPS ±%.0fm  %s".format(
                        p.accuracy, AlertStore.positionCompacte(p.latitude, p.longitude))
                    val cap = if (capCamera < 0f) "CAP ---" else "CAP %03d°".format(capCamera.toInt())

                    runOnUiThread {
                        binding.overlay.setResults(results, bmp.width, bmp.height)
                        binding.infoLabel.text = "$ms ms - ${results.size} obj   $cap\n$gps"
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Analyse", e)
                } finally {
                    proxy.close()
                }
            }

            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )

            binding.btn1x.setOnClickListener { camera.cameraControl.setZoomRatio(1f) }
            binding.btn2x.setOnClickListener { camera.cameraControl.setZoomRatio(2f) }
            binding.btn5x.setOnClickListener { camera.cameraControl.setZoomRatio(5f) }

            val pince = ScaleGestureDetector(this,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(d: ScaleGestureDetector): Boolean {
                        val cur = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                        camera.cameraControl.setZoomRatio(cur * d.scaleFactor)
                        return true
                    }
                })
            binding.previewView.setOnTouchListener { v, e ->
                pince.onTouchEvent(e); v.performClick(); true
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- alerte ----------

    private fun declencherAlerte() {
        val bmp = derniereImage
        if (bmp == null) {
            Toast.makeText(this, "Aucune image disponible", Toast.LENGTH_SHORT).show()
            return
        }
        val dets = dernieresDetections
        val meilleure = dets.maxByOrNull { it.score }

        val numero = AlertStore.prochainNumero(this)
        val annotee = AlertStore.annoter(bmp, dets)
        val fichier = AlertStore.enregistrerImage(this, annotee, numero)
        val emp = AlertStore.empreinte(fichier)
        val p = positionAlerte()

        startActivity(Intent(this, AlertActivity::class.java).apply {
            putExtra("numero", numero)
            putExtra("image", fichier.absolutePath)
            putExtra("classe", meilleure?.label ?: "NON CLASSE")
            putExtra("confiance", ((meilleure?.score ?: 0f) * 100).toInt())
            putExtra("dtg", AlertStore.dtg())
            putExtra("empreinte", emp)
            putExtra("position", AlertStore.positionCompacte(p?.latitude, p?.longitude))
            putExtra("precision", p?.accuracy ?: -1f)
            putExtra("lat", p?.latitude ?: Double.NaN)
            putExtra("lon", p?.longitude ?: Double.NaN)
            putExtra("azimut", capCamera)
        })
    }

    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    override fun onResume() {
        super.onResume()
        demarrerPositionnement()
        if (ecouteurCap == null) demarrerBoussole()
    }

    override fun onDestroy() {
        super.onDestroy()
        arreterPositionnement()
        arreterBoussole()
        analysisExecutor.shutdown()
        if (::detector.isInitialized) detector.close()
    }
}
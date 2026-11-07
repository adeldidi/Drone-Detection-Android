package tn.eaba.dronedetect

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import tn.eaba.dronedetect.databinding.ActivityAlertBinding

class AlertActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_ENVOI = "tn.eaba.dronedetect.SMS_ENVOYE"
        private const val DEMANDE_SMS = 20
    }

    private lateinit var b: ActivityAlertBinding
    private var numero = 0
    private var dtg = ""
    private var positionGps = ""
    private var empreinte = ""

    private var recepteur: BroadcastReceiver? = null
    private var attendus = 0
    private var reussis = 0
    private var clos = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(b.root)

        numero = intent.getIntExtra("numero", 0)
        dtg = intent.getStringExtra("dtg") ?: ""
        positionGps = intent.getStringExtra("position") ?: ""
        empreinte = intent.getStringExtra("empreinte") ?: ""
        val classe = intent.getStringExtra("classe") ?: "NON CLASSE"
        val conf = intent.getIntExtra("confiance", 0)
        val prec = intent.getFloatExtra("precision", -1f)
        val chemin = intent.getStringExtra("image")

        if (chemin != null) b.apercu.setImageBitmap(BitmapFactory.decodeFile(chemin))

        val acquise = positionGps.isNotEmpty() && positionGps != "POSITION-INCONNUE"
        b.position.setText(if (acquise) positionGps else "")
        b.entete.text = "CR N°%03d   %s\nEMPREINTE %s\nACQUISITION %s".format(
            numero, dtg, empreinte,
            if (acquise) "GPS ±%.0f m".format(prec) else "AUCUNE - saisir la position"
        )

        b.classe.setText(classe)
        b.confiance.setText(conf.toString())
        b.nombre.setText("1")
        val azCam = intent.getFloatExtra("azimut", -1f)
        if (azCam >= 0f) b.azimut.setText(azCam.toInt().toString())
        val p = getSharedPreferences("alertes", MODE_PRIVATE)
        b.indicatif.setText(p.getString("indicatif", ""))
        b.unite.setText(p.getString("unite", ""))
        b.destinataires.setText(p.getString("destinataires", ""))

        val surveillant = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = majMessage()
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
        }
        listOf(b.position, b.classe, b.confiance, b.nombre, b.azimut, b.distance,
            b.altitude, b.cap, b.indicatif, b.unite)
            .forEach { it.addTextChangedListener(surveillant) }
        majMessage()

        b.btnTransmettre.setOnClickListener { transmettre() }
        b.btnCarte.setOnClickListener {
            val lat = intent.getDoubleExtra("lat", Double.NaN)
            val lon = intent.getDoubleExtra("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) {
                statut("Position de l observateur inconnue")
                return@setOnClickListener
            }
            startActivity(Intent(this, CarteActivity::class.java).apply {
                putExtra("lat", lat)
                putExtra("lon", lon)
                putExtra("azimut", v(b.azimut, "0").toDoubleOrNull() ?: 0.0)
                putExtra("distance", v(b.distance, "500").toDoubleOrNull() ?: 500.0)
                putExtra("cap", v(b.cap, "-1").toDoubleOrNull() ?: -1.0)
                putExtra("classe", v(b.classe))
                putExtra("numero", numero)
            })
        }
        b.btnEnregistrer.setOnClickListener { cloturer("NON TRANSMIS (local)") }
    }

    // ---------- message ----------

    private fun v(e: EditText, defaut: String = "-"): String =
        e.text.toString().trim().ifEmpty { defaut }

    /** GPS si la position vient du recepteur, MAN si l'operateur l'a saisie. */
    private fun source(): String {
        val saisie = b.position.text.toString().trim()
        val acquise = positionGps.isNotEmpty() && positionGps != "POSITION-INCONNUE"
        return if (acquise && saisie == positionGps) "GPS" else "MAN"
    }

    private fun message(): String = listOf(
        "DRN%03d".format(numero),
        dtg,
        v(b.position, "POS-NC"),
        source(),
        AlertStore.codeClasse(v(b.classe)),
        v(b.confiance, "0"),
        "N" + v(b.nombre, "1"),
        "AZ" + v(b.azimut),
        "D" + v(b.distance),
        "A" + v(b.altitude),
        "C" + v(b.cap),
        empreinte,
        (v(b.indicatif, "OP") + "-" + v(b.unite, "NA")).uppercase()
    ).joinToString("/")

    private fun majMessage() {
        val m = message()
        b.apercuMessage.text = "$m\n\n${m.length} caracteres"
    }

    private fun statut(t: String) { b.statut.text = t }

    // ---------- transmission ----------

    private fun destinataires(): List<String> =
        b.destinataires.text.toString()
            .split(",", ";", " ", "\n")
            .map { it.trim() }
            .filter { it.length >= 6 }

    private fun transmettre() {
        val nums = destinataires()
        if (nums.isEmpty()) {
            statut("Aucun destinataire valide")
            return
        }
        memoriser()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.SEND_SMS), DEMANDE_SMS
            )
            return
        }
        envoyerSms(nums)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != DEMANDE_SMS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            envoyerSms(destinataires())
        } else {
            remettreAAppSms(destinataires())
        }
    }

    private fun gestionnaireSms(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            getSystemService(SmsManager::class.java)
        else
            @Suppress("DEPRECATION") SmsManager.getDefault()

    private fun envoyerSms(nums: List<String>) {
        if (nums.isEmpty()) return
        val m = message()
        attendus = nums.size
        reussis = 0
        b.btnTransmettre.isEnabled = false
        statut("Transmission en cours vers ${nums.size} destinataire(s)...")

        recepteur = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (resultCode == RESULT_OK) reussis++
                attendus--
                if (attendus <= 0) finaliser()
            }
        }
        ContextCompat.registerReceiver(
            this, recepteur, IntentFilter(ACTION_ENVOI),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val sm = gestionnaireSms()
        for ((i, n) in nums.withIndex()) {
            val pi = PendingIntent.getBroadcast(
                this, i, Intent(ACTION_ENVOI),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                sm.sendTextMessage(n, null, m, pi, null)
            } catch (e: Exception) {
                attendus--
            }
        }
        if (attendus <= 0) finaliser()
    }

    private fun finaliser() {
        recepteur?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        recepteur = null
        val ok = reussis > 0
        statut(if (ok) "Compte rendu transmis" else "Echec - enregistre localement")
        cloturer(
            if (ok) "TRANSMIS ${AlertStore.dtg()} - $reussis destinataire(s)"
            else "ECHEC TRANSMISSION"
        )
    }

    private fun remettreAAppSms(nums: List<String>) {
        if (nums.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:" + nums.joinToString(";"))
                putExtra("sms_body", message())
            })
            cloturer("REMIS A L APPLICATION SMS")
        } catch (e: Exception) {
            statut("Aucune application SMS disponible")
            cloturer("NON TRANSMIS (local)")
        }
    }

    // ---------- journal ----------

    private fun memoriser() {
        getSharedPreferences("alertes", MODE_PRIVATE).edit()
            .putString("indicatif", v(b.indicatif, ""))
            .putString("unite", v(b.unite, ""))
            .putString("destinataires", b.destinataires.text.toString().trim())
            .apply()
    }

    private fun cloturer(statutEnvoi: String) {
        if (clos) return
        clos = true
        memoriser()

        AlertStore.journaliser(this, buildString {
            appendLine("=== COMPTE RENDU N°%03d ===".format(numero))
            appendLine("DTG         : $dtg")
            appendLine("POSITION    : ${v(b.position, "non renseignee")} (${source()})")
            appendLine("CLASSE      : ${v(b.classe)} (${v(b.confiance, "0")} %)")
            appendLine("NOMBRE      : ${v(b.nombre, "1")}")
            appendLine("AZIMUT      : ${v(b.azimut)}")
            appendLine("DISTANCE    : ${v(b.distance)} m")
            appendLine("ALTITUDE    : ${v(b.altitude)} m")
            appendLine("CAP         : ${v(b.cap)}")
            appendLine("OBSERVATIONS: ${v(b.observations, "neant")}")
            appendLine("OPERATEUR   : ${v(b.indicatif)} / ${v(b.unite)}")
            appendLine("DESTINATAIR.: ${destinataires().joinToString(", ").ifEmpty { "-" }}")
            appendLine("EMPREINTE   : $empreinte")
            appendLine("STATUT      : $statutEnvoi")
            appendLine("MESSAGE     : ${message()}")
            appendLine()
        })

        AlertStore.ajouterCsv(this, listOf(
            "%03d".format(numero), dtg, v(b.position, ""), source(),
            v(b.classe), v(b.confiance, "0"), v(b.nombre, "1"),
            v(b.azimut, ""), v(b.distance, ""), v(b.altitude, ""), v(b.cap, ""),
            v(b.observations, ""), v(b.indicatif, ""), v(b.unite, ""),
            destinataires().joinToString(" "), empreinte, statutEnvoi, message()
        ))

        Toast.makeText(this, "CR n°%03d : %s".format(numero, statutEnvoi),
            Toast.LENGTH_LONG).show()
        b.root.postDelayed({ finish() }, 1200)
    }

    override fun onDestroy() {
        super.onDestroy()
        recepteur?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
    }
}
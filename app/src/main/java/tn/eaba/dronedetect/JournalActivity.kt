package tn.eaba.dronedetect

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import tn.eaba.dronedetect.databinding.ActivityJournalBinding

class JournalActivity : AppCompatActivity() {

    private lateinit var b: ActivityJournalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityJournalBinding.inflate(layoutInflater)
        setContentView(b.root)

        val texte = AlertStore.lire(this, "journal.txt")
        b.contenu.text = if (texte.isBlank()) "Aucun compte rendu enregistre." else texte

        val images = AlertStore.fichiers(this).count { it.name.endsWith(".jpg") }
        b.resume.text = "$images image(s) scellee(s) en memoire"

        b.btnTexte.setOnClickListener { partagerTexte(texte, "Journal des alertes") }
        b.btnCsv.setOnClickListener {
            partagerTexte(AlertStore.lire(this, "journal.csv"), "Alertes drone (CSV)")
        }
        b.btnTout.setOnClickListener { exporterTout() }
    }

    private fun partagerTexte(t: String, sujet: String) {
        if (t.isBlank()) {
            Toast.makeText(this, "Rien a exporter", Toast.LENGTH_SHORT).show(); return
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, sujet)
            putExtra(Intent.EXTRA_TEXT, t)
        }, sujet))
    }

    private fun exporterTout() {
        val uris = ArrayList<Uri>()
        for (f in AlertStore.fichiers(this)) {
            try {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fichiers", f))
            } catch (_: Exception) {}
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Rien a exporter", Toast.LENGTH_SHORT).show(); return
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Exporter les comptes rendus"))
    }
}
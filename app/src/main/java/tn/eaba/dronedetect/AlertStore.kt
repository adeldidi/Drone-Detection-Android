package tn.eaba.dronedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Stockage local des comptes rendus : image scellee, empreinte, journal. */
object AlertStore {

    private const val PREFS = "alertes"
    private const val KEY_SEQ = "sequence"

     fun dossier(context: Context): File {
        val d = File(context.filesDir, "alertes")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** Numero de compte rendu, incremente a chaque alerte. */
    fun prochainNumero(context: Context): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = p.getInt(KEY_SEQ, 0) + 1
        p.edit().putInt(KEY_SEQ, n).apply()
        return n
    }

    /** Recopie l'image en y dessinant les boites : c'est elle qui fait foi. */
    fun annoter(src: Bitmap, dets: List<Detection>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val trait = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(255, 70, 70)
        }
        val texte = Paint().apply { color = Color.WHITE; textSize = 28f; isAntiAlias = true }
        val fond = Paint().apply { color = Color.argb(200, 0, 0, 0) }
        for (d in dets) {
            c.drawRect(d.box, trait)
            val s = "${d.label} ${(d.score * 100).toInt()}%"
            val w = texte.measureText(s)
            c.drawRect(d.box.left, d.box.top - 34f, d.box.left + w + 12f, d.box.top, fond)
            c.drawText(s, d.box.left + 6f, d.box.top - 10f, texte)
        }
        return out
    }

    fun enregistrerImage(context: Context, bmp: Bitmap, numero: Int): File {
        val f = File(dossier(context), "CR%03d.jpg".format(numero))
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f
    }

    /** Empreinte SHA-256 du fichier, tronquee a 6 caracteres. */
    fun empreinte(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { entree ->
            val buf = ByteArray(8192)
            while (true) {
                val n = entree.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.substring(0, 6)
    }

    /** Groupe date-heure Zulu : JJHHMMZ */
    fun dtg(d: Date = Date()): String {
        val f = SimpleDateFormat("ddHHmm", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(d) + "Z"
    }

    /** Position compacte : 3680650N01018150E */
    fun positionCompacte(lat: Double?, lon: Double?): String {
        if (lat == null || lon == null) return "POSITION-INCONNUE"
        val la = Math.abs(lat * 100000).toInt()
        val lo = Math.abs(lon * 100000).toInt()
        return "%07d%s%08d%s".format(la, if (lat >= 0) "N" else "S",
            lo, if (lon >= 0) "E" else "W")
    }

    /** Code court derive du libelle du modele, jamais d'un indice. */
    fun codeClasse(label: String): String {
        val l = label.lowercase()
        return when {
            l.contains("grenade") -> "GREN"
            l.contains("heavy") || l.contains("payload") ||
                    l.contains("suspect") || l.contains("lourd") -> "LRD"
            l.contains("unarmed") || l.contains("non arm") -> "NARM"
            else -> l.replace(" ", "").take(4).uppercase().ifEmpty { "INCO" }
        }
    }

    fun journaliser(context: Context, texte: String) {
        File(dossier(context), "journal.txt").appendText(texte)
    }
    private const val ENTETE_CSV =
        "numero;dtg;position;source;classe;confiance;nombre;azimut;distance;" +
                "altitude;cap;observations;operateur;unite;destinataires;empreinte;statut;message"

    /** Une ligne par compte rendu, exploitable dans un tableur. */
    fun ajouterCsv(context: Context, champs: List<String>) {
        val f = File(dossier(context), "journal.csv")
        if (!f.exists()) f.appendText(ENTETE_CSV + "\n")
        f.appendText(champs.joinToString(";") { "\"" + it.replace("\"", "'") + "\"" } + "\n")
    }

    fun lire(context: Context, nom: String): String {
        val f = File(dossier(context), nom)
        return if (f.exists()) f.readText() else ""
    }

    fun fichiers(context: Context): List<File> =
        dossier(context).listFiles()?.sortedBy { it.name } ?: emptyList()
}
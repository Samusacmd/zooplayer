package it.sam.zooplayer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Il link non è un elenco JSON ma un flusso audio/video (es. una radio come R101). */
class NonJsonException(val contentType: String?) : IOException("Non è un elenco JSON ($contentType)")

object Rete {
    const val UA = "Mozilla/5.0 (Linux; Android) ZooPlayer"
    private const val MAX_BYTE = 6 * 1024 * 1024
    private val memoria = ConcurrentHashMap<String, String>()
    private lateinit var dirCache: File

    fun init(ctx: Context) {
        dirCache = File(ctx.cacheDir, "elenchi").apply { mkdirs() }
    }

    private fun fileCache(url: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dirCache, "$h.txt")
    }

    fun daCache(url: String): String? {
        memoria[url]?.let { return it }
        val f = fileCache(url)
        if (!f.exists()) return null
        return try {
            f.readText().also { memoria[url] = it }
        } catch (_: Exception) {
            null
        }
    }

    fun inCache(url: String, testo: String) {
        memoria[url] = testo
        try {
            fileCache(url).writeText(testo)
        } catch (e: Exception) {
            ZLog.w("Cache non scritta per $url: ${e.message}")
        }
    }

    suspend fun scarica(ctx: Context, url: String): String = withContext(Dispatchers.IO) {
        if (url.startsWith("content://") || url.startsWith("file://")) {
            val ins = ctx.contentResolver.openInputStream(Uri.parse(url))
                ?: throw IOException("File locale non leggibile")
            ins.use { leggi(it) ?: throw IOException("File troppo grande") }
        } else {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", UA)
                val codice = c.responseCode
                if (codice >= 400) throw IOException("HTTP $codice")
                val ct = c.contentType?.lowercase()
                if (ct != null && (ct.startsWith("audio/") || ct.startsWith("video/") || ct.contains("mpegurl"))) {
                    throw NonJsonException(ct)
                }
                c.inputStream.use { leggi(it) ?: throw NonJsonException(ct) }
            } finally {
                c.disconnect()
            }
        }
    }

    /** Legge al massimo MAX_BYTE: un flusso radio infinito ritorna null invece di bloccarsi. */
    private fun leggi(ins: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var tot = 0
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            tot += n
            if (tot > MAX_BYTE) return null
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}

object Repo {
    /** Scarica (o prende dalla cache) e interpreta un elenco. Mette in cache solo ciò che si interpreta. */
    suspend fun caricaVoci(ctx: Context, url: String, coverPadre: String?, forza: Boolean): List<Voce> {
        if (!forza) {
            Rete.daCache(url)?.let { t ->
                val arr = withContext(Dispatchers.Default) { Parser.estraiArray(t) }
                if (arr != null) return Parser.normalizza(arr, url, coverPadre)
            }
        }
        val testo = Rete.scarica(ctx, url)
        val arr = withContext(Dispatchers.Default) { Parser.estraiArray(testo) }
            ?: throw IOException("Nessun elenco JSON trovato")
        Rete.inCache(url, testo)
        return Parser.normalizza(arr, url, coverPadre)
    }
}

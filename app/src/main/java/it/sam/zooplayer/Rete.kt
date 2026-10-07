package it.sam.zooplayer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Il server ha risposto "troppe richieste" (HTTP 429) anche dopo i tentativi. */
class TroppeRichiesteException(host: String) : IOException("$host ha limitato le richieste (HTTP 429): riprova tra qualche minuto")

/**
 * Freno per gli host che limitano le richieste (pastebin: HTTP 429 se si va troppo veloci).
 * Le richieste verso lo stesso host partono distanziate; dopo un 429 tutte quelle verso
 * quell'host si fermano per il tempo indicato dal server.
 */
private object Freno {
    private class Stato {
        val lock = Mutex()
        var ultima = 0L
        var pausaFino = 0L
    }

    private val stati = ConcurrentHashMap<String, Stato>()

    /** Distanza minima tra due richieste allo stesso host. */
    fun intervallo(host: String): Long = when {
        host.endsWith("pastebin.com") -> 1_200L
        else -> 0L
    }

    suspend fun attendiTurno(host: String) {
        val minimo = intervallo(host)
        val st = stati.getOrPut(host) { Stato() }
        if (minimo == 0L && st.pausaFino == 0L) return
        st.lock.withLock {
            val ora = System.currentTimeMillis()
            val attesa = maxOf(st.ultima + minimo - ora, st.pausaFino - ora)
            if (attesa > 0) delay(attesa)
            st.ultima = System.currentTimeMillis()
        }
    }

    fun pausa(host: String, ms: Long) {
        val st = stati.getOrPut(host) { Stato() }
        st.pausaFino = maxOf(st.pausaFino, System.currentTimeMillis() + ms)
    }
}

/** Il link non è un elenco JSON ma un flusso audio/video (es. una radio come R101). */
class NonJsonException(val contentType: String?) : IOException("Non è un elenco JSON ($contentType)")

object Rete {
    const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private const val MAX_BYTE = 6 * 1024 * 1024

    /** Attese dopo un HTTP 429 (ms), una per tentativo. */
    private val ATTESE_429 = longArrayOf(5_000, 15_000, 30_000, 60_000)
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

    private const val CONSENTITI = "-._~:/?#@!$&'()*+,;="

    /**
     * Rende sicuro un link per la rete: spazi, lettere accentate, parentesi quadre ecc.
     * vengono codificati (%20...), le sequenze %XX già presenti restano intatte.
     * I link di filedn con nomi di cartelle come "Zoo di 105" falliscono senza questo passaggio.
     */
    fun codifica(url: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < url.length) {
            val cp = url.codePointAt(i)
            val n = Character.charCount(cp)
            val c = url[i]
            when {
                c == '%' && i + 2 < url.length &&
                    url[i + 1].isHexDigitAscii() && url[i + 2].isHexDigitAscii() -> sb.append(c)
                cp < 128 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in CONSENTITI) -> sb.append(c)
                else -> url.substring(i, i + n).toByteArray(Charsets.UTF_8)
                    .forEach { b -> sb.append('%').append("%02X".format(b.toInt() and 0xFF)) }
            }
            i += n
        }
        return sb.toString()
    }

    private fun Char.isHexDigitAscii() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    suspend fun scarica(ctx: Context, url: String): String = withContext(Dispatchers.IO) {
        if (url.startsWith("content://") || url.startsWith("file://")) {
            val ins = ctx.contentResolver.openInputStream(Uri.parse(url))
                ?: throw IOException("File locale non leggibile")
            return@withContext ins.use { leggi(it) ?: throw IOException("File troppo grande") }
        }
        var indirizzo = codifica(url)
        var salti = 0
        var tentativi429 = 0
        while (true) {
            val host = URL(indirizzo).host.lowercase()
            Freno.attendiTurno(host)
            val c = URL(indirizzo).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                // i redirect li seguo a mano: HttpURLConnection non passa da http a https da solo
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept", "application/json,text/plain,text/html;q=0.9,*/*;q=0.8")
                val codice = c.responseCode
                if (codice in 300..399) {
                    val dove = c.getHeaderField("Location")
                    if (dove == null || ++salti > 6) throw IOException("Redirect non valido (HTTP $codice)")
                    val prossimo = codifica(URL(URL(indirizzo), dove).toString())
                    ZLog.i("Redirect $codice: $indirizzo -> $prossimo")
                    indirizzo = prossimo
                    continue
                }
                if (codice == 429 || (codice == 503 && host.endsWith("pastebin.com"))) {
                    if (++tentativi429 > ATTESE_429.size) {
                        ZLog.w("HTTP $codice da $indirizzo: rinuncio dopo ${ATTESE_429.size} attese")
                        throw TroppeRichiesteException(host)
                    }
                    // Retry-After in secondi se il server lo indica, altrimenti attese crescenti
                    val suggerito = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                    val attesa = (suggerito ?: ATTESE_429[tentativi429 - 1]).coerceIn(2_000, 120_000)
                    ZLog.w("HTTP $codice da $host: pausa di ${attesa / 1000} s e riprovo (tentativo $tentativi429)")
                    Freno.pausa(host, attesa)
                    continue
                }
                if (codice >= 400) {
                    ZLog.w("HTTP $codice da $indirizzo")
                    throw IOException("Il server ha risposto HTTP $codice")
                }
                val ct = c.contentType?.lowercase()
                if (ct != null && (ct.startsWith("audio/") || ct.startsWith("video/") || ct.contains("mpegurl"))) {
                    throw NonJsonException(ct)
                }
                return@withContext c.inputStream.use { leggi(it) ?: throw NonJsonException(ct) }
            } finally {
                c.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("irraggiungibile")
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
        val testo = try {
            Rete.scarica(ctx, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NonJsonException) {
            throw e
        } catch (e: Exception) {
            // aggiornamento fallito (es. 429): meglio la copia salvata che un elenco mancante
            val vecchio = if (forza) Rete.daCache(url) else null
            val arrVecchio = vecchio?.let { withContext(Dispatchers.Default) { Parser.estraiArray(it) } }
            if (arrVecchio != null) {
                ZLog.w("Aggiornamento di $url fallito (${e.message}): uso la copia salvata")
                return Parser.normalizza(arrVecchio, url, coverPadre)
            }
            throw e
        }
        val arr = withContext(Dispatchers.Default) { Parser.estraiArray(testo) }
        if (arr == null) {
            ZLog.w("Nessun JSON in $url - inizio risposta: ${testo.take(300).replace(Regex("\\s+"), " ")}")
            throw IOException("Il link non contiene un elenco JSON (dettagli nel log)")
        }
        Rete.inCache(url, testo)
        return Parser.normalizza(arr, url, coverPadre)
    }
}

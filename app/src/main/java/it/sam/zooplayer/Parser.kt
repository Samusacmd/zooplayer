package it.sam.zooplayer

import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URL

/** Porting del parser di ZooPlayer 2.8.0: tollera HTML intorno, entità HTML e caratteri di controllo. */
object Parser {
    private val PASTEBIN = Regex("""^(https?://)?(www\.)?pastebin\.com/(?!raw/)([A-Za-z0-9]+)/?$""", RegexOption.IGNORE_CASE)
    private val PRE = Regex("""(?is)<pre[^>]*>(.*?)</pre>""")
    private val SPAZI = Regex("""\s+""")

    fun normalizzaUrlJson(u: String): String {
        var s = u.trim()
        // "pastebin.com/raw/abc" scritto senza https:// non è un indirizzo valido
        if (s.isNotEmpty() && !s.contains("://")) s = "https://$s"
        val m = PASTEBIN.find(s) ?: return s
        return "https://pastebin.com/raw/" + m.groupValues[3]
    }

    private fun htmlUnescape(s: String) = s
        .replace("&quot;", "\"").replace("&#34;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&amp;", "&")

    /** Trova il primo array JSON valido nel testo. Ritorna null se non c'è. */
    fun estraiArray(testo: String): JSONArray? {
        var t = testo
        val pre = PRE.find(t)
        if (pre != null) t = htmlUnescape(pre.groupValues[1])
        else if (t.contains("&quot;")) t = htmlUnescape(t)

        val inizio = t.trimStart()
        if (inizio.startsWith("{")) {
            bilancia(t, t.indexOf('{'))?.let { obj ->
                try {
                    val o = JSONObject(obj)
                    val k = o.keys()
                    while (k.hasNext()) o.optJSONArray(k.next())?.let { return it }
                } catch (_: JSONException) {
                }
            }
        }
        var da = 0
        var tentativi = 0
        while (tentativi < 25) {
            val start = t.indexOf('[', da)
            if (start < 0) return null
            val pezzo = bilancia(t, start)
            if (pezzo != null) {
                try {
                    return JSONArray(pezzo)
                } catch (_: JSONException) {
                }
            }
            da = start + 1
            tentativi++
        }
        return null
    }

    /** Sottostringa bilanciata a partire da start, con i caratteri di controllo dentro le stringhe sostituiti da spazi. */
    private fun bilancia(t: String, start: Int): String? {
        if (start < 0) return null
        val sb = StringBuilder()
        var prof = 0
        var inStringa = false
        var escape = false
        for (k in start until t.length) {
            val c = t[k]
            if (inStringa) {
                when {
                    escape -> { escape = false; sb.append(if (c < ' ') ' ' else c) }
                    c == '\\' -> { escape = true; sb.append(c) }
                    c == '"' -> { inStringa = false; sb.append(c) }
                    c < ' ' -> sb.append(' ')
                    else -> sb.append(c)
                }
            } else {
                when (c) {
                    '"' -> inStringa = true
                    '[', '{' -> prof++
                    ']', '}' -> prof--
                }
                sb.append(c)
                if (prof == 0) return sb.toString()
            }
        }
        return null
    }

    private fun campo(o: JSONObject, vararg chiavi: String): String {
        for (k in chiavi) {
            val v = o.optString(k, "").trim()
            if (v.isNotEmpty() && v != "null") return v
        }
        return ""
    }

    fun normalizza(arr: JSONArray, base: String?, coverPadre: String?): List<Voce> {
        val out = ArrayList<Voce>()
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val nome = campo(o, "name", "nome", "title").replace(SPAZI, " ").trim()
            val img = campo(o, "imageUrl", "image", "cover")
                .takeIf { it.isNotEmpty() && !it.equals("PLACEHOLDER_IMMAGINE", ignoreCase = true) }
            val cover = img?.let { risolvi(base, it) } ?: coverPadre
            val urlList = campo(o, "urlList")
            val url = campo(o, "url")
            when {
                urlList.isNotEmpty() -> {
                    val u = normalizzaUrlJson(risolvi(base, urlList))
                    out += Voce(nome.ifBlank { nomeDaUrl(u) }, u, true, cover)
                }
                url.isNotEmpty() -> {
                    val u = risolvi(base, url)
                    val elenco = TipiMedia.sembraElenco(u)
                    out += Voce(nome.ifBlank { nomeDaUrl(u) }, if (elenco) normalizzaUrlJson(u) else u, elenco, cover)
                }
                else -> ZLog.w("Voce senza url/urlList ignorata: '$nome'")
            }
        }
        return out
    }

    fun risolvi(base: String?, link: String): String {
        if (link.startsWith("http://", true) || link.startsWith("https://", true)) return link
        if (base == null || !base.startsWith("http", true)) {
            if (base != null) ZLog.w("Link relativo '$link' in un JSON locale: non risolvibile su Android")
            return link
        }
        return try {
            URL(URL(base), link).toString()
        } catch (_: Exception) {
            link
        }
    }

    fun nomeDaUrl(u: String): String =
        Uri.decode(u.substringBefore('?').trimEnd('/').substringAfterLast('/'))
}

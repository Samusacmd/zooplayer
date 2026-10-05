package it.sam.zooplayer

import android.net.Uri

/** Una voce di un elenco JSON: un sotto-elenco (urlList) oppure un file/stream (url). */
data class Voce(
    val nome: String,
    val url: String,
    val isElenco: Boolean,
    val cover: String?,
)

/** Una riga dell'indice di ricerca: un file media con il percorso degli elenchi che lo contengono. */
data class RigaIndice(
    val nome: String,
    val url: String,
    val percorso: String,
    val cover: String?,
) {
    fun comeVoce() = Voce(nome, url, false, cover)
}

enum class TipoMedia { VIDEO, AUDIO, STREAM }

object TipiMedia {
    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "mpg", "mpeg", "3gp", "vob")
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "mka", "ac3")
    private val ELENCHI = setOf("json", "txt")
    private val PASTEBIN = Regex("""pastebin\.com/(raw/)?[A-Za-z0-9]+/?$""")

    fun estensione(url: String): String {
        val path = try {
            Uri.parse(url).path
        } catch (_: Exception) {
            null
        } ?: url.substringBefore('?')
        val nome = path.substringAfterLast('/')
        return if ('.' in nome) nome.substringAfterLast('.').lowercase() else ""
    }

    fun tipo(url: String): TipoMedia = when (estensione(url)) {
        in VIDEO -> TipoMedia.VIDEO
        in AUDIO -> TipoMedia.AUDIO
        else -> TipoMedia.STREAM
    }

    /** Dirette in streaming adattivo (DASH .mpd, HLS .m3u8): si riproducono, non si scaricano. */
    fun isDiretta(url: String): Boolean = estensione(url) in setOf("mpd", "m3u8")

    /** Un campo "url" che in realtà punta a un altro elenco JSON. */
    fun sembraElenco(url: String): Boolean =
        estensione(url) in ELENCHI || PASTEBIN.containsMatchIn(url) || url.contains("/raw?id=")
}

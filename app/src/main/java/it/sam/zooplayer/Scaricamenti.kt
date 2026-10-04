package it.sam.zooplayer

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/** Download tramite il DownloadManager di sistema, in Download/ZooPlayer/<percorso degli elenchi>/ */
object Scaricamenti {
    private val VIETATI = Regex("""[\\/:*?"<>|\x00-\x1F]""")

    private fun pulisci(s: String) = s.replace(VIETATI, "_").trim().trim('.').ifBlank { "_" }.take(80)

    fun accoda(ctx: Context, nome: String, url: String, cartella: String) {
        val nomeFile = pulisci(Parser.nomeDaUrl(url).ifBlank { nome })
        val sotto = cartella.split(" / ").filter { it.isNotBlank() }.joinToString("/") { pulisci(it) }
        val rel = if (sotto.isEmpty()) "ZooPlayer/$nomeFile" else "ZooPlayer/$sotto/$nomeFile"
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(nome)
                .setDescription("ZooPlayer")
                .addRequestHeader("User-Agent", Rete.UA)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, rel)
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            ZLog.i("Download accodato: $rel <- $url")
        } catch (e: Exception) {
            ZLog.e("Download non accodato: $url", e)
            throw e
        }
    }
}

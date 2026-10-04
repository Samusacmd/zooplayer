package it.sam.zooplayer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

data class StatoPlayer(
    val coda: List<Voce> = emptyList(),
    val indice: Int = -1,
    val inRiproduzione: Boolean = false,
    val buffering: Boolean = false,
    val finito: Boolean = false,
    val posizione: Long = 0,
    val durata: Long = 0,
    val video: Boolean = false,
    val coverIncorporata: Bitmap? = null,
    val errore: String? = null,
) {
    val corrente: Voce? get() = coda.getOrNull(indice)
}

/** Unico player dell'app (libVLC). Vive nel processo; PlayerService lo tiene attivo in background. */
object Riproduttore {
    private lateinit var app: Context
    private var libVlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var jobCover: Job? = null
    val stato = MutableStateFlow(StatoPlayer())

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    private fun player(): MediaPlayer {
        mp?.let { return it }
        val lib = LibVLC(app, arrayListOf("--network-caching=3000", "--http-reconnect", "--no-stats"))
        val p = MediaPlayer(lib)
        p.setEventListener { ev -> evento(ev) }
        libVlc = lib
        mp = p
        ZLog.i("libVLC inizializzato")
        return p
    }

    private fun evento(ev: MediaPlayer.Event) {
        when (ev.type) {
            MediaPlayer.Event.Playing -> {
                stato.update { it.copy(inRiproduzione = true, buffering = false, finito = false, errore = null) }
                stato.value.corrente?.let { Db.segnaAscoltato(it.url) }
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped ->
                stato.update { it.copy(inRiproduzione = false) }
            MediaPlayer.Event.Buffering ->
                stato.update { it.copy(buffering = ev.buffering < 100f) }
            MediaPlayer.Event.TimeChanged ->
                stato.update { it.copy(posizione = ev.timeChanged) }
            MediaPlayer.Event.LengthChanged ->
                stato.update { it.copy(durata = ev.lengthChanged) }
            MediaPlayer.Event.Vout -> {
                val c = stato.value.corrente
                if (c != null && TipiMedia.tipo(c.url) == TipoMedia.STREAM) {
                    stato.update { it.copy(video = ev.voutCount > 0) }
                }
            }
            MediaPlayer.Event.EndReached -> scope.launch {
                delay(300)
                successivo(automatico = true)
            }
            MediaPlayer.Event.EncounteredError -> {
                ZLog.e("Errore di riproduzione: ${stato.value.corrente?.url}")
                stato.update { it.copy(inRiproduzione = false, buffering = false, errore = "Impossibile riprodurre questo file") }
            }
        }
    }

    fun riproduci(coda: List<Voce>, indice: Int) {
        if (indice !in coda.indices) return
        stato.value = StatoPlayer(coda = coda, indice = indice)
        avvia()
        try {
            ContextCompat.startForegroundService(app, Intent(app, PlayerService::class.java))
        } catch (e: Exception) {
            ZLog.e("Servizio di riproduzione non avviato", e)
        }
    }

    private fun avvia() {
        val v = stato.value.corrente ?: return
        val p = player()
        ZLog.i("Riproduco: ${v.nome} -> ${v.url}")
        val m = Media(libVlc!!, Uri.parse(v.url))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":network-caching=3000")
        p.setMedia(m)
        m.release()
        p.play()
        stato.update {
            it.copy(
                posizione = 0, durata = 0, coverIncorporata = null, buffering = true,
                finito = false, errore = null, video = TipiMedia.tipo(v.url) == TipoMedia.VIDEO,
            )
        }
        caricaCover(v)
    }

    /** Cover incorporata nel file audio (tag ID3/MP4/FLAC); se manca resta quella dell'elenco. */
    private fun caricaCover(v: Voce) {
        jobCover?.cancel()
        if (TipiMedia.tipo(v.url) == TipoMedia.VIDEO) return
        jobCover = scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(v.url, HashMap<String, String>())
                    r.embeddedPicture?.let { decodifica(it) }
                } catch (e: Exception) {
                    ZLog.w("Cover incorporata non letta (${v.nome}): ${e.message}")
                    null
                } finally {
                    try {
                        r.release()
                    } catch (_: Exception) {
                    }
                }
            }
            if (bmp != null && stato.value.corrente?.url == v.url) {
                stato.update { it.copy(coverIncorporata = bmp) }
            }
        }
    }

    private fun decodifica(b: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var campione = 1
        while (o.outWidth / campione > 1000 || o.outHeight / campione > 1000) campione *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = campione })
    }

    fun playPausa() {
        val p = mp ?: return
        when {
            stato.value.finito || stato.value.errore != null -> avvia()
            p.isPlaying -> p.pause()
            else -> p.play()
        }
    }

    fun salta(ms: Long) {
        val p = mp ?: return
        var t = (p.time + ms).coerceAtLeast(0)
        if (p.length > 0) t = t.coerceAtMost(p.length - 1000)
        p.setTime(t)
        stato.update { it.copy(posizione = t) }
    }

    fun vaiA(ms: Long) {
        mp?.setTime(ms)
        stato.update { it.copy(posizione = ms) }
    }

    fun successivo(automatico: Boolean = false) {
        val s = stato.value
        if (s.indice + 1 < s.coda.size) {
            stato.update { it.copy(indice = s.indice + 1) }
            avvia()
        } else if (automatico) {
            stato.update { it.copy(inRiproduzione = false, finito = true) }
        }
    }

    fun precedente() {
        val s = stato.value
        if (s.posizione > 5000 || s.indice == 0) {
            vaiA(0)
        } else {
            stato.update { it.copy(indice = s.indice - 1) }
            avvia()
        }
    }

    fun ferma() {
        jobCover?.cancel()
        try {
            mp?.stop()
        } catch (_: Exception) {
        }
        stato.value = StatoPlayer()
        app.stopService(Intent(app, PlayerService::class.java))
    }

    fun agganciaVideo(layout: VLCVideoLayout) {
        val p = player()
        if (p.getVLCVout().areViewsAttached()) p.detachViews()
        p.attachViews(layout, null, false, false)
    }

    fun sganciaVideo() {
        val p = mp ?: return
        if (p.getVLCVout().areViewsAttached()) p.detachViews()
    }
}

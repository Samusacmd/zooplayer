package it.sam.zooplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import org.videolan.libvlc.interfaces.IVLCVout
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

/** Tipo di traccia "video" negli eventi ESAdded di libVLC (0 = audio, 1 = video, 2 = sottotitoli). */
private const val TRACCIA_VIDEO = 1

/** Unico player dell'app (libVLC). Vive nel processo; PlayerService lo tiene attivo in background. */
object Riproduttore {
    private lateinit var app: Context
    private var libVlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var jobCover: Job? = null
    val stato = MutableStateFlow(StatoPlayer())

    /** Sessione multimediale: tasti Bluetooth/cuffie, schermata di blocco, auto, smartwatch. */
    lateinit var sessione: MediaSessionCompat
        private set

    /** Copertina mostrata su notifica, schermata di blocco e display dell'auto. */
    var copertinaSessione: Bitmap? = null
        private set

    private lateinit var audio: AudioManager
    private var richiestaFocus: AudioFocusRequestCompat? = null
    private var hoFocus = false
    private var riprendiDopoInterruzione = false
    private var abbassato = false
    private var jobArt: Job? = null
    private var urlArt: String? = null

    /** Uscite video attive secondo VLC: 0 con una traccia video = schermo nero. */
    private var voutAttive = 0
    private var jobVideo: Job? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        sessione = MediaSessionCompat(app, "ZooPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = riprendi()
                override fun onPause() = pausa()
                override fun onStop() = ferma()
                override fun onSkipToNext() = successivo()
                override fun onSkipToPrevious() = precedente()
                override fun onFastForward() = salta(10_000)
                override fun onRewind() = salta(-10_000)
                override fun onSeekTo(pos: Long) = vaiA(pos)
            })
        }

        // cuffie scollegate (filo o Bluetooth): pausa, invece di passare all'altoparlante
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (mp?.isPlaying == true) {
                        ZLog.i("Cuffie scollegate: pausa")
                        pausa()
                    }
                }
            },
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_EXPORTED,
        )

        // la sessione segue lo stato del player (non a ogni secondo: la posizione la calcola il sistema)
        scope.launch {
            stato.map { listOf(it.corrente?.url, it.inRiproduzione, it.buffering, it.durata, it.errore, it.coverIncorporata, it.finito) }
                .distinctUntilChanged()
                .collect { aggiornaSessione() }
        }
    }

    // ── focus audio: chiamate, sveglie, altre app ──────────────────────

    private fun chiediFocus(): Boolean {
        if (hoFocus) return true
        val video = stato.value.video
        val req = richiestaFocus ?: AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(if (video) AudioAttributesCompat.CONTENT_TYPE_MOVIE else AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { cambio -> scope.launch { focusCambiato(cambio) } }
            .build()
            .also { richiestaFocus = it }
        hoFocus = AudioManagerCompat.requestAudioFocus(audio, req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hoFocus) ZLog.w("Focus audio negato (chiamata in corso?): riproduzione non avviata")
        return hoFocus
    }

    private fun abbandonaFocus() {
        richiestaFocus?.let { AudioManagerCompat.abandonAudioFocusRequest(audio, it) }
        hoFocus = false
        riprendiDopoInterruzione = false
    }

    private fun focusCambiato(cambio: Int) {
        val p = mp ?: return
        when (cambio) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // un'altra app ha preso l'audio in modo stabile: pausa, senza ripartire da soli
                ZLog.i("Focus audio perso: pausa")
                hoFocus = false
                riprendiDopoInterruzione = false
                if (p.isPlaying) p.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // chiamata, sveglia, messaggio vocale: pausa e ripresa automatica alla fine
                ZLog.i("Interruzione temporanea (es. chiamata): pausa")
                riprendiDopoInterruzione = p.isPlaying || riprendiDopoInterruzione
                if (p.isPlaying) p.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // notifica o navigatore: volume basso per un attimo
                if (p.isPlaying) {
                    p.setVolume(25)
                    abbassato = true
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hoFocus = true
                if (abbassato) {
                    p.setVolume(100)
                    abbassato = false
                }
                if (riprendiDopoInterruzione) {
                    ZLog.i("Fine interruzione: riprendo")
                    riprendiDopoInterruzione = false
                    p.play()
                }
            }
        }
    }

    // ── sessione multimediale ──────────────────────────────────────────

    private fun aggiornaSessione() {
        val s = stato.value
        val c = s.corrente
        if (c == null) {
            sessione.setPlaybackState(PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_NONE, 0, 0f).build())
            sessione.isActive = false
            return
        }
        var azioni = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (s.durata > 0) azioni = azioni or PlaybackStateCompat.ACTION_SEEK_TO
        val st = when {
            s.errore != null -> PlaybackStateCompat.STATE_ERROR
            s.buffering && !s.inRiproduzione -> PlaybackStateCompat.STATE_BUFFERING
            s.inRiproduzione -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val posizione = mp?.time?.takeIf { it >= 0 } ?: s.posizione
        sessione.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(azioni)
                .setState(st, posizione, if (s.inRiproduzione) 1f else 0f)
                .apply { if (s.errore != null) setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, s.errore) }
                .build()
        )
        aggiornaCopertina(s, c)
        sessione.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, c.nome)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, c.nome)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "ZooPlayer")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (s.durata > 0) s.durata else -1)
                .apply { copertinaSessione?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        if (!sessione.isActive) sessione.isActive = true
    }

    /** Copertina: quella incorporata nel file, altrimenti quella dell'elenco (scaricata con Coil). */
    private fun aggiornaCopertina(s: StatoPlayer, c: Voce) {
        val incorporata = s.coverIncorporata
        if (incorporata != null) {
            jobArt?.cancel()
            copertinaSessione = incorporata
            urlArt = c.url
            return
        }
        if (urlArt == c.url) return
        urlArt = c.url
        copertinaSessione = null
        jobArt?.cancel()
        val cover = c.cover ?: return
        jobArt = scope.launch {
            val bmp = try {
                val r = ImageRequest.Builder(app).data(cover).size(512).allowHardware(false).build()
                (app.imageLoader.execute(r) as? SuccessResult)?.drawable?.toBitmap()
            } catch (e: Exception) {
                null
            }
            if (bmp != null && stato.value.corrente?.url == c.url && stato.value.coverIncorporata == null) {
                copertinaSessione = bmp
                aggiornaSessione()
                PlayerService.aggiornaNotifica(app)
            }
        }
    }

    // ── comandi ────────────────────────────────────────────────────────

    /** Avvia (o riprende) solo se Android concede l'audio: durante una chiamata non parte. */
    private fun suona(p: MediaPlayer) {
        if (chiediFocus()) {
            if (abbassato) {
                p.setVolume(100)
                abbassato = false
            }
            p.play()
        } else {
            stato.update { it.copy(inRiproduzione = false, buffering = false) }
        }
    }

    fun pausa() {
        riprendiDopoInterruzione = false
        mp?.let { if (it.isPlaying) it.pause() }
    }

    fun riprendi() {
        val p = mp ?: return
        when {
            stato.value.finito || stato.value.errore != null -> avvia()
            !p.isPlaying -> suona(p)
        }
    }

    private fun player(): MediaPlayer {
        mp?.let { return it }
        val lib = LibVLC(app, arrayListOf("--network-caching=3000", "--http-reconnect", "--no-stats"))
        val p = MediaPlayer(lib)
        p.setEventListener { ev -> evento(ev) }
        // la superficie video è pronta solo quando Android l'ha creata, non quando la si aggancia
        p.getVLCVout().addCallback(object : IVLCVout.Callback {
            override fun onSurfacesCreated(vlcVout: IVLCVout) {
                ZLog.i("Superficie video pronta")
                controllaVideo(ritardoMs = 150)
            }

            override fun onSurfacesDestroyed(vlcVout: IVLCVout) {}
        })
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
                if ((mp?.videoTracksCount ?: 0) > 0) segnaVideo()
            }
            // una traccia video comparsa nel flusso: è così che si riconoscono le dirette video
            MediaPlayer.Event.ESAdded ->
                if (ev.esChangedType == TRACCIA_VIDEO) segnaVideo()
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped ->
                stato.update { it.copy(inRiproduzione = false) }
            MediaPlayer.Event.Buffering ->
                stato.update { it.copy(buffering = ev.buffering < 100f) }
            MediaPlayer.Event.TimeChanged ->
                stato.update { it.copy(posizione = ev.timeChanged) }
            MediaPlayer.Event.LengthChanged ->
                stato.update { it.copy(durata = ev.lengthChanged) }
            // solo "acceso": chiudendo il player la superficie si stacca e voutCount torna 0,
            // ma il video c'è ancora
            MediaPlayer.Event.Vout -> {
                voutAttive = ev.voutCount
                if (ev.voutCount > 0) segnaVideo()
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
        val m = Media(libVlc!!, Uri.parse(Rete.codifica(v.url)))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":network-caching=3000")
        p.setMedia(m)
        m.release()
        stato.update {
            it.copy(
                posizione = 0, durata = 0, coverIncorporata = null, buffering = true,
                finito = false, errore = null, video = TipiMedia.tipo(v.url) == TipoMedia.VIDEO,
            )
        }
        suona(p)
        caricaCover(v)
    }

    /** Cover incorporata nel file audio (tag ID3/MP4/FLAC); se manca resta quella dell'elenco. */
    private fun caricaCover(v: Voce) {
        jobCover?.cancel()
        if (TipiMedia.tipo(v.url) == TipoMedia.VIDEO || TipiMedia.isDiretta(v.url)) return
        jobCover = scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(Rete.codifica(v.url), hashMapOf("User-Agent" to Rete.UA))
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
            p.isPlaying -> pausa()
            else -> suona(p)
        }
    }

    fun salta(ms: Long) {
        val p = mp ?: return
        var t = (p.time + ms).coerceAtLeast(0)
        if (p.length > 0) t = t.coerceAtMost(p.length - 1000)
        p.setTime(t)
        stato.update { it.copy(posizione = t) }
        aggiornaSessione()
    }

    fun vaiA(ms: Long) {
        mp?.setTime(ms)
        stato.update { it.copy(posizione = ms) }
        aggiornaSessione()
    }

    fun successivo(automatico: Boolean = false) {
        val s = stato.value
        if (s.indice + 1 < s.coda.size) {
            stato.update { it.copy(indice = s.indice + 1) }
            avvia()
        } else if (automatico) {
            stato.update { it.copy(inRiproduzione = false, finito = true) }
            abbandonaFocus()
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
        jobVideo?.cancel()
        try {
            mp?.stop()
        } catch (_: Exception) {
        }
        abbandonaFocus()
        jobArt?.cancel()
        urlArt = null
        copertinaSessione = null
        stato.value = StatoPlayer()
        app.stopService(Intent(app, PlayerService::class.java))
    }

    private fun segnaVideo() {
        val c = stato.value.corrente ?: return
        if (stato.value.video || TipiMedia.tipo(c.url) == TipoMedia.AUDIO) return
        ZLog.i("Traccia video rilevata: ${c.nome}")
        stato.update { it.copy(video = true) }
    }

    fun agganciaVideo(layout: VLCVideoLayout) {
        val p = player()
        if (p.getVLCVout().areViewsAttached()) p.detachViews()
        voutAttive = 0
        p.attachViews(layout, null, false, false)
        // Non riattivo subito la traccia: al primo avvio la superficie non esiste ancora
        // (viene creata un attimo dopo) e il video finirebbe nel nulla. Lo fa onSurfacesCreated,
        // più un controllo di sicurezza nel caso quell'evento arrivi prima delle tracce.
        controllaVideo(ritardoMs = 1500)
    }

    /**
     * Se c'è una traccia video ma VLC non sta disegnando (voutAttive == 0), la riseleziono
     * così l'uscita video riparte sulla superficie agganciata. Fino a 4 tentativi.
     */
    private fun controllaVideo(ritardoMs: Long) {
        jobVideo?.cancel()
        jobVideo = scope.launch {
            delay(ritardoMs)
            repeat(4) { tentativo ->
                val p = mp ?: return@launch
                if (!p.getVLCVout().areViewsAttached()) return@launch
                val id = p.videoTracks?.firstOrNull { it.id >= 0 }?.id
                if (id != null && voutAttive == 0) {
                    ZLog.i("Video nero (tentativo ${tentativo + 1}): riattivo la traccia video $id")
                    p.setVideoTrack(-1)
                    p.setVideoTrack(id)
                } else if (id != null) {
                    return@launch // il video si vede
                }
                delay(1500)
            }
            if (voutAttive == 0 && (mp?.videoTracksCount ?: 0) > 0) {
                ZLog.w("Video ancora nero dopo 4 tentativi")
            }
        }
    }

    fun sganciaVideo() {
        jobVideo?.cancel()
        val p = mp ?: return
        if (p.getVLCVout().areViewsAttached()) p.detachViews()
    }
}

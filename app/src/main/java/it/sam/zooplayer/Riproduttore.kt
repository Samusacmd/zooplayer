package it.sam.zooplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    /** true = la voce corrente è suonata da ExoPlayer (dirette), false = da VLC (file). */
    val exo: Boolean = false,
) {
    val corrente: Voce? get() = coda.getOrNull(indice)
}

/** Nuovi tentativi automatici quando una diretta va in errore (ms di attesa prima di ciascuno). */
private val RIPROVE_DIRETTA_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)

/**
 * Server gemelli del CDN Mediaset: se uno risponde male si prova l'altro.
 * live03-col <-> live03-seg (stesso percorso, stesso contenuto).
 */
private fun specchio(url: String): String? {
    val m = Regex("""^(https?://live\d+-)(col|seg)(\.msr\.cdn\.mediaset\.net/.*)$""").find(url) ?: return null
    val altro = if (m.groupValues[2] == "col") "seg" else "col"
    return m.groupValues[1] + altro + m.groupValues[3]
}

/** Tipo di traccia "video" negli eventi ESAdded di libVLC (0 = audio, 1 = video, 2 = sottotitoli). */
private const val TRACCIA_VIDEO = 1

private enum class Motore { NESSUNO, VLC, EXO }

/**
 * Unico player dell'app, con due motori:
 * - ExoPlayer (Media3) per le dirette DASH/HLS (.mpd, .m3u8);
 * - libVLC per tutti i file (MP3, MP4, WMA, FLV, MKV…), che ExoPlayer non sempre legge.
 * Vive nel processo; PlayerService lo tiene attivo in background.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object Riproduttore {
    private lateinit var app: Context
    private var libVlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var exo: ExoPlayer? = null
    private var motore = Motore.NESSUNO
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

    private var tentativoDiretta = 0
    private var jobRiprova: Job? = null
    private var jobPosizione: Job? = null

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
                    if (suonaOra()) {
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

    // ── operazioni comuni ai due motori ────────────────────────────────

    /** Sta suonando o sta per suonare (ExoPlayer in caricamento con play premuto). */
    private fun suonaOra(): Boolean = when (motore) {
        Motore.VLC -> mp?.isPlaying == true
        Motore.EXO -> exo?.playWhenReady == true
        Motore.NESSUNO -> false
    }

    private fun playMotore() {
        when (motore) {
            Motore.VLC -> mp?.play()
            Motore.EXO -> exo?.play()
            Motore.NESSUNO -> {}
        }
    }

    private fun pausaMotore() {
        when (motore) {
            Motore.VLC -> mp?.pause()
            Motore.EXO -> exo?.pause()
            Motore.NESSUNO -> {}
        }
    }

    private fun volumeMotore(percento: Int) {
        when (motore) {
            Motore.VLC -> mp?.setVolume(percento)
            Motore.EXO -> exo?.volume = percento / 100f
            Motore.NESSUNO -> {}
        }
    }

    private fun posizioneMotore(): Long = when (motore) {
        Motore.VLC -> mp?.time?.takeIf { it >= 0 } ?: stato.value.posizione
        Motore.EXO -> exo?.currentPosition ?: stato.value.posizione
        Motore.NESSUNO -> 0L
    }

    /** Durata seekabile: per una diretta ExoPlayer è la finestra disponibile (es. 2 minuti). */
    private fun lunghezzaMotore(): Long = when (motore) {
        Motore.VLC -> mp?.length ?: 0L
        Motore.EXO -> exo?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
        Motore.NESSUNO -> 0L
    }

    private fun spostaMotore(ms: Long) {
        when (motore) {
            Motore.VLC -> mp?.setTime(ms)
            Motore.EXO -> exo?.seekTo(ms)
            Motore.NESSUNO -> {}
        }
    }

    /** Ferma il motore che non serve più (si passa da una diretta a un file o viceversa). */
    private fun fermaMotore(quale: Motore) {
        try {
            when (quale) {
                Motore.VLC -> mp?.stop()
                Motore.EXO -> {
                    jobPosizione?.cancel()
                    exo?.stop()
                    exo?.clearMediaItems()
                }
                Motore.NESSUNO -> {}
            }
        } catch (_: Exception) {
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
        if (motore == Motore.NESSUNO) return
        when (cambio) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // un'altra app ha preso l'audio in modo stabile: pausa, senza ripartire da soli
                ZLog.i("Focus audio perso: pausa")
                hoFocus = false
                riprendiDopoInterruzione = false
                if (suonaOra()) pausaMotore()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // chiamata, sveglia, messaggio vocale: pausa e ripresa automatica alla fine
                ZLog.i("Interruzione temporanea (es. chiamata): pausa")
                riprendiDopoInterruzione = suonaOra() || riprendiDopoInterruzione
                if (suonaOra()) pausaMotore()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // notifica o navigatore: volume basso per un attimo
                if (suonaOra()) {
                    volumeMotore(25)
                    abbassato = true
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hoFocus = true
                if (abbassato) {
                    volumeMotore(100)
                    abbassato = false
                }
                if (riprendiDopoInterruzione) {
                    ZLog.i("Fine interruzione: riprendo")
                    riprendiDopoInterruzione = false
                    playMotore()
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
        sessione.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(azioni)
                .setState(st, posizioneMotore(), if (s.inRiproduzione) 1f else 0f)
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
    private fun suona() {
        if (chiediFocus()) {
            if (abbassato) {
                volumeMotore(100)
                abbassato = false
            }
            playMotore()
        } else {
            stato.update { it.copy(inRiproduzione = false, buffering = false) }
        }
    }

    fun pausa() {
        riprendiDopoInterruzione = false
        if (suonaOra()) pausaMotore()
    }

    fun riprendi() {
        if (motore == Motore.NESSUNO) return
        when {
            stato.value.finito || stato.value.errore != null -> avvia()
            !suonaOra() -> suona()
        }
    }

    // ── motore VLC (file) ──────────────────────────────────────────────

    private fun vlc(): MediaPlayer {
        mp?.let { return it }
        val lib = LibVLC(app, arrayListOf("--network-caching=3000", "--http-reconnect", "--no-stats"))
        val p = MediaPlayer(lib)
        p.setEventListener { ev -> eventoVlc(ev) }
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

    private fun eventoVlc(ev: MediaPlayer.Event) {
        if (motore != Motore.VLC) return
        when (ev.type) {
            MediaPlayer.Event.Playing -> {
                stato.update { it.copy(inRiproduzione = true, buffering = false, finito = false, errore = null) }
                stato.value.corrente?.let { Db.segnaAscoltato(it.url) }
                if ((mp?.videoTracksCount ?: 0) > 0) segnaVideo()
            }
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
            MediaPlayer.Event.EncounteredError -> erroreRiproduzione("errore VLC")
        }
    }

    // ── motore ExoPlayer (dirette) ─────────────────────────────────────

    private fun exoPlayer(): ExoPlayer {
        exo?.let { return it }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Rete.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val e = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(app).setDataSourceFactory(http))
            .build()
        e.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = aggiornaDaExo()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = aggiornaDaExo()

            override fun onTracksChanged(tracks: Tracks) {
                if (motore != Motore.EXO || tracks.groups.isEmpty()) return
                val haVideo = tracks.containsType(C.TRACK_TYPE_VIDEO)
                val c = stato.value.corrente ?: return
                if (haVideo != stato.value.video) {
                    ZLog.i(if (haVideo) "Traccia video rilevata: ${c.nome}" else "Nessuna traccia video in ${c.nome}: mostro la copertina")
                    stato.update { it.copy(video = haVideo) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (motore != Motore.EXO) return
                val e2 = exo ?: return
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // rimasti troppo indietro nella diretta (es. lunga pausa): si torna al live
                    ZLog.i("Diretta: rimasto indietro, torno al live")
                    e2.seekToDefaultPosition()
                    e2.prepare()
                    return
                }
                val http = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                val dettaglio = buildString {
                    append(error.errorCodeName)
                    if (http != null) append(" HTTP ${http.responseCode} su ${http.dataSpec.uri}")
                    error.cause?.message?.let { append(" - ").append(it.take(200)) }
                }
                erroreRiproduzione(dettaglio)
            }
        })
        exo = e
        ZLog.i("ExoPlayer inizializzato")
        return e
    }

    private fun aggiornaDaExo() {
        if (motore != Motore.EXO) return
        val e = exo ?: return
        val st = e.playbackState
        when (st) {
            Player.STATE_ENDED -> {
                stato.update { it.copy(inRiproduzione = false, buffering = false) }
                scope.launch {
                    delay(300)
                    successivo(automatico = true)
                }
            }
            Player.STATE_READY -> {
                stato.update { it.copy(inRiproduzione = e.playWhenReady, buffering = false, errore = null) }
                if (e.playWhenReady) {
                    tentativoDiretta = 0
                    stato.value.corrente?.let { Db.segnaAscoltato(it.url) }
                }
            }
            Player.STATE_BUFFERING ->
                stato.update { it.copy(inRiproduzione = e.playWhenReady, buffering = true) }
            Player.STATE_IDLE ->
                stato.update { it.copy(inRiproduzione = false) }
        }
    }

    /** ExoPlayer non manda la posizione a eventi come VLC: la leggo ogni mezzo secondo. */
    private fun seguiPosizioneExo() {
        jobPosizione?.cancel()
        jobPosizione = scope.launch {
            while (isActive && motore == Motore.EXO) {
                val e = exo ?: break
                val live = e.isCurrentMediaItemLive
                stato.update {
                    it.copy(
                        posizione = e.currentPosition.coerceAtLeast(0),
                        // per una diretta la barra resta disattivata ("live")
                        durata = if (live) 0L else (e.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0L),
                    )
                }
                delay(500)
            }
        }
    }

    // ── errori e nuovi tentativi ───────────────────────────────────────

    private fun erroreRiproduzione(dettaglio: String) {
        val c = stato.value.corrente
        ZLog.e("Errore di riproduzione: ${c?.url} (tentativo ${tentativoDiretta + 1}) - $dettaglio")
        if (c != null && TipiMedia.isDiretta(c.url) && tentativoDiretta < RIPROVE_DIRETTA_MS.size) {
            // riprovo da solo, alternando il server gemello, invece di lasciare lo schermo fermo
            val prossimo = tentativoDiretta + 1
            val attesa = RIPROVE_DIRETTA_MS[tentativoDiretta]
            ZLog.i("Diretta non partita: riprovo tra ${attesa / 1000} s (tentativo ${prossimo + 1} di ${RIPROVE_DIRETTA_MS.size + 1})")
            stato.update { it.copy(inRiproduzione = false, buffering = true, errore = null) }
            jobRiprova?.cancel()
            jobRiprova = scope.launch {
                delay(attesa)
                if (stato.value.corrente?.url == c.url) avvia(prossimo)
            }
        } else {
            val msg = if (c != null && TipiMedia.isDiretta(c.url))
                "Diretta non disponibile in questo momento: riprova tra poco"
            else "Impossibile riprodurre questo file"
            stato.update { it.copy(inRiproduzione = false, buffering = false, errore = msg) }
        }
    }

    // ── avvio ──────────────────────────────────────────────────────────

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

    /** tentativo > 0 solo per i nuovi tentativi automatici delle dirette. */
    private fun avvia(tentativo: Int = 0) {
        val v = stato.value.corrente ?: return
        jobRiprova?.cancel()
        jobVideo?.cancel()
        tentativoDiretta = tentativo
        val diretta = TipiMedia.isDiretta(v.url)
        val nuovo = if (diretta) Motore.EXO else Motore.VLC
        if (motore != nuovo) fermaMotore(motore)
        motore = nuovo
        // nei tentativi dispari provo il server gemello, se esiste
        val sorgente = if (tentativo % 2 == 1) specchio(v.url) ?: v.url else v.url
        ZLog.i(
            "Riproduco con ${if (diretta) "ExoPlayer" else "VLC"}: ${v.nome} -> $sorgente" +
                if (tentativo > 0) " (tentativo ${tentativo + 1})" else ""
        )
        val dash = TipiMedia.estensione(v.url) == "mpd"
        stato.update {
            it.copy(
                posizione = 0, durata = 0, coverIncorporata = null, buffering = true,
                finito = false, errore = null, exo = diretta,
                // le dirette DASH si aprono subito come video; se poi non c'è video lo dicono le tracce
                video = TipiMedia.tipo(v.url) == TipoMedia.VIDEO || dash,
            )
        }
        if (diretta) {
            val e = exoPlayer()
            val item = MediaItem.Builder()
                .setUri(Rete.codifica(sorgente))
                .setMimeType(if (dash) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8)
                .build()
            e.setMediaItem(item)
            e.prepare()
            seguiPosizioneExo()
            suona()
        } else {
            val p = vlc()
            val m = Media(libVlc!!, Uri.parse(Rete.codifica(sorgente)))
            m.setHWDecoderEnabled(true, false)
            m.addOption(":network-caching=3000")
            p.setMedia(m)
            m.release()
            suona()
        }
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
        if (motore == Motore.NESSUNO) return
        when {
            stato.value.finito || stato.value.errore != null -> avvia()
            suonaOra() -> pausa()
            else -> suona()
        }
    }

    fun salta(ms: Long) {
        if (motore == Motore.NESSUNO) return
        var t = (posizioneMotore() + ms).coerceAtLeast(0)
        val lunghezza = lunghezzaMotore()
        if (lunghezza > 0) t = t.coerceAtMost(lunghezza - 1000)
        spostaMotore(t)
        stato.update { it.copy(posizione = t) }
        aggiornaSessione()
    }

    fun vaiA(ms: Long) {
        spostaMotore(ms)
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
        jobRiprova?.cancel()
        jobPosizione?.cancel()
        tentativoDiretta = 0
        fermaMotore(Motore.VLC)
        fermaMotore(Motore.EXO)
        motore = Motore.NESSUNO
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

    // ── superficie video ───────────────────────────────────────────────

    /** Riquadro video di ExoPlayer: si può agganciare in qualsiasi momento, anche a diretta avviata. */
    fun agganciaExo(view: PlayerView) {
        view.player = exoPlayer()
    }

    fun sganciaExo(view: PlayerView) {
        view.player = null
    }

    fun agganciaVideo(layout: VLCVideoLayout) {
        val p = vlc()
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
        if (motore != Motore.VLC) return
        jobVideo = scope.launch {
            delay(ritardoMs)
            repeat(4) { tentativo ->
                val p = mp ?: return@launch
                if (motore != Motore.VLC || !p.getVLCVout().areViewsAttached()) return@launch
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

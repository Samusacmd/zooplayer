package it.sam.zooplayer

import android.widget.Toast
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import org.videolan.libvlc.util.VLCVideoLayout

private val ARANCIO = Color(0xFFFF8F00)
private val GRIGIO = Color(0xFF9E9E9E)
private val VERDE = Color(0xFF66BB6A)
private val GIALLO = Color(0xFFFFC107)

@Composable
fun ZooTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ARANCIO,
            secondary = GIALLO,
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.Black,
        ),
        content = content,
    )
}

/** Evidenzia l'elemento selezionato col telecomando (D-pad) su Android TV / Fire TV. */
private fun Modifier.evidenziaFocus(): Modifier = composed {
    var focus by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focus = it.isFocused || it.hasFocus }
        .background(if (focus) ARANCIO.copy(alpha = 0.25f) else Color.Transparent)
}

private fun segnaposto(v: Voce?): String = when {
    v == null -> "🎵"
    v.isElenco -> "📁"
    else -> when (TipiMedia.tipo(v.url)) {
        TipoMedia.VIDEO -> "🎬"
        TipoMedia.AUDIO -> "🎵"
        TipoMedia.STREAM -> "📻"
    }
}

private fun sottotitolo(v: Voce): String = when {
    v.isElenco -> "Elenco"
    TipiMedia.estensione(v.url).isNotEmpty() -> TipiMedia.estensione(v.url).uppercase()
    else -> "Stream"
}

private fun tempo(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun Copertina(url: String?, segno: String, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A)),
        contentAlignment = Alignment.Center,
    ) {
        Text(segno, fontSize = 22.sp)
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun App(
    vm: MainViewModel,
    onApriFile: () -> Unit,
    onCondividiLog: () -> Unit,
    puoScaricare: () -> Boolean,
    onEsci: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val player by Riproduttore.stato.collectAsStateWithLifecycle()
    val preferiti by Db.preferiti.collectAsStateWithLifecycle()
    val ascoltati by Db.ascoltati.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var dialogoUrl by remember { mutableStateOf(false) }
    var dialogoInfo by remember { mutableStateOf(false) }

    BackHandler { if (!vm.indietro()) onEsci() }

    LaunchedEffect(ui.messaggio) {
        val m = ui.messaggio
        if (m != null) {
            Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
            vm.messaggioMostrato()
        }
    }

    val scarica: (Voce) -> Unit = { v -> if (puoScaricare()) vm.scarica(v) }
    val correnteUrl = player.corrente?.url

    if (ui.schermoPlayer && player.corrente != null) {
        SchermoPlayer(player, onChiudi = vm::chiudiPlayer)
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Barra(
                    ui = ui, vm = vm,
                    onApriUrl = { dialogoUrl = true },
                    onApriFile = onApriFile,
                    onCondividiLog = onCondividiLog,
                    onInfo = { dialogoInfo = true },
                    puoScaricare = puoScaricare,
                )
            },
            bottomBar = {
                if (player.corrente != null) MiniPlayer(player, onApri = vm::apriPlayer)
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when {
                    ui.ricerca != null -> VistaRicerca(ui, preferiti, ascoltati, correnteUrl, vm, puoScaricare)
                    ui.preferitiAperti -> VistaVoci(
                        voci = ui.listaPreferiti, vista = Vista.LISTA, urlLivello = "★preferiti",
                        preferiti = preferiti, ascoltati = ascoltati, correnteUrl = correnteUrl, vm = vm,
                        onScarica = scarica, vuoto = "Nessun preferito: tocca ☆ accanto a un elenco o a un file",
                    )
                    ui.pila.isEmpty() -> if (!ui.caricamento) Benvenuto(vm, onApriFile)
                    else -> {
                        val liv = ui.pila.last()
                        key(liv.url, ui.vista) {
                            VistaVoci(
                                voci = liv.voci, vista = ui.vista, urlLivello = liv.url,
                                preferiti = preferiti, ascoltati = ascoltati, correnteUrl = correnteUrl, vm = vm,
                                onScarica = scarica, vuoto = "Elenco vuoto",
                            )
                        }
                    }
                }
                if (ui.caricamento) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (dialogoUrl) DialogoUrl(vm.ultimoLink(), onApri = { dialogoUrl = false; vm.apriRadice(it) }, onAnnulla = { dialogoUrl = false })
    if (dialogoInfo) DialogoInfo(onChiudi = { dialogoInfo = false })
    ui.confermaDownload?.let { c ->
        AlertDialog(
            onDismissRequest = vm::annullaScaricaTutto,
            title = { Text("Scarica tutto") },
            text = {
                Text(
                    if (c.righe.isEmpty()) "Nessun file trovato in '${c.titolo}' e nei suoi sotto-elenchi."
                    else "Stai per scaricare ${c.righe.size} file da '${c.titolo}' (sotto-elenchi compresi).\n\n" +
                        "Verranno salvati in Download/ZooPlayer. Possono occupare molto spazio e traffico dati."
                )
            },
            confirmButton = {
                if (c.righe.isNotEmpty()) TextButton(onClick = vm::confermaScaricaTutto) { Text("Scarica ${c.righe.size} file") }
            },
            dismissButton = { TextButton(onClick = vm::annullaScaricaTutto) { Text("Annulla") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Barra(
    ui: UiStato,
    vm: MainViewModel,
    onApriUrl: () -> Unit,
    onApriFile: () -> Unit,
    onCondividiLog: () -> Unit,
    onInfo: () -> Unit,
    puoScaricare: () -> Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    val titolo = when {
        ui.ricerca != null -> "Ricerca"
        ui.preferitiAperti -> "Preferiti"
        else -> ui.pila.lastOrNull()?.titolo ?: "ZooPlayer"
    }
    val percorso = if (ui.ricerca == null && !ui.preferitiAperti && ui.pila.size > 1)
        ui.pila.dropLast(1).joinToString(" › ") { it.titolo } else null
    val puoTornare = ui.pila.size > 1 || ui.ricerca != null || ui.preferitiAperti

    TopAppBar(
        title = {
            Column {
                Text(titolo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (percorso != null) {
                    Text(percorso, fontSize = 11.sp, color = GRIGIO, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        navigationIcon = {
            if (puoTornare) {
                IconButton(onClick = { vm.indietro() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                }
            }
        },
        actions = {
            IconButton(onClick = vm::apriRicerca) { Icon(Icons.Filled.Search, contentDescription = "Cerca") }
            IconButton(onClick = vm::apriPreferiti) { Icon(Icons.Filled.Star, contentDescription = "Preferiti") }
            IconButton(onClick = vm::cambiaVista) {
                Text(
                    when (ui.vista) {
                        Vista.LISTA -> "☰"
                        Vista.ELENCO -> "≡"
                        Vista.BOX -> "▦"
                    },
                    fontSize = 20.sp,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    VoceMenu("Apri link JSON") { menu = false; onApriUrl() }
                    VoceMenu("Apri JSON locale") { menu = false; onApriFile() }
                    if (ui.pila.isNotEmpty()) {
                        HorizontalDivider()
                        VoceMenu("Torna all'inizio") { menu = false; vm.tornaAllaRadice() }
                        VoceMenu("Aggiorna elenco") { menu = false; vm.aggiorna() }
                        VoceMenu("Aggiorna indice ricerca") { menu = false; vm.aggiornaIndice() }
                        VoceMenu("Scarica tutto questo elenco") { menu = false; if (puoScaricare()) vm.preparaScaricaTutto() }
                    }
                    HorizontalDivider()
                    VoceMenu("Condividi log") { menu = false; onCondividiLog() }
                    VoceMenu("Info") { menu = false; onInfo() }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A)),
    )
}

@Composable
private fun VoceMenu(testo: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(testo) }, onClick = onClick)
}

@Composable
private fun Benvenuto(vm: MainViewModel, onApriFile: () -> Unit) {
    var link by remember { mutableStateOf(vm.ultimoLink()) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ZooPlayer", fontSize = 32.sp, color = ARANCIO)
        Text("v${BuildConfig.VERSION_NAME}", color = GRIGIO)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("Link al JSON padre") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.apriRadice(link) }, enabled = link.isNotBlank()) { Text("Apri") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onApriFile) { Text("Apri JSON locale") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VistaVoci(
    voci: List<Voce>,
    vista: Vista,
    urlLivello: String,
    preferiti: Set<String>,
    ascoltati: Set<String>,
    correnteUrl: String?,
    vm: MainViewModel,
    onScarica: (Voce) -> Unit,
    vuoto: String,
) {
    if (voci.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(vuoto, color = GRIGIO) }
    } else if (vista == Vista.BOX) {
        val stato = rememberLazyGridState(vm.posizione(urlLivello))
        DisposableEffect(urlLivello) { onDispose { vm.salvaPosizione(urlLivello, stato.firstVisibleItemIndex) } }
        LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), state = stato, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(voci) { _, v ->
                Column(
                    Modifier
                        .padding(4.dp)
                        .evidenziaFocus()
                        .combinedClickable(onClick = { vm.clicca(v, voci) }, onLongClick = { vm.togglePreferito(v) })
                        .padding(6.dp)
                ) {
                    Box {
                        Copertina(v.cover, segnaposto(v), Modifier.fillMaxWidth().aspectRatio(1f))
                        if (v.url in preferiti) Text("★", color = GIALLO, fontSize = 20.sp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                        if (v.url in ascoltati) Text("✔", color = VERDE, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
                    }
                    Text(
                        v.nome, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                        color = if (v.url == correnteUrl) ARANCIO else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    } else {
        val compatta = vista == Vista.ELENCO
        val stato = rememberLazyListState(vm.posizione(urlLivello))
        DisposableEffect(urlLivello) { onDispose { vm.salvaPosizione(urlLivello, stato.firstVisibleItemIndex) } }
        LazyColumn(state = stato, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(voci) { _, v ->
                RigaVoce(
                    v = v, compatta = compatta,
                    preferito = v.url in preferiti, ascoltato = v.url in ascoltati, corrente = v.url == correnteUrl,
                    onClick = { vm.clicca(v, voci) },
                    onPreferito = { vm.togglePreferito(v) },
                    onScarica = { onScarica(v) },
                )
            }
        }
    }
}

@Composable
private fun RigaVoce(
    v: Voce,
    compatta: Boolean,
    preferito: Boolean,
    ascoltato: Boolean,
    corrente: Boolean,
    onClick: () -> Unit,
    onPreferito: () -> Unit,
    onScarica: () -> Unit,
    sotto: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .evidenziaFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (compatta) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compatta) {
            Text(segnaposto(v), fontSize = 16.sp)
        } else {
            Copertina(v.cover, segnaposto(v), Modifier.size(56.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                v.nome,
                maxLines = if (compatta) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = if (compatta) 14.sp else 16.sp,
                color = if (corrente) ARANCIO else MaterialTheme.colorScheme.onSurface,
            )
            if (!compatta) Text(sotto ?: sottotitolo(v), fontSize = 11.sp, color = GRIGIO, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ascoltato) Text("✔", color = VERDE, modifier = Modifier.padding(horizontal = 6.dp))
        IconButton(onClick = onPreferito) {
            Text(if (preferito) "★" else "☆", color = GIALLO, fontSize = 20.sp)
        }
        if (!v.isElenco) {
            IconButton(onClick = onScarica) { Text("⬇", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun VistaRicerca(
    ui: UiStato,
    preferiti: Set<String>,
    ascoltati: Set<String>,
    correnteUrl: String?,
    vm: MainViewModel,
    puoScaricare: () -> Boolean,
) {
    val fr = remember { FocusRequester() }
    val q = ui.ricerca ?: ""
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q,
            onValueChange = vm::cerca,
            label = { Text("Cerca in tutti gli elenchi (min. 3 caratteri)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(fr),
        )
        LaunchedEffect(Unit) {
            try {
                fr.requestFocus()
            } catch (_: Exception) {
            }
        }
        val stato = ui.indicizzazione ?: when {
            q.trim().length < 3 -> "Scrivi almeno 3 caratteri. I numeri devono combaciare per intero (03 non trova 2003)."
            else -> "${ui.risultati.size} risultati" + if (ui.risultati.size >= 500) " (mostrati i primi 500)" else ""
        }
        Text(stato, color = GRIGIO, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(ui.risultati) { _, r ->
                val v = r.comeVoce()
                RigaVoce(
                    v = v, compatta = false,
                    preferito = r.url in preferiti, ascoltato = r.url in ascoltati, corrente = r.url == correnteUrl,
                    onClick = { vm.riproduciRisultato(r) },
                    onPreferito = { vm.togglePreferito(v) },
                    onScarica = { if (puoScaricare()) vm.scaricaRiga(r) },
                    sotto = r.percorso.ifBlank { sottotitolo(v) },
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(s: StatoPlayer, onApri: () -> Unit) {
    Surface(color = Color(0xFF1A1A1A)) {
        Row(
            Modifier.fillMaxWidth().evidenziaFocus().clickable(onClick = onApri).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bmp = s.coverIncorporata
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Copertina(s.corrente?.cover, segnaposto(s.corrente), Modifier.size(44.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.corrente?.nome ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                val frac = if (s.durata > 0) (s.posizione.toFloat() / s.durata).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            TextButton(onClick = { Riproduttore.salta(-10_000) }) { Text("−10") }
            IconButton(onClick = { Riproduttore.playPausa() }) {
                Icon(
                    painterResource(if (s.inRiproduzione) R.drawable.ic_pausa else R.drawable.ic_play),
                    contentDescription = if (s.inRiproduzione) "Pausa" else "Riproduci",
                    tint = ARANCIO, modifier = Modifier.size(28.dp),
                )
            }
            TextButton(onClick = { Riproduttore.salta(10_000) }) { Text("+10") }
            IconButton(onClick = { Riproduttore.ferma() }) {
                Icon(painterResource(R.drawable.ic_chiudi), contentDescription = "Ferma", tint = GRIGIO, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun Context.trovaActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Player a tutto schermo.
 * - Video: comandi sovrapposti che spariscono dopo 4 s di riproduzione; tocco (o tasto del telecomando) li fa ricomparire.
 * - Schermo intero: nasconde le barre di sistema e, per i video, ruota in orizzontale.
 * - In orizzontale i comandi sono compatti.
 */
@Composable
private fun SchermoPlayer(s: StatoPlayer, onChiudi: () -> Unit) {
    val view = LocalView.current
    val activity = LocalContext.current.trovaActivity()
    val orizzontale = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var schermoIntero by remember { mutableStateOf(false) }
    var visibili by remember { mutableStateOf(true) }
    var tocchi by remember { mutableIntStateOf(0) }
    val fuocoRadice = remember { FocusRequester() }
    val compatti = orizzontale || schermoIntero
    val tocca = { tocchi++; visibili = true }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // barre di sistema e orientamento
    DisposableEffect(schermoIntero, s.video) {
        if (activity != null) {
            val ctrl = WindowCompat.getInsetsController(activity.window, view)
            if (schermoIntero) {
                ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
                if (s.video) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ctrl.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onDispose {
            if (activity != null) {
                WindowCompat.getInsetsController(activity.window, view).show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // scomparsa automatica dei comandi (solo video in riproduzione)
    LaunchedEffect(visibili, tocchi, s.inRiproduzione, s.video) {
        if (visibili && s.video && s.inRiproduzione) {
            delay(4000)
            visibili = false
            try {
                fuocoRadice.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    BackHandler(enabled = schermoIntero) { schermoIntero = false }

    val comandi: @Composable (Modifier) -> Unit = { mod ->
        ComandiPlayer(
            s = s, compatti = compatti, schermoIntero = schermoIntero,
            onTocco = tocca,
            onSchermoIntero = { tocca(); schermoIntero = !schermoIntero },
            onChiudi = onChiudi,
            modifier = mod,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(fuocoRadice)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (!visibili) {
                    tocca()   // il primo tasto del telecomando mostra solo i comandi
                    true
                } else {
                    tocchi++
                    false
                }
            }
            .focusable()
    ) {
        if (s.video) {
            AndroidView(
                factory = { c -> VLCVideoLayout(c).also { Riproduttore.agganciaVideo(it) } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { Riproduttore.sganciaVideo() },
            )
            // strato trasparente sopra il video: il tocco mostra/nasconde i comandi
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (visibili) visibili = false else tocca()
                    }
            )
            if (s.buffering) CircularProgressIndicator(Modifier.align(Alignment.Center))
            AnimatedVisibility(
                visible = visibili, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                comandi(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(top = 24.dp)
                )
            }
        } else {
            // audio: cover + comandi sempre visibili; in orizzontale affiancati
            val bmp = s.coverIncorporata
            val cover: @Composable (Modifier) -> Unit = { mod ->
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = mod.clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Copertina(s.corrente?.cover, segnaposto(s.corrente), mod)
                }
            }
            val contenitore = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            if (orizzontale) {
                Row(contenitore.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.Center) {
                        cover(Modifier.fillMaxHeight(0.9f).aspectRatio(1f))
                        if (s.buffering) CircularProgressIndicator()
                    }
                    comandi(Modifier.weight(1.3f))
                }
            } else {
                Column(contenitore) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        cover(Modifier.fillMaxHeight(0.85f).aspectRatio(1f))
                        if (s.buffering) CircularProgressIndicator()
                    }
                    comandi(Modifier.fillMaxWidth().background(Color(0xFF121212)))
                }
            }
        }
    }
}

@Composable
private fun TastoIcona(icona: Int, descrizione: String, dimensione: Dp, pad: PaddingValues, onTocco: () -> Unit, azione: () -> Unit) {
    TextButton(onClick = { onTocco(); azione() }, contentPadding = pad) {
        Icon(painterResource(icona), contentDescription = descrizione, tint = Color.White, modifier = Modifier.size(dimensione))
    }
}

@Composable
private fun TastoPlayer(etichetta: String, dimensione: TextUnit, pad: PaddingValues, onTocco: () -> Unit, azione: () -> Unit) {
    TextButton(onClick = { onTocco(); azione() }, contentPadding = pad) {
        Text(etichetta, fontSize = dimensione, color = Color.White)
    }
}

@Composable
private fun ComandiPlayer(
    s: StatoPlayer,
    compatti: Boolean,
    schermoIntero: Boolean,
    onTocco: () -> Unit,
    onSchermoIntero: () -> Unit,
    onChiudi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val testo = if (compatti) 13.sp else 16.sp
    val iconaPiccola = if (compatti) 22.dp else 28.dp
    val iconaGrande = if (compatti) 30.dp else 40.dp
    val pad = if (compatti) PaddingValues(horizontal = 6.dp, vertical = 0.dp) else ButtonDefaults.TextButtonContentPadding

    Column(modifier.padding(horizontal = if (compatti) 12.dp else 16.dp, vertical = if (compatti) 2.dp else 8.dp)) {
        Text(
            s.corrente?.nome ?: "", fontSize = if (compatti) 13.sp else 16.sp,
            maxLines = if (compatti) 1 else 2, overflow = TextOverflow.Ellipsis, color = Color.White,
        )
        val err = s.errore
        if (err != null) Text(err, color = Color(0xFFEF5350), fontSize = 12.sp)
        var trascina by remember { mutableStateOf<Float?>(null) }
        val frac = if (s.durata > 0) (s.posizione.toFloat() / s.durata).coerceIn(0f, 1f) else 0f
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tempo(s.posizione), color = GRIGIO, fontSize = 11.sp)
            Slider(
                value = trascina ?: frac,
                onValueChange = { onTocco(); trascina = it },
                onValueChangeFinished = {
                    trascina?.let { Riproduttore.vaiA((it * s.durata).toLong()) }
                    trascina = null
                },
                enabled = s.durata > 0,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(if (compatti) 28.dp else 40.dp),
            )
            Text(if (s.durata > 0) tempo(s.durata) else "live", color = GRIGIO, fontSize = 11.sp)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TastoIcona(R.drawable.ic_riduci, "Torna all'elenco", iconaPiccola, pad, onTocco, onChiudi)
            TastoIcona(R.drawable.ic_precedente, "Precedente", iconaPiccola, pad, onTocco) { Riproduttore.precedente() }
            TastoPlayer("−10", testo, pad, onTocco) { Riproduttore.salta(-10_000) }
            TastoIcona(
                if (s.inRiproduzione) R.drawable.ic_pausa else R.drawable.ic_play,
                if (s.inRiproduzione) "Pausa" else "Riproduci", iconaGrande, pad, onTocco,
            ) { Riproduttore.playPausa() }
            TastoPlayer("+10", testo, pad, onTocco) { Riproduttore.salta(10_000) }
            TastoIcona(R.drawable.ic_successivo, "Successivo", iconaPiccola, pad, onTocco) { Riproduttore.successivo() }
            TastoIcona(R.drawable.ic_stop, "Ferma", iconaPiccola, pad, onTocco) { Riproduttore.ferma() }
            IconButton(onClick = onSchermoIntero, modifier = Modifier.size(if (compatti) 36.dp else 44.dp)) {
                Icon(
                    painterResource(if (schermoIntero) R.drawable.ic_esci_schermo_intero else R.drawable.ic_schermo_intero),
                    contentDescription = if (schermoIntero) "Esci da schermo intero" else "Schermo intero",
                    tint = Color.White,
                )
            }
        }
        if (!compatti) {
            Text(
                "${s.indice + 1} / ${s.coda.size}", color = GRIGIO, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun DialogoUrl(iniziale: String, onApri: (String) -> Unit, onAnnulla: () -> Unit) {
    var link by remember { mutableStateOf(iniziale) }
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Apri link JSON") },
        text = {
            OutlinedTextField(
                value = link, onValueChange = { link = it }, singleLine = true,
                label = { Text("Link al JSON padre (pastebin, S4M, filedn…)") },
            )
        },
        confirmButton = { TextButton(onClick = { onApri(link) }, enabled = link.isNotBlank()) { Text("Apri") } },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}

@Composable
private fun DialogoInfo(onChiudi: () -> Unit) {
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text("ZooPlayer ${BuildConfig.VERSION_NAME}") },
        text = {
            Text(
                "Build ${BuildConfig.VERSION_CODE}\n" +
                    "Motore: libVLC (MP3, MP4, WMA, FLV, MKV e stream)\n\n" +
                    "Log: ${ZLog.file()?.absolutePath ?: "-"}\n" +
                    "Database: zooplayer_indice.db\n" +
                    "Download: Download/ZooPlayer\n\n" +
                    "Vista box: tieni premuto un riquadro per ★"
            )
        },
        confirmButton = { TextButton(onClick = onChiudi) { Text("Chiudi") } },
    )
}

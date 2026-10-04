package it.sam.zooplayer

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Livello(
    val titolo: String,
    val url: String,
    val cover: String?,
    val voci: List<Voce> = emptyList(),
)

enum class Vista { LISTA, ELENCO, BOX }

data class ConfermaDownload(val titolo: String, val righe: List<RigaIndice>)

data class UiStato(
    val pila: List<Livello> = emptyList(),
    val caricamento: Boolean = false,
    val messaggio: String? = null,
    val vista: Vista = Vista.LISTA,
    val ricerca: String? = null,          // null = ricerca chiusa
    val risultati: List<RigaIndice> = emptyList(),
    val indicizzazione: String? = null,   // testo di avanzamento, null = ferma
    val preferitiAperti: Boolean = false,
    val listaPreferiti: List<Voce> = emptyList(),
    val confermaDownload: ConfermaDownload? = null,
    val schermoPlayer: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("zooplayer", Context.MODE_PRIVATE)
    private val posizioni = HashMap<String, Int>()
    private var jobRicerca: Job? = null
    private var jobIndice: Job? = null

    val ui = MutableStateFlow(
        UiStato(vista = runCatching { Vista.valueOf(prefs.getString("vista", "LISTA")!!) }.getOrDefault(Vista.LISTA))
    )

    init {
        ripristinaSessione()
    }

    // ── sessione ───────────────────────────────────────────────────────

    private fun salvaSessione() {
        val arr = JSONArray()
        ui.value.pila.forEach {
            arr.put(JSONObject().put("t", it.titolo).put("u", it.url).put("c", it.cover ?: "").put("p", posizioni[it.url] ?: 0))
        }
        prefs.edit().putString("sessione", arr.toString()).apply()
    }

    private fun ripristinaSessione() {
        val arr = try {
            JSONArray(prefs.getString("sessione", null) ?: return)
        } catch (_: Exception) {
            return
        }
        if (arr.length() == 0) return
        viewModelScope.launch {
            ui.update { it.copy(caricamento = true) }
            val pila = ArrayList<Livello>()
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val url = o.getString("u")
                val cover = o.optString("c").ifBlank { null }
                posizioni[url] = o.optInt("p", 0)
                try {
                    pila += Livello(o.getString("t"), url, cover, Repo.caricaVoci(ctx, url, cover, false))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ZLog.w("Ripristino sessione interrotto a $url: ${e.message}")
                    break
                }
            }
            ui.update { it.copy(pila = pila, caricamento = false) }
            ZLog.i("Sessione ripristinata: ${pila.size} livelli")
        }
    }

    fun posizione(url: String): Int = posizioni[url] ?: 0

    fun salvaPosizione(url: String, indice: Int) {
        posizioni[url] = indice
        salvaSessione()
    }

    // ── navigazione ────────────────────────────────────────────────────

    fun apriRadice(input: String) {
        val url = Parser.normalizzaUrlJson(input)
        if (url.isBlank()) return
        prefs.edit().putString("ultimo_link", url).apply()
        carica(Livello(titoloDi(url), url, null), radice = true)
    }

    fun ultimoLink(): String = prefs.getString("ultimo_link", "") ?: ""

    private fun titoloDi(url: String): String {
        if (url.startsWith("content://")) {
            try {
                ctx.contentResolver.query(Uri.parse(url), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.') }
            } catch (_: Exception) {
            }
            return "JSON locale"
        }
        return "ZooPlayer"
    }

    fun clicca(v: Voce, codaDa: List<Voce>) {
        if (v.isElenco) {
            carica(Livello(v.nome, v.url, v.cover), radice = ui.value.pila.isEmpty())
        } else {
            val media = codaDa.filter { !it.isElenco }
            riproduci(media, media.indexOf(v).coerceAtLeast(0))
        }
    }

    private fun carica(liv: Livello, radice: Boolean, forza: Boolean = false, sostituisci: Boolean = false) {
        viewModelScope.launch {
            ui.update { it.copy(caricamento = true, preferitiAperti = false, ricerca = null) }
            try {
                val voci = Repo.caricaVoci(ctx, liv.url, liv.cover, forza)
                ZLog.i("Elenco '${liv.titolo}': ${voci.size} voci (${liv.url})")
                ui.update { s ->
                    val base = when {
                        radice -> emptyList()
                        sostituisci -> s.pila.dropLast(1)
                        else -> s.pila
                    }
                    s.copy(pila = base + liv.copy(voci = voci), caricamento = false)
                }
                salvaSessione()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NonJsonException) {
                ZLog.i("'${liv.titolo}' non è un elenco JSON (${e.contentType}): lo riproduco come stream")
                ui.update { it.copy(caricamento = false) }
                riproduci(listOf(Voce(liv.titolo, liv.url, false, liv.cover)), 0)
            } catch (e: Exception) {
                ZLog.e("Errore caricando ${liv.url}", e)
                ui.update { it.copy(caricamento = false, messaggio = "Errore: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun aggiorna() {
        val s = ui.value
        val top = s.pila.lastOrNull() ?: return
        carica(top.copy(voci = emptyList()), radice = s.pila.size == 1, forza = true, sostituisci = s.pila.size > 1)
    }

    /** Ritorna true se ha gestito il tasto Indietro. */
    fun indietro(): Boolean {
        val s = ui.value
        when {
            s.schermoPlayer -> ui.update { it.copy(schermoPlayer = false) }
            s.ricerca != null -> ui.update { it.copy(ricerca = null, risultati = emptyList()) }
            s.preferitiAperti -> ui.update { it.copy(preferitiAperti = false) }
            s.pila.size > 1 -> {
                ui.update { it.copy(pila = it.pila.dropLast(1)) }
                salvaSessione()
            }
            else -> return false
        }
        return true
    }

    fun tornaAllaRadice() {
        ui.update { it.copy(pila = it.pila.take(1), ricerca = null, preferitiAperti = false) }
        salvaSessione()
    }

    fun cambiaVista() {
        val nuova = Vista.entries[(ui.value.vista.ordinal + 1) % Vista.entries.size]
        prefs.edit().putString("vista", nuova.name).apply()
        ui.update { it.copy(vista = nuova) }
    }

    fun messaggioMostrato() = ui.update { it.copy(messaggio = null) }

    private fun messaggio(m: String) = ui.update { it.copy(messaggio = m) }

    // ── riproduzione ───────────────────────────────────────────────────

    fun riproduci(coda: List<Voce>, indice: Int) {
        if (coda.isEmpty()) return
        Riproduttore.riproduci(coda, indice)
        val v = coda[indice]
        if (TipiMedia.tipo(v.url) == TipoMedia.VIDEO) ui.update { it.copy(schermoPlayer = true) }
    }

    fun apriPlayer() = ui.update { it.copy(schermoPlayer = true) }
    fun chiudiPlayer() = ui.update { it.copy(schermoPlayer = false) }

    // ── preferiti ──────────────────────────────────────────────────────

    fun togglePreferito(v: Voce) {
        Db.togglePreferito(v)
        if (ui.value.preferitiAperti) ui.update { it.copy(listaPreferiti = Db.listaPreferiti()) }
    }

    fun apriPreferiti() = ui.update { it.copy(preferitiAperti = true, ricerca = null, listaPreferiti = Db.listaPreferiti()) }

    // ── ricerca ────────────────────────────────────────────────────────

    fun apriRicerca() {
        if (ui.value.pila.isEmpty()) {
            messaggio("Apri prima un elenco")
            return
        }
        ui.update { it.copy(ricerca = "", risultati = emptyList(), preferitiAperti = false) }
    }

    fun cerca(q: String) {
        ui.update { it.copy(ricerca = q) }
        jobRicerca?.cancel()
        val radice = ui.value.pila.firstOrNull() ?: return
        if (q.trim().length < 3) {
            ui.update { it.copy(risultati = emptyList()) }
            return
        }
        jobRicerca = viewModelScope.launch {
            delay(300)
            if (!withContext(Dispatchers.IO) { Db.haIndice(radice.url) }) {
                costruisciIndice(radice, forza = false)
                jobIndice?.join()
            }
            val r = withContext(Dispatchers.IO) { Db.cerca(radice.url, q) }
            ui.update { it.copy(risultati = r) }
        }
    }

    fun aggiornaIndice() {
        val r = ui.value.pila.firstOrNull()
        if (r == null) {
            messaggio("Apri prima un elenco")
            return
        }
        costruisciIndice(r, forza = true)
    }

    private fun costruisciIndice(radice: Livello, forza: Boolean) {
        if (jobIndice?.isActive == true) return
        jobIndice = viewModelScope.launch {
            ui.update { it.copy(indicizzazione = "Indicizzazione avviata…") }
            try {
                val righe = Esploratore.raccogli(ctx, radice.url, radice.titolo, radice.cover, forza) { e, f ->
                    ui.update { it.copy(indicizzazione = "Indicizzazione: $e elenchi, $f file") }
                }
                withContext(Dispatchers.IO) { Db.salvaIndice(radice.url, righe) }
                ui.update { it.copy(indicizzazione = null, messaggio = "Indice aggiornato: ${righe.size} file") }
            } catch (e: CancellationException) {
                ui.update { it.copy(indicizzazione = null) }
                throw e
            } catch (e: Exception) {
                ZLog.e("Indicizzazione fallita", e)
                ui.update { it.copy(indicizzazione = null, messaggio = "Indicizzazione fallita: ${e.message}") }
            }
        }
        // se la ricerca è aperta, la rilancia a indice pronto
        viewModelScope.launch {
            jobIndice?.join()
            val q = ui.value.ricerca
            if (q != null && q.trim().length >= 3 && jobRicerca?.isActive != true) cerca(q)
        }
    }

    fun riproduciRisultato(r: RigaIndice) {
        val coda = ui.value.risultati.map { it.comeVoce() }
        riproduci(coda, ui.value.risultati.indexOf(r).coerceAtLeast(0))
    }

    // ── download ───────────────────────────────────────────────────────

    private fun cartellaCorrente(): String = ui.value.pila.drop(1).joinToString(" / ") { it.titolo }

    fun scarica(v: Voce) {
        try {
            Scaricamenti.accoda(ctx, v.nome, v.url, cartellaCorrente())
            messaggio("Download avviato: ${v.nome}")
        } catch (e: Exception) {
            messaggio("Download non avviato: ${e.message}")
        }
    }

    fun scaricaRiga(r: RigaIndice) {
        try {
            Scaricamenti.accoda(ctx, r.nome, r.url, r.percorso)
            messaggio("Download avviato: ${r.nome}")
        } catch (e: Exception) {
            messaggio("Download non avviato: ${e.message}")
        }
    }

    fun preparaScaricaTutto() {
        val liv = ui.value.pila.lastOrNull() ?: return
        viewModelScope.launch {
            ui.update { it.copy(caricamento = true) }
            try {
                val righe = Esploratore.raccogli(ctx, liv.url, liv.titolo, liv.cover, false) { _, _ -> }
                ui.update { it.copy(caricamento = false, confermaDownload = ConfermaDownload(liv.titolo, righe)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ZLog.e("Conteggio file per Scarica tutto fallito", e)
                ui.update { it.copy(caricamento = false, messaggio = "Errore: ${e.message}") }
            }
        }
    }

    fun annullaScaricaTutto() = ui.update { it.copy(confermaDownload = null) }

    fun confermaScaricaTutto() {
        val c = ui.value.confermaDownload ?: return
        ui.update { it.copy(confermaDownload = null) }
        val base = cartellaCorrente()
        var ok = 0
        for (r in c.righe) {
            val cartella = listOf(base, r.percorso).filter { it.isNotBlank() }.joinToString(" / ")
            try {
                Scaricamenti.accoda(ctx, r.nome, r.url, cartella)
                ok++
            } catch (_: Exception) {
            }
        }
        ZLog.i("Scarica tutto '${c.titolo}': $ok/${c.righe.size} download accodati")
        messaggio("$ok download accodati in Download/ZooPlayer")
    }
}

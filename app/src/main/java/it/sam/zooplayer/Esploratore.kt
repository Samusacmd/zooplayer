package it.sam.zooplayer

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Visita ricorsiva di tutti i sotto-elenchi: serve all'indice di ricerca e a "Scarica tutto". */
object Esploratore {
    /** Elenchi che l'ultima esplorazione non è riuscita a leggere (es. pastebin ha limitato). */
    @Volatile
    var nonLetti = 0
        private set

    suspend fun raccogli(
        ctx: Context,
        radiceUrl: String,
        radiceNome: String,
        coverRadice: String?,
        forza: Boolean,
        avanzamento: (elenchi: Int, file: Int) -> Unit,
    ): List<RigaIndice> = coroutineScope {
        val visti: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val out = Collections.synchronizedList(ArrayList<RigaIndice>())
        val sem = Semaphore(6)
        val elenchi = AtomicInteger()
        val falliti = AtomicInteger()

        suspend fun visita(url: String, nome: String, percorso: String, cover: String?) {
            if (!visti.add(url)) return
            val voci = try {
                sem.withPermit { Repo.caricaVoci(ctx, url, cover, forza) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NonJsonException) {
                out += RigaIndice(nome, url, percorso, cover) // stream: si riproduce direttamente
                return
            } catch (e: Exception) {
                ZLog.w("Elenco non letto [$percorso] $url: ${e.message}")
                falliti.incrementAndGet()
                return
            }
            voci.filter { !it.isElenco }.forEach { out += RigaIndice(it.nome, it.url, percorso, it.cover) }
            avanzamento(elenchi.incrementAndGet(), out.size)
            voci.filter { it.isElenco }.map { v ->
                val p = if (percorso.isEmpty()) v.nome else "$percorso / ${v.nome}"
                launch { visita(v.url, v.nome, p, v.cover) }
            }.joinAll()
        }

        visita(radiceUrl, radiceNome, "", coverRadice)
        nonLetti = falliti.get()
        ZLog.i("Esplorazione di '$radiceNome' completata: ${elenchi.get()} elenchi, ${out.size} file, ${falliti.get()} elenchi non letti")
        synchronized(out) { out.toList() }
    }
}

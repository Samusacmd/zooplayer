package it.sam.zooplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.text.Normalizer

/** Database dell'app (zooplayer_indice.db): preferiti ★, ascoltati ✔ e indice di ricerca. */
object Db {
    private lateinit var h: Helper
    val preferiti = MutableStateFlow<Set<String>>(emptySet())
    val ascoltati = MutableStateFlow<Set<String>>(emptySet())

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "zooplayer_indice.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE preferiti(url TEXT PRIMARY KEY, nome TEXT, cover TEXT, elenco INTEGER, ts INTEGER)")
            db.execSQL("CREATE TABLE ascoltati(url TEXT PRIMARY KEY, ts INTEGER)")
            db.execSQL("CREATE TABLE indice(radice TEXT, nome TEXT, url TEXT, percorso TEXT, cover TEXT, testo TEXT)")
            db.execSQL("CREATE INDEX idx_indice_radice ON indice(radice)")
            db.execSQL("CREATE TABLE indici(radice TEXT PRIMARY KEY, ts INTEGER, n INTEGER)")
        }

        override fun onUpgrade(db: SQLiteDatabase, vecchia: Int, nuova: Int) {}
    }

    fun init(ctx: Context) {
        h = Helper(ctx)
        preferiti.value = colonna("SELECT url FROM preferiti")
        ascoltati.value = colonna("SELECT url FROM ascoltati")
    }

    private fun colonna(sql: String): Set<String> {
        val out = HashSet<String>()
        h.readableDatabase.rawQuery(sql, null).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun togglePreferito(v: Voce) {
        val db = h.writableDatabase
        if (v.url in preferiti.value) {
            db.delete("preferiti", "url=?", arrayOf(v.url))
            preferiti.update { it - v.url }
        } else {
            val cv = ContentValues().apply {
                put("url", v.url); put("nome", v.nome); put("cover", v.cover)
                put("elenco", if (v.isElenco) 1 else 0); put("ts", System.currentTimeMillis())
            }
            db.insertWithOnConflict("preferiti", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            preferiti.update { it + v.url }
        }
    }

    fun listaPreferiti(): List<Voce> {
        val out = ArrayList<Voce>()
        h.readableDatabase.rawQuery("SELECT nome, url, elenco, cover FROM preferiti ORDER BY ts DESC", null).use { c ->
            while (c.moveToNext()) out += Voce(c.getString(0) ?: "", c.getString(1), c.getInt(2) == 1, c.getString(3))
        }
        return out
    }

    fun segnaAscoltato(url: String) {
        if (url in ascoltati.value) return
        val cv = ContentValues().apply { put("url", url); put("ts", System.currentTimeMillis()) }
        h.writableDatabase.insertWithOnConflict("ascoltati", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        ascoltati.update { it + url }
    }

    // ── indice di ricerca ──────────────────────────────────────────────

    fun normalizza(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")

    fun haIndice(radice: String): Boolean =
        h.readableDatabase.rawQuery("SELECT 1 FROM indici WHERE radice=?", arrayOf(radice)).use { it.moveToFirst() }

    fun salvaIndice(radice: String, righe: List<RigaIndice>) {
        val db = h.writableDatabase
        db.beginTransaction()
        try {
            db.delete("indice", "radice=?", arrayOf(radice))
            val st = db.compileStatement("INSERT INTO indice(radice,nome,url,percorso,cover,testo) VALUES(?,?,?,?,?,?)")
            for (r in righe) {
                st.clearBindings()
                st.bindString(1, radice)
                st.bindString(2, r.nome)
                st.bindString(3, r.url)
                st.bindString(4, r.percorso)
                if (r.cover != null) st.bindString(5, r.cover) else st.bindNull(5)
                // solo nome e percorso: gli ID casuali dei link non finiscono nell'indice
                st.bindString(6, normalizza(r.nome + " " + r.percorso))
                st.executeInsert()
            }
            val cv = ContentValues().apply {
                put("radice", radice); put("ts", System.currentTimeMillis()); put("n", righe.size)
            }
            db.insertWithOnConflict("indici", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Ricerca come nella 2.8.0: i numeri devono combaciare per intero ("03" non trova "2003"),
     * le parole devono iniziare con il termine cercato.
     */
    fun cerca(radice: String, query: String): List<RigaIndice> {
        val token = normalizza(query).split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
        if (token.isEmpty()) return emptyList()
        val where = StringBuilder("radice=?")
        val args = arrayListOf(radice)
        token.forEach { where.append(" AND testo LIKE ?"); args += "%$it%" }
        val filtri = token.map { t ->
            if (t.all { it.isDigit() }) Regex("""(?<!\p{N})$t(?!\p{N})""")
            else Regex("""(?<![\p{L}\p{N}])${Regex.escape(t)}""")
        }
        val out = ArrayList<RigaIndice>()
        h.readableDatabase.rawQuery(
            "SELECT nome, url, percorso, cover, testo FROM indice WHERE $where ORDER BY rowid LIMIT 5000",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext() && out.size < 500) {
                val testo = c.getString(4)
                if (filtri.all { it.containsMatchIn(testo) }) {
                    out += RigaIndice(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
                }
            }
        }
        return out
    }
}

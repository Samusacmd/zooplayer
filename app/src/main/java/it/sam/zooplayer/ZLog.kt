package it.sam.zooplayer

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log su file (Android/data/it.sam.zooplayer/files/zooplayer.log), condivisibile dal menu. */
object ZLog {
    private const val TAG = "ZooPlayer"
    private var file: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY)

    fun init(ctx: Context) {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = File(dir, "zooplayer.log")
        if (f.length() > 1_000_000) f.renameTo(File(dir, "zooplayer.old.log"))
        file = File(dir, "zooplayer.log")
    }

    fun file(): File? = file

    @Synchronized
    private fun scrivi(livello: String, msg: String, t: Throwable?) {
        when (livello) {
            "ERROR" -> Log.e(TAG, msg, t)
            "WARNING" -> Log.w(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        try {
            val extra = if (t != null) "\n" + t.stackTraceToString() else ""
            file?.appendText("${fmt.format(Date())} [$livello] $msg$extra\n")
        } catch (_: Exception) {
        }
    }

    fun i(msg: String) = scrivi("INFO", msg, null)
    fun w(msg: String) = scrivi("WARNING", msg, null)
    fun e(msg: String, t: Throwable? = null) = scrivi("ERROR", msg, t)
}

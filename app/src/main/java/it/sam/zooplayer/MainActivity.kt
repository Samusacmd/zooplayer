package it.sam.zooplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var vm: MainViewModel

    private val apriFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            vm.apriRadice(uri.toString())
        }
    }
    private val permesso = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[MainViewModel::class.java]
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permesso.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            ZooTheme {
                App(
                    vm = vm,
                    onApriFile = { apriFile.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onCondividiLog = ::condividiLog,
                    puoScaricare = ::verificaPermessoScrittura,
                    onEsci = { finish() },
                )
            }
        }
    }

    /** Su Android 9 e precedenti il DownloadManager verso Download/ richiede il permesso di scrittura. */
    private fun verificaPermessoScrittura(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return true
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return true
        permesso.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        Toast.makeText(this, "Concedi il permesso e riprova", Toast.LENGTH_LONG).show()
        return false
    }

    private fun condividiLog() {
        val f = ZLog.file()
        if (f == null || !f.exists()) {
            Toast.makeText(this, "Log vuoto", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val i = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Condividi log"))
        } catch (e: Exception) {
            ZLog.e("Condivisione log fallita", e)
            Toast.makeText(this, "Log in: ${f.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    /** Uscita dall'app col tasto Indietro dalla home: il player si chiude. */
    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations && Riproduttore.stato.value.corrente != null) {
            ZLog.i("App chiusa: fermo la riproduzione")
            Riproduttore.ferma()
        }
        super.onDestroy()
    }

    /** Tasti multimediali del telecomando (Fire TV / Android TV) e delle cuffie. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (Riproduttore.stato.value.corrente == null) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                Riproduttore.playPausa()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Riproduttore.salta(10_000)
            KeyEvent.KEYCODE_MEDIA_REWIND -> Riproduttore.salta(-10_000)
            KeyEvent.KEYCODE_MEDIA_NEXT -> Riproduttore.successivo()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Riproduttore.precedente()
            KeyEvent.KEYCODE_MEDIA_STOP -> Riproduttore.ferma()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}

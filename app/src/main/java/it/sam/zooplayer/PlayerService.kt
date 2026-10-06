package it.sam.zooplayer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Servizio in primo piano: la riproduzione continua a schermo spento o con l'app in background. */
class PlayerService : Service() {
    companion object {
        const val CANALE = "riproduzione"
        private const val ID = 105
        private const val A_TOGGLE = "it.sam.zooplayer.TOGGLE"
        private const val A_INDIETRO = "it.sam.zooplayer.INDIETRO"
        private const val A_AVANTI = "it.sam.zooplayer.AVANTI"
        private const val A_STOP = "it.sam.zooplayer.STOP"
        private const val A_AGGIORNA = "it.sam.zooplayer.AGGIORNA"

        /** Ridisegna la notifica (es. copertina arrivata), solo se il servizio è attivo. */
        fun aggiornaNotifica(ctx: Context) {
            if (Riproduttore.stato.value.corrente == null) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.activeNotifications.none { it.id == ID }) return
            try {
                ctx.startService(Intent(ctx, PlayerService::class.java).setAction(A_AGGIORNA))
            } catch (_: Exception) {
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val tipo = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, ID, notifica(Riproduttore.stato.value), tipo)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zooplayer:riproduzione")
            .apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "zooplayer:wifi")
            .apply { setReferenceCounted(false); acquire() }
        scope.launch {
            Riproduttore.stato
                .map { listOf(it.corrente?.nome, it.inRiproduzione, it.errore, it.coverIncorporata) }
                .distinctUntilChanged()
                .collect {
                    if (Riproduttore.stato.value.corrente != null) {
                        getSystemService(NotificationManager::class.java)
                            .notify(ID, notifica(Riproduttore.stato.value))
                    }
                }
        }
        ZLog.i("Servizio di riproduzione avviato")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_TOGGLE -> Riproduttore.playPausa()
            A_INDIETRO -> Riproduttore.salta(-10_000)
            A_AVANTI -> Riproduttore.salta(10_000)
            A_AGGIORNA -> getSystemService(NotificationManager::class.java)
                .notify(ID, notifica(Riproduttore.stato.value))
            A_STOP -> {
                ZLog.i("Stop dalla notifica")
                Riproduttore.ferma()
            }
        }
        return START_NOT_STICKY
    }

    private fun notifica(s: StatoPlayer): Notification {
        val flag = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val apri = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flag,
        )
        fun azione(a: String, req: Int) =
            PendingIntent.getService(this, req, Intent(this, PlayerService::class.java).setAction(a), flag)

        return NotificationCompat.Builder(this, CANALE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(s.corrente?.nome ?: "ZooPlayer")
            .setContentText(
                when {
                    s.errore != null -> s.errore
                    s.inRiproduzione -> "In riproduzione"
                    else -> "In pausa"
                }
            )
            .setContentIntent(apri)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLargeIcon(Riproduttore.copertinaSessione)
            .addAction(R.drawable.ic_riavvolgi, "−10 s", azione(A_INDIETRO, 1))
            .addAction(
                if (s.inRiproduzione) R.drawable.ic_pausa else R.drawable.ic_play,
                if (s.inRiproduzione) "Pausa" else "Play",
                azione(A_TOGGLE, 2),
            )
            .addAction(R.drawable.ic_avanza, "+10 s", azione(A_AVANTI, 3))
            .addAction(R.drawable.ic_stop, "Stop", azione(A_STOP, 4))
            // notifica "multimediale": comandi su schermata di blocco, collegata alla sessione
            .setStyle(
                MediaStyle()
                    .setMediaSession(Riproduttore.sessione.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            // Android 14+: se la notifica viene scartata con uno swipe, si ferma anche il player
            .setDeleteIntent(azione(A_STOP, 5))
            .build()
    }

    /** L'app è stata chiusa dalle app recenti: il player si chiude con lei. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        ZLog.i("App chiusa dalle recenti: fermo la riproduzione")
        Riproduttore.ferma()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        try {
            wakeLock?.release()
            wifiLock?.release()
        } catch (_: Exception) {
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        ZLog.i("Servizio di riproduzione fermato")
        super.onDestroy()
    }
}

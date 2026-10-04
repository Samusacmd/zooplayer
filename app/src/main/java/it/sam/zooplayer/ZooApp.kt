package it.sam.zooplayer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ZooApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ZLog.init(this)
        ZLog.i(
            "Avvio ZooPlayer ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) " +
                "- Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) - ${Build.MANUFACTURER} ${Build.MODEL}"
        )
        val precedente = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            ZLog.e("CRASH nel thread ${th.name}", ex)
            precedente?.uncaughtException(th, ex)
        }
        Rete.init(this)
        Db.init(this)
        Riproduttore.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(PlayerService.CANALE, "Riproduzione", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}

package app.aaps.wear.engine.medtrum

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.aaps.wear.engine.R

/**
 * Handover test: keeps the watch app a foreground service of type connectedDevice while it reads the
 * Medtrum patch every few minutes, as Android expects of an app that talks to a device in the background.
 * A Samsung watch still freezes the app when the screen dims unless its battery use is unrestricted. The
 * reads themselves run in [MedtrumWatchLink].
 */
class MedtrumWatchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.medtrum_watch_service_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MedtrumWatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(getString(R.string.medtrum_watch_title))
            .setContentText(getString(R.string.medtrum_watch_auto_running))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_STICKY
    }

    companion object {

        private const val CHANNEL_ID = "AndroidAPS-MedtrumWatchService"

        // Next to the Medtrum test notification (7970), clear of the others (see MedtrumWatchLink).
        private const val NOTIFICATION_ID = 7971

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MedtrumWatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MedtrumWatchService::class.java))
        }
    }
}

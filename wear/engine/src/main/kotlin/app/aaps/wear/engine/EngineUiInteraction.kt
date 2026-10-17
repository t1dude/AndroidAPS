package app.aaps.wear.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.wear.engine.medtrum.MedtrumWatchActivity
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.reflect.KClass

/**
 * The engine's screens and alarms on the watch. The phone's alarm screen does not exist here: an alarm is
 * a notification that vibrates and opens the engine's screen.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class EngineUiInteraction(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) : UiInteraction {

    override val mainActivity: KClass<*> = MedtrumWatchActivity::class
    override val errorHelperActivity: KClass<*> = MedtrumWatchActivity::class

    override fun runAlarm(status: String, title: String, sound: AlarmSound?) {
        aapsLogger.warn(LTag.CORE, "Engine alarm: $title - $status")
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, mainActivity.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(status)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 800, 400, 800, 400, 800))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun stopAlarm(reason: String) {
        aapsLogger.debug(LTag.CORE, "Engine alarm stopped: $reason")
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    companion object {

        private const val CHANNEL_ID = "AndroidAPS-EngineAlarm"
        private const val CHANNEL_NAME = "AAPS alarms on the watch"

        // Clear of the companion's notification ids (1, 2, 556677, 7700..7971)
        private const val NOTIFICATION_ID = 7980
    }
}

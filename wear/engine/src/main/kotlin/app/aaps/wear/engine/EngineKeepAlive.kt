package app.aaps.wear.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.implementation.receivers.KeepAliveCheck
import app.aaps.wear.engine.medtrum.MedtrumWatchLink
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The keep alive on the watch: the phone's [KeepAliveCheck] (the profile on the pump, a status read when the
 * last one is old, the "pump unreachable" and missed reading alarms, the zero temp basal while the pump is
 * disconnected), run by the CGM instead of WorkManager. WorkManager is held back while the watch is idle: in
 * the watch tests it ran 13 to 29 minutes late, while the G7 readings came every 5 minutes.
 *
 * - At the start of the engine, so the pump gets the running profile.
 * - After every CGM reading.
 * - From an alarm when no reading came for [NO_READING_MS], and then every [REPEAT_MS] until readings come
 *   back. The loop does not run without readings, but the pump must still be read and the missed reading
 *   alarm must still go off. While readings come, the alarm is moved on with each one and never goes off.
 */
@SingleIn(AppScope::class)
@Inject
class EngineKeepAlive(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val keepAliveCheck: KeepAliveCheck,
    private val link: MedtrumWatchLink
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    @Volatile private var lastReadingAt = 0L

    fun start() {
        arm(System.currentTimeMillis() + NO_READING_MS)
        run(wakeLock = null)
    }

    /** A new CGM reading was stored. The loop runs on it too. */
    fun onReading() {
        lastReadingAt = System.currentTimeMillis()
        arm(lastReadingAt + NO_READING_MS)
        run(wakeLock = null)
    }

    /**
     * The alarm went off: no CGM reading for a while (see [EngineKeepAliveReceiver]). The wake lock is taken
     * before this returns: the alarm keeps the watch awake only until then.
     */
    fun onAlarm() {
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidAPS:EngineKeepAlive")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MS)
            }
        arm(System.currentTimeMillis() + REPEAT_MS)
        val since = if (lastReadingAt == 0L) "since the engine started" else "since ${SimpleDateFormat("HH:mm", Locale.US).format(Date(lastReadingAt))}"
        link.note("No CGM reading $since: keep alive from the alarm")
        run(wakeLock)
    }

    fun stop() = cancel(context)

    private fun run(wakeLock: PowerManager.WakeLock?) {
        scope.launch {
            try {
                // One at a time: a reading and the alarm can come close together
                mutex.withLock { keepAliveCheck.run() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Engine keep alive failed", e)
            } finally {
                wakeLock?.release()
            }
        }
    }

    private fun arm(at: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
        } catch (e: SecurityException) {
            // No exact alarms for this app: an inexact one may come several minutes late
            aapsLogger.warn(LTag.CORE, "Engine keep alive: exact alarm not allowed (${e.message}), using an inexact one")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
        }
    }

    companion object {

        /** A G7 reading is due every 5 minutes: two minutes more before the alarm takes over. */
        const val NO_READING_MS = 7 * 60 * 1000L
        const val REPEAT_MS = 5 * 60 * 1000L
        private const val WAKE_LOCK_MS = 2 * 60 * 1000L

        private fun intent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0, Intent(context, EngineKeepAliveReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        /** Cancels the alarm, also when the engine was never built (companion mode). */
        fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(intent(context))
    }
}

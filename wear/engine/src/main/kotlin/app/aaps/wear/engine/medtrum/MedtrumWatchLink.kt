package app.aaps.wear.engine.medtrum

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.di.MetroMemberInjector
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.ble.MedtrumBleCallback
import app.aaps.pump.medtrum.ble.MedtrumBleTransport
import app.aaps.pump.medtrum.comm.packets.AuthorizePacket
import app.aaps.pump.medtrum.comm.packets.GetDeviceTypePacket
import app.aaps.pump.medtrum.comm.packets.GetTimePacket
import app.aaps.pump.medtrum.comm.packets.MedtrumPacket
import app.aaps.pump.medtrum.comm.packets.NotificationPacket
import app.aaps.pump.medtrum.comm.packets.SubscribePacket
import app.aaps.pump.medtrum.comm.packets.SynchronizePacket
import app.aaps.pump.medtrum.handover.HandoverExtras
import app.aaps.pump.medtrum.handover.HandoverTemporaryBasal
import app.aaps.pump.medtrum.handover.MedtrumSession
import app.aaps.pump.medtrum.handover.MedtrumSession.Companion.writeTo
import app.aaps.pump.medtrum.keys.MedtrumBooleanNonKey
import app.aaps.wear.engine.CompanionLink
import app.aaps.wear.engine.R
import app.aaps.wear.engine.WatchEngine
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Handover test, watch side: the phone hands its running Medtrum patch session to the watch, the watch
 * reads the patch status with it, and hands it back.
 *
 * A status read is the phone's connect sequence (authorize, device type, time, synchronize, subscribe) and
 * nothing more: the watch sends no bolus or basal, does not set the patch clock and does not read the patch
 * history. The phone reads the history it missed when it takes the session back, so nothing is lost or
 * doubled.
 *
 * Only one device may talk to the patch. The phone stops before it sends the session; the watch stops
 * before it sends it back.
 *
 * Reads every 5 minutes are woken by an alarm. A plain timer does not run while the watch sleeps with the
 * screen off, so it can take far longer than 5 minutes.
 *
 * The test log is kept in a file, because the watch's own log is too short to hold a test, and goes to the
 * phone's exports folder at hand back or on request.
 */
@SingleIn(AppScope::class)
@Inject
class MedtrumWatchLink(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val companion: CompanionLink,
    private val config: Config,
    private val preferences: Preferences,
    private val medtrumPump: MedtrumPump,
    private val transport: MedtrumBleTransport,
    private val injector: MetroMemberInjector
) : MedtrumBleCallback {

    data class Status(
        /** The patch serial, 0 when the watch has no session. */
        val serial: Long = 0L,
        val reading: Boolean = false,
        val autoRead: Boolean = false,
        val lastReadAt: Long? = null,
        val lastReadOk: Boolean? = null,
        /** What the last good read found, ready to show. */
        val result: String? = null,
        /** The newest lines of the test log, for the screen. */
        val log: List<String> = emptyList(),
        /** Daily doses and temporary basal that came with the session, the basal kept up to date by the reads. */
        val extras: HandoverExtras = HandoverExtras()
    ) {

        val hasSession: Boolean get() = serial != 0L
    }

    private class Pending(val packet: MedtrumPacket, val done: CompletableDeferred<Boolean>) {

        var retries = 0
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private var started = false

    /** Survives a restart of the app, so reads every 5 minutes go on after one. */
    private val state by lazy { context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE) }
    private val logFile by lazy { File(context.filesDir, LOG_FILE) }
    private val logLock = Any()

    @Volatile private var connected: CompletableDeferred<Boolean>? = null
    @Volatile private var pending: Pending? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The Bluetooth permissions the driver uses exist from Android 12. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** A session the phone handed over (see `WatchEngine.onMessageFromPhone`). */
    fun receiveSession(text: String) {
        scope.launch { takeIn(text) }
    }

    fun start() {
        if (started) return
        started = true
        medtrumPump.loadVarsFromSP()
        _status.update { it.copy(serial = currentSerial(), log = tailLines(), extras = savedExtras()) }
        // The app was restarted while reading every 5 minutes: go on
        if (state.getBoolean(KEY_AUTO_READ, false) && _status.value.hasSession) {
            log("App restarted, reading every 5 minutes goes on")
            setAutoRead(true)
        }
    }

    /** One status read, as the "Read status" button does. */
    fun readNow() {
        val wakeLock = acquireWakeLock()
        scope.launch {
            try {
                readStatus("button")
            } finally {
                wakeLock.release()
            }
        }
    }

    /** Reads every [AUTO_READ_MS] while on, to see whether the link holds over time and with the screen off. */
    fun setAutoRead(on: Boolean) {
        state.edit().putBoolean(KEY_AUTO_READ, on).apply()
        _status.update { it.copy(autoRead = on) }
        if (on) {
            // Android may refuse a foreground service started from the background (after a restart). The
            // alarms still run without it.
            runCatching { MedtrumWatchService.start(context) }.onFailure { log("Foreground service not started: ${it.message}") }
            log("Reading every 5 minutes: on")
            onAlarm(planned = System.currentTimeMillis())
        } else {
            cancelAlarm()
            MedtrumWatchService.stop(context)
            log("Reading every 5 minutes: off")
        }
    }

    /**
     * The alarm for the next read went off (see [MedtrumWatchAlarmReceiver]). The wake lock is taken before
     * this returns: the alarm keeps the watch awake only until then.
     */
    fun onAlarm(planned: Long = state.getLong(KEY_NEXT_AT, 0L)) {
        if (!state.getBoolean(KEY_AUTO_READ, false)) return
        val wakeLock = acquireWakeLock()
        val now = System.currentTimeMillis()
        if (planned > 0 && now - planned > LATE_MS) log("Alarm ${(now - planned) / 1000} s late")
        scheduleAlarm(now + AUTO_READ_MS)
        scope.launch {
            try {
                readStatus("auto")
            } finally {
                wakeLock.release()
            }
        }
    }

    /** Sends the test log to the phone's exports folder, without handing the session back. */
    fun sendLog() {
        scope.launch {
            log("Log sent to the phone")
            companion.sendToPhone(EventData.MedtrumWatchLog(logForPhone()))
        }
    }

    /** Stops talking to the patch and sends the session back to the phone, with the test log. */
    fun handBack() {
        scope.launch {
            if (_status.value.autoRead) setAutoRead(false)
            mutex.withLock {
                if (!_status.value.hasSession) return@withLock
                // Stop first: the session must not move on after it is sent
                preferences.put(MedtrumBooleanNonKey.SessionMovedAway, true)
                // The daily doses go back as they came; the temporary basal and basal status as the last read saw them
                val session = MedtrumSession.read(preferences, System.currentTimeMillis()).withExtras(_status.value.extras)
                _status.update { it.copy(serial = 0L, result = null, lastReadOk = null) }
                log("Session of patch ${session.serial.toString(16)} handed back to the phone")
                companion.sendToPhone(EventData.MedtrumSessionToPhone(session.toText(), logForPhone()))
                // The engine stops with the session
                WatchEngine.afterHandback()
            }
        }
    }

    private suspend fun takeIn(text: String) = mutex.withLock {
        val session = MedtrumSession.parse(text)
        if (session == null || session.serial == 0L) {
            log("The phone sent a session that cannot be read")
            return@withLock
        }
        session.writeTo(preferences)
        preferences.put(MedtrumBooleanNonKey.SessionMovedAway, false)
        medtrumPump.loadVarsFromSP()
        val extras = session.extras
        saveExtras(extras)
        _status.update { it.copy(serial = currentSerial(), lastReadAt = null, lastReadOk = null, result = null, extras = extras) }
        log("----- Took in the session of patch ${session.serial.toString(16)}, exported ${time(session.exportedAt)}")
        log("Came along: ${extras.tdds.size} daily doses ${extras.tdds.map { "%.1f".format(Locale.US, it.totalAmount) }}, temporary basal ${describe(extras.temporaryBasal)}")
        log(
            "Watch ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), AAPS ${config.BUILD_VERSION}, " +
                "battery unrestricted: ${isBatteryUnrestricted()}"
        )
        notifySessionReceived()
    }

    private suspend fun readStatus(reason: String): Boolean = mutex.withLock {
        val serial = _status.value.serial
        if (serial == 0L) return@withLock false
        if (!isSupported) {
            log("This watch runs Android 11 or older; the Medtrum link needs Android 12")
            return@withLock false
        }
        _status.update { it.copy(reading = true) }
        val ok = try {
            read(serial, reason)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPCOMM, "Medtrum watch test: status read failed", e)
            log("Read failed: ${e.javaClass.simpleName} ${e.message}")
            false
        } finally {
            pending = null
            connected = null
            transport.disconnect("Medtrum watch test")
            transport.setMedtrumCallback(null)
        }
        _status.update {
            it.copy(reading = false, lastReadAt = System.currentTimeMillis(), lastReadOk = ok, result = if (ok) describe() else it.result)
        }
        ok
    }

    private suspend fun read(serial: Long, reason: String): Boolean {
        val started = System.currentTimeMillis()
        log("Read ($reason): connecting to patch ${serial.toString(16)}")
        transport.setMedtrumCallback(this)
        if (!connect(serial, started)) {
            // A single failed connect happens now and then (seen once in 8 reads). Once more before giving up.
            transport.disconnect("Medtrum watch test: retry")
            log("Trying once more in ${CONNECT_RETRY_DELAY_MS / 1000} s")
            delay(CONNECT_RETRY_DELAY_MS)
            if (!connect(serial, System.currentTimeMillis())) return false
        }
        val steps = listOf(
            "authorize" to AuthorizePacket(injector),
            "device type" to GetDeviceTypePacket(injector),
            "time" to GetTimePacket(injector),
            "synchronize" to SynchronizePacket(injector),
            "subscribe" to SubscribePacket(injector)
        )
        for ((name, packet) in steps) {
            val stepStarted = System.currentTimeMillis()
            if (!exchange(packet)) {
                log("Step $name failed after ${System.currentTimeMillis() - stepStarted} ms")
                return false
            }
            log("Step $name ok in ${System.currentTimeMillis() - stepStarted} ms")
        }
        followBasal()
        log(
            "Read ok in ${seconds(started)} s: ${medtrumPump.pumpState}, reservoir ${medtrumPump.reservoir} U, battery ${medtrumPump.batteryVoltage_B} V, " +
                "basal ${medtrumPump.lastBasalRate} U/h ${medtrumPump.lastBasalType}, patch clock off by ${clockOffSeconds()} s, " +
                "history ${medtrumPump.syncedSequenceNumber}/${medtrumPump.currentSequenceNumber}"
        )
        return true
    }

    /**
     * Keeps the temporary basal that came along up to date with what the patch reports. The patch does
     * not report a duration, so a temporary basal it runs is only kept when it is the one that came along.
     */
    private fun followBasal() {
        val status = medtrumPump.basalStatus(readAt = System.currentTimeMillis()) ?: return
        val extras = _status.value.extras
        val known = extras.temporaryBasal
        val temporaryBasal = when {
            status.type.isTempBasal() && known != null && abs(known.timestamp - status.startTime) < SAME_BASAL_MS -> known

            status.type.isTempBasal()                                                                              -> {
                log("The patch runs a temporary basal that did not come along: ${status.rate} U/h ${status.type} since ${time(status.startTime)}, duration not known")
                null
            }

            else                                                                                                   -> {
                if (known != null) log("The temporary basal that came along has ended: the patch runs ${status.type} since ${time(status.startTime)}")
                null
            }
        }
        val updated = extras.copy(temporaryBasal = temporaryBasal, basalStatus = status)
        saveExtras(updated)
        _status.update { it.copy(extras = updated) }
    }

    private fun describe(tbr: HandoverTemporaryBasal?): String =
        if (tbr == null) "none"
        else "${tbr.rate}${if (tbr.isAbsolute) " U/h" else " %"} for ${tbr.duration / 60_000} min from ${time(tbr.timestamp)} (ends ${time(tbr.end)})"

    private fun saveExtras(extras: HandoverExtras) {
        state.edit().putString(KEY_EXTRAS, MedtrumSession(0L, extras.toValues()).toText()).apply()
    }

    private fun savedExtras(): HandoverExtras =
        state.getString(KEY_EXTRAS, null)?.let { MedtrumSession.parse(it)?.extras } ?: HandoverExtras()

    private suspend fun connect(serial: Long, started: Long): Boolean {
        val link = CompletableDeferred<Boolean>()
        connected = link
        if (!transport.connect("Medtrum watch test", serial)) {
            log("Connect refused: Bluetooth off or not allowed")
            return false
        }
        if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { link.await() } != true) {
            log("No connection to the patch after ${seconds(started)} s")
            return false
        }
        log("Connected after ${seconds(started)} s")
        return true
    }

    private suspend fun exchange(packet: MedtrumPacket): Boolean {
        val done = CompletableDeferred<Boolean>()
        pending = Pending(packet, done)
        transport.sendMessage(packet.getRequest())
        val ok = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { done.await() } ?: false
        pending = null
        return ok
    }

    private fun describe(): String =
        context.getString(
            R.string.medtrum_watch_result,
            context.getString(medtrumPump.pumpState.label),
            medtrumPump.reservoir,
            medtrumPump.batteryVoltage_B,
            medtrumPump.lastBasalRate,
            medtrumPump.lastBasalType.name,
            clockOffSeconds()
        )

    private fun clockOffSeconds(): Long = ((medtrumPump.lastTimeReceivedFromPump - System.currentTimeMillis()) / 1000.0).roundToLong()

    private fun seconds(since: Long): Long = (System.currentTimeMillis() - since) / 1000

    private fun currentSerial(): Long =
        if (preferences.get(MedtrumBooleanNonKey.SessionMovedAway)) 0L else medtrumPump.pumpSN

    private fun isBatteryUnrestricted(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    // MedtrumBleCallback: called on the transport's threads

    override fun onConnected() {
        connected?.complete(true)
    }

    override fun onDisconnected() {
        if (pending != null) log("Patch disconnected during a step")
        connected?.complete(false)
        pending?.done?.complete(false)
    }

    override fun onNotification(data: ByteArray) {
        NotificationPacket(injector).handleNotification(data)
    }

    override fun onIndication(data: ByteArray) {
        val current = pending ?: return
        if (current.packet.handleResponse(data)) current.done.complete(true)
        else if (current.packet.failed) current.done.complete(false)
    }

    override fun onSendMessageError(reason: String, isRetryAble: Boolean) {
        val current = pending ?: return
        log("Send failed: $reason")
        if (isRetryAble && current.retries < 3) {
            current.retries++
            transport.sendMessage(current.packet.getRequest())
        } else current.done.complete(false)
    }

    // Alarm and wake lock

    private fun alarmIntent(): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, MedtrumWatchAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun scheduleAlarm(at: Long) {
        state.edit().putLong(KEY_NEXT_AT, at).apply()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
        } catch (e: SecurityException) {
            // No exact alarms for this app: an inexact one may come several minutes late
            log("Exact alarm not allowed (${e.message}), using an inexact one")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
        }
    }

    private fun cancelAlarm() {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent())
        state.edit().remove(KEY_NEXT_AT).apply()
    }

    /** Held for one read. The timeout is a guard only: a read gives up long before. */
    private fun acquireWakeLock(): PowerManager.WakeLock =
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidAPS:MedtrumWatchTest")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MS)
            }

    // Test log

    /** A line from the rest of the engine (start, settings, loop runs) for the same test log. */
    fun note(text: String) = log(text)

    private fun log(text: String) {
        aapsLogger.info(LTag.PUMPCOMM, "Medtrum watch test: $text")
        val line = "${dateTime(System.currentTimeMillis())} $text"
        synchronized(logLock) {
            runCatching {
                logFile.appendText(line + "\n")
                if (logFile.length() > LOG_FILE_MAX_BYTES) logFile.writeText(logFile.readText().takeLast(LOG_FILE_KEEP_CHARS))
            }.onFailure { aapsLogger.error(LTag.PUMPCOMM, "Medtrum watch test: writing the log failed", it) }
        }
        _status.update { it.copy(log = (it.log + line).takeLast(SCREEN_LINES)) }
    }

    private fun tailLines(): List<String> = synchronized(logLock) {
        runCatching { if (logFile.exists()) logFile.readLines().takeLast(SCREEN_LINES) else emptyList() }.getOrDefault(emptyList())
    }

    /** The newest part of the log: a message to the phone may not be much larger. */
    private fun logForPhone(): String = synchronized(logLock) {
        runCatching { if (logFile.exists()) logFile.readText().takeLast(PHONE_LOG_CHARS) else "" }.getOrDefault("")
    }

    private fun time(at: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))

    private fun dateTime(at: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))

    private fun notifySessionReceived() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.medtrum_watch_title), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MedtrumWatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(context.getString(R.string.medtrum_watch_title))
            .setContentText(context.getString(R.string.medtrum_watch_received))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {

        private const val AUTO_READ_MS = 5 * 60 * 1000L
        private const val LATE_MS = 30 * 1000L
        private const val CONNECT_TIMEOUT_MS = 60 * 1000L
        private const val CONNECT_RETRY_DELAY_MS = 10 * 1000L
        private const val COMMAND_TIMEOUT_MS = 30 * 1000L
        private const val WAKE_LOCK_MS = 4 * 60 * 1000L
        private const val SCREEN_LINES = 40

        // A message to the phone is limited to about 100 KB
        private const val PHONE_LOG_CHARS = 80_000
        private const val LOG_FILE_MAX_BYTES = 400_000L
        private const val LOG_FILE_KEEP_CHARS = 200_000
        private const val LOG_FILE = "medtrum_watch_test.log"
        private const val STATE_FILE = "medtrum_watch_test"
        private const val KEY_AUTO_READ = "auto_read"
        private const val KEY_NEXT_AT = "next_read_at"
        private const val KEY_EXTRAS = "extras"
        private const val SAME_BASAL_MS = 60 * 1000L
        private const val CHANNEL_ID = "AndroidAPS-MedtrumWatchTest"

        // Clear of the ids DataLayerListenerServiceWear (1, 2, 556677), CgmAlarmWear (7700..7955) and
        // the G7 notifications (7960, 7961).
        private const val NOTIFICATION_ID = 7970
    }
}

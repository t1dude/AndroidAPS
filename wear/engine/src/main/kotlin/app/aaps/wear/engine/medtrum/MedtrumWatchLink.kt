package app.aaps.wear.engine.medtrum

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.handover.HandoverSnapshot
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.medtrum.MedtrumPlugin
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.handover.HandoverExtras
import app.aaps.pump.medtrum.handover.HandoverTemporaryBasal
import app.aaps.pump.medtrum.handover.MedtrumSession
import app.aaps.pump.medtrum.handover.MedtrumSession.Companion.writeTo
import app.aaps.pump.medtrum.keys.MedtrumBooleanNonKey
import app.aaps.wear.engine.CompanionLink
import app.aaps.wear.engine.EngineTestLog
import app.aaps.wear.engine.R
import app.aaps.wear.engine.WatchEngine
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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
import kotlin.math.roundToLong

/**
 * Handover, watch side: the Medtrum patch session the phone handed over. The engine's own Medtrum driver
 * talks to the patch with it, the same driver the phone runs; this class takes the session in, hands it back,
 * and keeps the test log.
 *
 * Only one device may talk to the patch. The phone stops before it sends the session (its transport refuses
 * to connect from then on); the watch stops before it sends it back, the same way.
 *
 * The test log is kept in a file, because the watch's own log is too short to hold a test, and goes to the
 * phone's handover folder at hand back, at the end of a test run without a patch, or on request.
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
    private val medtrumPlugin: MedtrumPlugin,
    private val commandQueue: CommandQueue,
    private val pumpSync: PumpSync,
    private val profileFunction: ProfileFunction
) {

    data class Status(
        /** The patch serial, 0 when the watch has no session. */
        val serial: Long = 0L,
        /** A read or a command of the buttons is running. */
        val busy: Boolean = false,
        val lastReadAt: Long? = null,
        val lastReadOk: Boolean? = null,
        /** What the last good read found, ready to show. */
        val result: String? = null,
        /** The newest lines of the test log, for the screen. */
        val log: List<String> = emptyList(),
        /** Daily doses and temporary basal that came with the session. */
        val extras: HandoverExtras = HandoverExtras()
    ) {

        val hasSession: Boolean get() = serial != 0L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private var started = false

    /** What came along with the session survives a restart of the app. */
    private val state by lazy { context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE) }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** True while the watch holds a patch session, so the engine's pump is the Medtrum driver. */
    val holdsSession: Boolean get() = currentSerial() != 0L

    fun start() {
        if (started) return
        started = true
        medtrumPump.loadVarsFromSP()
        _status.update { it.copy(serial = currentSerial(), log = tailLines(), extras = savedExtras()) }
        logReads()
    }

    /**
     * Every read of the patch in the test log, with what asked for it: the keep alive, the loop, a button. The
     * driver reads by itself (the keep alive reads when the last one is 15 minutes old), so the button's reads
     * were all the log had. A connection is a read when the patch was last heard of more than a minute before;
     * the line is written a few seconds later, when the read is done.
     */
    private fun logReads() {
        scope.launch {
            var lastSeen = medtrumPump.lastConnection
            medtrumPump.lastConnectionFlow.collect { at ->
                val newRead = at - lastSeen > NEW_READ_GAP_MS && at > System.currentTimeMillis() - NEW_READ_GAP_MS
                lastSeen = at
                if (!newRead || !holdsSession) return@collect
                val reason = commandQueue.performing()?.log() ?: "the patch"
                scope.launch {
                    delay(READ_DONE_WAIT_MS)
                    log("Read ok ($reason): ${describeForLog()}")
                }
            }
        }
    }

    /**
     * Takes in a session the phone handed over. Called before the engine's plugins start (see `startEngine`),
     * so the Medtrum driver starts with its patch.
     */
    suspend fun takeIn(text: String) = mutex.withLock {
        val session = MedtrumSession.parse(text)
        if (session == null || session.serial == 0L) {
            log("The phone sent a session that cannot be read")
            return@withLock
        }
        // Before the serial is written: the driver resets a patch whose serial changes, unless it is this one
        medtrumPump.takenInSerial = session.serial
        session.writeTo(preferences)
        preferences.put(MedtrumBooleanNonKey.SessionMovedAway, false)
        medtrumPump.loadVarsFromSP()
        medtrumPump.loadUserSettingsFromSP()
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

    /**
     * One status read through the engine's Medtrum driver, as the "Read status" button does. When a read runs
     * already (the driver's own at the start, the keep alive's), the button waits for that one: the queue
     * refuses a second, and that is no failure.
     */
    fun readNow() {
        if (!_status.value.hasSession || _status.value.busy) return
        _status.update { it.copy(busy = true) }
        scope.launch {
            val ok = if (commandQueue.statusInQueue()) {
                log("Read (button): a read runs already, the button waits for it")
                withTimeoutOrNull(READ_RUNNING_WAIT_MS) { while (commandQueue.statusInQueue()) delay(POLL_MS) }
                val ok = medtrumPump.lastConnection > System.currentTimeMillis() - NEW_READ_GAP_MS && medtrumPlugin.isInitialized()
                if (!ok) log("Read failed: the read that ran did not reach the patch")
                ok
            } else {
                log("Read (button): through the driver")
                val result = commandQueue.readStatus("Watch: Read status button")
                val ok = result.success && medtrumPlugin.isInitialized()
                if (!ok) log("Read failed: ${result.comment}")
                ok
            }
            // A good read is logged with every other read (see logReads)
            _status.update { it.copy(busy = false, lastReadAt = System.currentTimeMillis(), lastReadOk = ok, result = if (ok) describe() else it.result) }
        }
    }

    /**
     * Step 3, supervised: a temp basal of 0 U/h for 30 minutes from the watch. The safe way, it gives less insulin.
     * The log then says what the patch runs and what the watch's records hold: both must show it, or the watch
     * threw the patch's record away.
     */
    fun setZeroTempBasal() = runCommand("Zero temp basal (button): 0 U/h for $ZERO_TEMP_MINUTES min") {
        val profile = profileFunction.getProfile() ?: return@runCommand null
        commandQueue.tempBasalAbsolute(0.0, ZERO_TEMP_MINUTES, enforceNew = true, profile = profile, tbrType = PumpSync.TemporaryBasalType.NORMAL)
    }

    /** Step 3: cancels the temp basal that runs, with the same check as [setZeroTempBasal]. */
    fun cancelTempBasal() = runCommand("Cancel temp basal (button)") {
        commandQueue.cancelTempBasal(enforceNew = true)
    }

    /** Runs one pump command of the buttons. [command] returns null when it could not be sent (no profile). */
    private fun runCommand(what: String, command: suspend () -> PumpEnactResult?) {
        if (!_status.value.hasSession || _status.value.busy) return
        _status.update { it.copy(busy = true) }
        scope.launch {
            try {
                log(what)
                val result = command()
                when {
                    result == null  -> log("Not sent: no profile")
                    result.success  -> log("Done${if (result.enacted) "" else " (nothing to change)"}: ${result.comment}")
                    else            -> log("Failed: ${result.comment}")
                }
                val recorded = pumpSync.expectedPumpState().temporaryBasal?.let { HandoverTemporaryBasal.of(it) }
                log("Patch runs: basal ${num(medtrumPump.lastBasalRate)} U/h ${medtrumPump.lastBasalType}. Watch's records: temporary basal ${describe(recorded)}")
                _status.update { it.copy(result = describe(), extras = it.extras.copy(temporaryBasal = recorded)) }
            } finally {
                _status.update { it.copy(busy = false) }
            }
        }
    }

    /** Sends the test log to the phone's handover folder, without handing the session back. */
    fun sendLog() {
        scope.launch { sendLogToPhone() }
    }

    /**
     * Ends a test run without a patch: the log goes to the phone first, because the engine then stops with
     * the app process (see `WatchEngine.stopTestRun`).
     */
    fun stopTestRun() {
        scope.launch {
            log("Engine test run stopped")
            sendLogToPhone()
            WatchEngine.stopTestRun()
        }
    }

    /**
     * The whole test log with a summary on top, as a file over a Wear channel: a night of it is far too big for
     * a message. Returns when it was sent, so the app is not restarted in the middle of it.
     */
    private suspend fun sendLogToPhone() {
        val file = File(context.cacheDir, LOG_FOR_PHONE_FILE)
        if (!EngineTestLog.writeForPhone(context, file)) {
            log("The log could not be written for the phone")
            return
        }
        val kilobytes = file.length() / 1024
        val sent = companion.sendFileToPhone(file, HandoverSnapshot.TEST_LOG_CHANNEL_PATH)
        file.delete()
        log(if (sent) "Log sent to the phone ($kilobytes KB)" else "The log could not be sent to the phone ($kilobytes KB)")
    }

    /**
     * Stops talking to the patch and sends the session back to the phone, with the test log. Not while the
     * driver is busy: the session must not move on after it is sent.
     */
    fun handBack() {
        scope.launch {
            mutex.withLock {
                if (!_status.value.hasSession) return@withLock
                if (commandQueue.size() > 0 || commandQueue.performing() != null) {
                    log("Hand back refused: the pump is busy. Try again when the command queue is empty.")
                    return@withLock
                }
                // Stop first: from here the driver reports no patch and the transport refuses to connect
                preferences.put(MedtrumBooleanNonKey.SessionMovedAway, true)
                medtrumPlugin.getService()?.disconnect("Handover: session handed back")
                // The daily doses go back as they came. The temporary basal is what this watch's records say runs
                // now, with its duration, and the basal is what the patch reported at the last read.
                val cameAlong = _status.value.extras
                val extras = cameAlong.copy(
                    temporaryBasal = pumpSync.expectedPumpState().temporaryBasal?.let { HandoverTemporaryBasal.of(it) },
                    basalStatus = medtrumPump.basalStatus(readAt = medtrumPump.lastConnection)
                )
                val session = MedtrumSession.read(preferences, System.currentTimeMillis()).withExtras(extras)
                _status.update { it.copy(serial = 0L, result = null, lastReadOk = null) }
                log("Session of patch ${session.serial.toString(16)} handed back to the phone, temporary basal ${describe(extras.temporaryBasal)}")
                companion.sendToPhone(EventData.MedtrumSessionToPhone(session.toText()))
                sendLogToPhone()
                // The engine stops with the session
                WatchEngine.afterHandback()
            }
        }
    }

    private fun describe(tbr: HandoverTemporaryBasal?): String =
        if (tbr == null) "none"
        else "${num(tbr.rate)}${if (tbr.isAbsolute) " U/h" else " %"} for ${tbr.duration / 60_000} min from ${time(tbr.timestamp)} (ends ${time(tbr.end)})"

    private fun saveExtras(extras: HandoverExtras) {
        state.edit().putString(KEY_EXTRAS, MedtrumSession(0L, extras.toValues()).toText()).apply()
    }

    private fun savedExtras(): HandoverExtras =
        state.getString(KEY_EXTRAS, null)?.let { MedtrumSession.parse(it)?.extras } ?: HandoverExtras()

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

    private fun describeForLog(): String =
        "${medtrumPump.pumpState}, reservoir ${num(medtrumPump.reservoir)} U, battery ${num(medtrumPump.batteryVoltage_B)} V, " +
            "basal ${num(medtrumPump.lastBasalRate)} U/h ${medtrumPump.lastBasalType}, patch clock off by ${clockOffSeconds()} s, " +
            "history ${medtrumPump.syncedSequenceNumber}/${medtrumPump.currentSequenceNumber}"

    private fun clockOffSeconds(): Long = ((medtrumPump.lastTimeReceivedFromPump - System.currentTimeMillis()) / 1000.0).roundToLong()

    private fun currentSerial(): Long =
        if (preferences.get(MedtrumBooleanNonKey.SessionMovedAway)) 0L else medtrumPump.pumpSN

    private fun isBatteryUnrestricted(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    // Test log

    /** A line from the rest of the engine (start, settings, loop runs) for the same test log. */
    fun note(text: String) = log(text)

    private fun log(text: String) {
        aapsLogger.info(LTag.PUMPCOMM, "Medtrum watch: $text")
        val line = EngineTestLog.append(context, text)
        _status.update { it.copy(log = (it.log + line).takeLast(SCREEN_LINES)) }
    }

    private fun tailLines(): List<String> = EngineTestLog.lastLines(context, SCREEN_LINES)

    /** Two decimals in the log: "0.35", not "0.35000000000000003". */
    private fun num(value: Double): String = "%.2f".format(Locale.US, value)

    private fun time(at: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))

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

        private const val SCREEN_LINES = 40

        /** A connection after this much silence from the patch is a new read. */
        private const val NEW_READ_GAP_MS = 60_000L

        /** A read takes a few seconds; the history is synced by then. */
        private const val READ_DONE_WAIT_MS = 10_000L

        /** How long the button waits for a read that runs already. A read that cannot connect gives up sooner. */
        private const val READ_RUNNING_WAIT_MS = 120_000L
        private const val POLL_MS = 500L

        /** Step 3's temp basal, as agreed in the plan. */
        private const val ZERO_TEMP_MINUTES = 30

        private const val LOG_FOR_PHONE_FILE = "handover/test_log_for_phone.txt"
        private const val STATE_FILE = "medtrum_watch_test"
        private const val KEY_EXTRAS = "extras"
        private const val CHANNEL_ID = "AndroidAPS-MedtrumWatchTest"

        // Clear of the ids DataLayerListenerServiceWear (1, 2, 556677), CgmAlarmWear (7700..7955) and
        // the G7 notifications (7960, 7961).
        private const val NOTIFICATION_ID = 7970
    }
}

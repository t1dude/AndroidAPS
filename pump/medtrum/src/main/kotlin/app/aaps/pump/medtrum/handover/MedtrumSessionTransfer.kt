package app.aaps.pump.medtrum.handover

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.RM
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.handover.HandoverSnapshot
import app.aaps.core.interfaces.handover.HandoverSnapshotWriter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.TemporaryBasalStorage
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.events.EventMobileToWearFile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Translator
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.medtrum.MedtrumPlugin
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.R
import app.aaps.pump.medtrum.handover.MedtrumSession.Companion.writeTo
import app.aaps.pump.medtrum.keys.MedtrumBooleanNonKey
import app.aaps.pump.medtrum.keys.MedtrumIntNonKey
import app.aaps.pump.medtrum.keys.MedtrumStringNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Handover test: moves a running patch session to the watch and back, over the phone-watch link. Only one
 * device may talk to the patch, so the phone that moved its session away does not connect to the patch
 * again until it takes a session back in.
 *
 * A copy of the session is written to a file in the AAPS handover folder, as the way back when the watch
 * never gets it, and the watch's hand back is saved there too. The file holds the patch serial and
 * session token: anyone with it can talk to the patch. Delete it after the test.
 *
 * [sendSnapshotWithoutPatch] starts the watch's engine without the patch, for a test of the engine alone.
 */
@SingleIn(AppScope::class)
@Inject
class MedtrumSessionTransfer(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val fileListProvider: FileListProvider,
    private val commandQueue: CommandQueue,
    private val medtrumPump: MedtrumPump,
    private val medtrumPlugin: MedtrumPlugin,
    private val loop: Loop,
    private val profileFunction: ProfileFunction,
    private val translator: Translator,
    private val rxBus: RxBus,
    private val notificationManager: NotificationManager,
    private val tddCalculator: TddCalculator,
    private val pumpSync: PumpSync,
    private val temporaryBasalStorage: TemporaryBasalStorage,
    private val persistenceLayer: PersistenceLayer,
    private val snapshotWriter: HandoverSnapshotWriter
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var started = false

    /** What [moveToWatch], [sendSnapshotWithoutPatch] and [takeIn] report. [modeToRestore] is set when the loop can be turned back on. */
    data class Result(val message: String, val modeToRestore: RM.Mode? = null)

    private val _movedAway = MutableStateFlow(preferences.get(MedtrumBooleanNonKey.SessionMovedAway))

    /** True while another device has this phone's patch session. */
    val movedAway: StateFlow<Boolean> = _movedAway.asStateFlow()

    val isMovedAway: Boolean get() = _movedAway.value

    /** Listens for the watch handing a session back. Called when the Medtrum plugin starts. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            rxBus.toFlow(EventData.MedtrumSessionToPhone::class).collect { receiveFromWatch(it.session) }
        }
        scope.launch { rxBus.toFlow(EventData.MedtrumWatchLog::class).collect { saveWatchLog(it.log) } }
    }

    /**
     * Sends the session to the watch and stops this phone talking to the patch. A running loop is turned
     * off (Disable loop), and the mode it had is kept, to offer it again when a session is taken back in.
     * The session is also written to a file, as a way back: if the watch never gets it, this phone can take
     * it in again.
     */
    suspend fun moveToWatch(): Result = withContext(Dispatchers.IO) {
        if (medtrumPump.pumpSN == 0L) return@withContext Result(rh.gs(R.string.handover_test_no_patch))
        if (isMovedAway) return@withContext Result(rh.gs(R.string.handover_test_already_moved))
        if (commandQueue.size() > 0 || commandQueue.performing() != null) return@withContext Result(rh.gs(R.string.handover_test_busy))
        val dir = fileListProvider.ensureHandoverDirExists() ?: return@withContext Result(rh.gs(R.string.handover_test_no_directory))
        // Stop talking to the patch first: the session must not move on after the file is written. From here
        // the plugin reports no active patch, so AAPS neither connects nor sends commands.
        preferences.put(MedtrumBooleanNonKey.SessionMovedAway, true)
        _movedAway.value = true
        // Where this phone's history of the patch ends: at take in it reads the patch history again from here
        preferences.put(MedtrumIntNonKey.SyncedSequenceAtMoveOut, medtrumPump.syncedSequenceNumber)
        medtrumPump.takenInSerial = null
        medtrumPlugin.getService()?.disconnect("Handover test: session moved away")
        val loopNote = turnLoopOff()
        val extras = collectExtras()
        val session = MedtrumSession.read(preferences, dateUtil.now()).withExtras(extras)
        val name = MedtrumSession.FILE_PREFIX + SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + ".txt"
        val written = runCatching {
            val file = dir.createFile("text/plain", name) ?: error("could not create $name")
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(session.toText().toByteArray()) } ?: error("could not write $name")
        }
        written.exceptionOrNull()?.let {
            aapsLogger.error(LTag.PUMP, "Handover test: writing the session failed", it)
            preferences.put(MedtrumBooleanNonKey.SessionMovedAway, false)
            preferences.put(MedtrumIntNonKey.SyncedSequenceAtMoveOut, -1)
            _movedAway.value = false
            return@withContext Result(rh.gs(R.string.handover_test_write_failed), modeToRestore = rememberedMode())
        }
        aapsLogger.info(LTag.PUMP, "Handover test: session of patch ${session.serial.toString(16)} moved out to handover/$name")
        val snapshotNote = sendToWatch(session)
        val moved = rh.gs(R.string.handover_test_moved_to_watch, "handover/$name")
        Result(listOfNotNull(moved, describeSent(extras), snapshotNote, loopNote).joinToString("\n\n"))
    }

    /**
     * Sends the handover snapshot to the watch without the patch session. The watch's engine starts on this
     * phone's settings and history, with its own virtual pump, in open loop. This phone keeps the patch and
     * its loop, so the engine can run for a night (test T2: battery, a steady loop) at no risk.
     */
    suspend fun sendSnapshotWithoutPatch(): Result = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, SNAPSHOT_FILE)
        if (!snapshotWriter.write(file.absolutePath, pumpSession = "")) return@withContext Result(rh.gs(R.string.handover_test_snapshot_failed))
        rxBus.send(EventMobileToWearFile(file.absolutePath, HandoverSnapshot.CHANNEL_PATH))
        aapsLogger.info(LTag.PUMP, "Handover test: snapshot without the patch session sent to the watch (${file.length() / 1024} KB)")
        Result(rh.gs(R.string.handover_test_snapshot_sent))
    }

    /**
     * Takes in the newest session file from the handover folder. When this phone turned its loop off at
     * move out, the result offers that mode again.
     */
    suspend fun takeIn(): Result = withContext(Dispatchers.IO) {
        if (medtrumPump.pumpSN != 0L && !isMovedAway) return@withContext Result(rh.gs(R.string.handover_test_has_own_patch))
        val dir = fileListProvider.ensureHandoverDirExists() ?: return@withContext Result(rh.gs(R.string.handover_test_no_directory))
        val file = dir.listFiles()
            .filter { it.name?.startsWith(MedtrumSession.FILE_PREFIX) == true }
            .maxByOrNull { it.lastModified() }
            ?: return@withContext Result(rh.gs(R.string.handover_test_no_file))
        val session = runCatching { context.contentResolver.openInputStream(file.uri)?.use { MedtrumSession.parse(it.readBytes().decodeToString()) } }.getOrNull()
        if (session == null || session.serial == 0L) return@withContext Result(rh.gs(R.string.handover_test_bad_file, file.name ?: "?"))
        val samePatch = session.serial == medtrumPump.pumpSN
        // Before the serial is written: the service resets a patch whose serial changes, unless it is this one
        medtrumPump.takenInSerial = session.serial
        session.writeTo(preferences)
        preferences.put(MedtrumBooleanNonKey.SessionMovedAway, false)
        _movedAway.value = false
        medtrumPump.loadVarsFromSP()
        medtrumPump.loadUserSettingsFromSP()
        val historyNote = readHistoryAgain(samePatch)
        val basalNote = applyExtras(session.extras)
        notificationManager.dismiss(NotificationId.PUMP_SESSION_FROM_WATCH)
        aapsLogger.info(LTag.PUMP, "Handover test: session of patch ${session.serial.toString(16)} taken in from ${file.name}, exported ${dateUtil.dateAndTimeString(session.exportedAt)}")
        val takenIn = rh.gs(R.string.handover_test_taken_in, file.name ?: "?", dateUtil.dateAndTimeString(session.exportedAt))
        Result(listOfNotNull(takenIn, historyNote, basalNote).joinToString("\n\n"), modeToRestore = rememberedMode())
    }

    /**
     * The other device read the patch history too, and moved the history position on. Set back to where
     * this phone was at move out, its next status read gets every record the other device made from the
     * patch itself: boluses, temporary basals, suspends. Each record is keyed by its pump id, so a record
     * already here is not added twice. This also works when the watch was lost and the session is taken in
     * from the file written at move out.
     */
    @VisibleForTesting
    internal fun readHistoryAgain(samePatch: Boolean): String? {
        val readFrom = preferences.get(MedtrumIntNonKey.SyncedSequenceAtMoveOut)
        preferences.put(MedtrumIntNonKey.SyncedSequenceAtMoveOut, -1)
        if (!samePatch || readFrom < 0 || readFrom >= medtrumPump.syncedSequenceNumber) return null
        val records = medtrumPump.syncedSequenceNumber - readFrom
        medtrumPump.syncedSequenceNumber = readFrom
        aapsLogger.info(LTag.PUMP, "Handover: the patch history from record ${readFrom + 1} is read again ($records records)")
        return rh.gs(R.string.handover_test_history_read_again, records)
    }

    /**
     * Sends the session to the watch inside the handover snapshot: the cut down database and the settings,
     * so the watch's AAPS engine starts where this phone stopped. Written after the loop was turned off, so
     * the engine starts with it off too. When the snapshot cannot be written, the session goes alone, as
     * before, and the result says so.
     */
    private suspend fun sendToWatch(session: MedtrumSession): String? {
        val file = File(context.cacheDir, SNAPSHOT_FILE)
        return if (snapshotWriter.write(file.absolutePath, session.toText())) {
            rxBus.send(EventMobileToWearFile(file.absolutePath, HandoverSnapshot.CHANNEL_PATH))
            aapsLogger.info(LTag.PUMP, "Handover test: snapshot with the session sent to the watch (${file.length() / 1024} KB)")
            null
        } else {
            rxBus.send(EventMobileToWear(EventData.MedtrumSessionToWatch(session.toText())))
            aapsLogger.warn(LTag.PUMP, "Handover test: no snapshot, session sent to the watch alone")
            rh.gs(R.string.handover_test_no_snapshot)
        }
    }

    /**
     * The watch handed the session back. It is saved as the newest session file, so "Take patch session
     * in" picks it up; the user takes it in on the Medtrum page as with a file from another phone.
     */
    private suspend fun receiveFromWatch(text: String) = withContext(Dispatchers.IO) {
        val session = MedtrumSession.parse(text)
        if (session == null || session.serial == 0L) {
            aapsLogger.error(LTag.PUMP, "Handover test: the watch sent a session that cannot be read")
            return@withContext
        }
        val dir = fileListProvider.ensureHandoverDirExists() ?: run {
            aapsLogger.error(LTag.PUMP, "Handover test: no handover folder for the session from the watch")
            return@withContext
        }
        val name = MedtrumSession.FILE_PREFIX + SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + "_watch.txt"
        val written = runCatching {
            val file = dir.createFile("text/plain", name) ?: error("could not create $name")
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(text.toByteArray()) } ?: error("could not write $name")
        }
        written.exceptionOrNull()?.let {
            aapsLogger.error(LTag.PUMP, "Handover test: saving the session from the watch failed", it)
            return@withContext
        }
        aapsLogger.info(LTag.PUMP, "Handover test: the watch handed back the session of patch ${session.serial.toString(16)}, saved as handover/$name")
        notificationManager.post(NotificationId.PUMP_SESSION_FROM_WATCH, rh.gs(R.string.handover_test_from_watch))
    }

    /** The watch's test log goes next to the session files, as Medtrum_watch_log_<date>.txt. */
    private suspend fun saveWatchLog(log: String) = withContext(Dispatchers.IO) {
        if (log.isBlank()) return@withContext
        val dir = fileListProvider.ensureHandoverDirExists() ?: run {
            aapsLogger.error(LTag.PUMP, "Handover test: no handover folder for the watch log")
            return@withContext
        }
        val name = WATCH_LOG_PREFIX + SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + ".txt"
        runCatching {
            val file = dir.createFile("text/plain", name) ?: error("could not create $name")
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(log.toByteArray()) } ?: error("could not write $name")
        }.onSuccess { aapsLogger.info(LTag.PUMP, "Handover test: watch log saved as handover/$name") }
            .onFailure { aapsLogger.error(LTag.PUMP, "Handover test: saving the watch log failed", it) }
    }

    /**
     * What goes along with the session: the total daily doses of the last 7 finished days, the running
     * temporary basal with its duration, and the basal the patch reported at this phone's last read.
     */
    private suspend fun collectExtras(): HandoverExtras {
        val tdds = tddCalculator.calculate(TDD_DAYS, allowMissingDays = true)
            ?.let { days -> (0 until days.size()).map { days.valueAt(it) } }
            ?.map { HandoverTdd(it.timestamp, it.basalAmount, it.bolusAmount, it.totalAmount, it.carbs) }
            ?.sortedBy { it.timestamp }
            .orEmpty()
        val temporaryBasal = pumpSync.expectedPumpState().temporaryBasal?.let { HandoverTemporaryBasal.of(it) }
        val basalStatus = medtrumPump.basalStatus(readAt = medtrumPump.lastConnection)
        aapsLogger.info(LTag.PUMP, "Handover test: sending ${tdds.size} daily doses, temporary basal $temporaryBasal, basal status $basalStatus")
        return HandoverExtras(tdds = tdds, temporaryBasal = temporaryBasal, basalStatus = basalStatus, movedOutAt = dateUtil.now())
    }

    private fun describeSent(extras: HandoverExtras): String {
        val tbr = extras.temporaryBasal
        return if (tbr != null && tbr.end > dateUtil.now())
            rh.gs(R.string.handover_test_sent_with_tbr, extras.tdds.size, rateText(tbr), dateUtil.timeString(tbr.end))
        else rh.gs(R.string.handover_test_sent_no_tbr, extras.tdds.size)
    }

    private fun rateText(tbr: HandoverTemporaryBasal): String =
        if (tbr.isAbsolute) rh.gs(R.string.handover_test_rate_absolute, tbr.rate) else rh.gs(R.string.handover_test_rate_percent, tbr.rate.roundToInt())

    /**
     * Brings this phone's records up to what the other device knew.
     *
     * The basal the patch reported at the other device's last read goes through the driver's own update,
     * the same one a status read runs, so a temporary basal that started or ended there gets into the
     * treatments now, before the loop runs again. It is keyed by its start on the patch, so the driver's
     * next read of the same basal adds nothing twice. The patch does not report how long a temporary basal
     * runs: the duration that came along is handed to the driver first.
     *
     * The daily doses from the day the session left are calculated again, from the complete records.
     */
    @VisibleForTesting
    internal suspend fun applyExtras(extras: HandoverExtras): String? {
        val notes = mutableListOf<String>()
        extras.basalStatus?.let { status ->
            val expected = pumpSync.expectedPumpState().temporaryBasal
            val tbr = extras.temporaryBasal
            if (status.type.isTempBasal() && expected?.pumpId != status.startTime && tbr != null && abs(tbr.timestamp - status.startTime) < T.mins(1).msecs()) {
                temporaryBasalStorage.add(
                    PumpSync.PumpState.TemporaryBasal(
                        timestamp = status.startTime,
                        duration = tbr.duration,
                        rate = status.rate,
                        isAbsolute = tbr.isAbsolute,
                        type = PumpSync.TemporaryBasalType.entries.firstOrNull { it.name == tbr.type } ?: PumpSync.TemporaryBasalType.NORMAL,
                        id = 0L,
                        pumpId = status.startTime
                    )
                )
            }
            medtrumPump.handleBasalStatusUpdate(status.type, status.rate, status.sequence, status.patchId, status.startTime, status.readAt)
            aapsLogger.info(LTag.PUMP, "Handover test: basal status from the other device applied: $status")
            notes += rh.gs(R.string.handover_test_basal_applied, dateUtil.timeString(status.readAt))
        }
        extras.movedOutAt?.let { movedOutAt ->
            val from = dateUtil.beginOfDay(movedOutAt)
            persistenceLayer.clearCachedTddData(from)
            aapsLogger.info(LTag.PUMP, "Handover test: cached daily doses from ${dateUtil.dateString(from)} cleared")
            notes += rh.gs(R.string.handover_test_tdd_recalculated, dateUtil.dateString(from))
        }
        return notes.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** The mode this phone had before it moved its session out, if any. */
    fun rememberedMode(): RM.Mode? =
        preferences.get(MedtrumStringNonKey.ModeBeforeMoveOut).takeIf { it.isNotEmpty() }?.let { name -> RM.Mode.entries.firstOrNull { it.name == name } }

    fun modeName(mode: RM.Mode): String = translator.translate(mode)

    /** Turns the loop back on in [mode], as the user confirmed. */
    suspend fun restoreMode(mode: RM.Mode) {
        val profile = profileFunction.getProfile()
        val action = when (mode) {
            RM.Mode.CLOSED_LOOP     -> Action.CLOSED_LOOP_MODE
            RM.Mode.CLOSED_LOOP_LGS -> Action.LGS_LOOP_MODE
            else                    -> Action.OPEN_LOOP_MODE
        }
        if (profile != null && loop.handleRunningModeChange(mode, action, Sources.Medtrum, profile = profile))
            aapsLogger.info(LTag.PUMP, "Handover test: loop turned back on as $mode")
        else aapsLogger.warn(LTag.PUMP, "Handover test: could not turn the loop back on as $mode")
        forgetRememberedMode()
    }

    fun forgetRememberedMode() = preferences.put(MedtrumStringNonKey.ModeBeforeMoveOut, "")

    /** Disable loop when the loop is running, remembering the mode. Returns a line for the message, if any. */
    private suspend fun turnLoopOff(): String? {
        val mode = loop.runningMode()
        if (!mode.isLoopRunning()) return null
        val profile = profileFunction.getProfile() ?: return null
        if (!loop.handleRunningModeChange(RM.Mode.DISABLED_LOOP, Action.LOOP_DISABLED, Sources.Medtrum, profile = profile)) {
            aapsLogger.warn(LTag.PUMP, "Handover test: could not disable the loop")
            return rh.gs(R.string.handover_test_loop_not_disabled)
        }
        preferences.put(MedtrumStringNonKey.ModeBeforeMoveOut, mode.name)
        aapsLogger.info(LTag.PUMP, "Handover test: loop disabled, was $mode")
        return rh.gs(R.string.handover_test_loop_disabled, translator.translate(mode))
    }

    companion object {

        const val WATCH_LOG_PREFIX = "Medtrum_watch_log_"

        /** In the app's own cache: it holds the session and the settings, and is only kept for sending. */
        private const val SNAPSHOT_FILE = "handover/snapshot.zip"

        /** Dynamic ISF needs the total daily dose of the last 7 days. */
        private const val TDD_DAYS = 7L
    }
}

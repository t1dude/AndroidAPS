package app.aaps.pump.medtrum.handover

import android.content.Context
import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Translator
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.medtrum.MedtrumPlugin
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.R
import app.aaps.pump.medtrum.handover.MedtrumSession.Companion.writeTo
import app.aaps.pump.medtrum.keys.MedtrumBooleanNonKey
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handover test (phase 0): moves a running patch session to another phone and back, through a file
 * in the AAPS exports folder, or to the watch and back, over the phone-watch link. Only one device may talk to the patch, so the phone that moved its
 * session away does not connect to the patch again until it takes a session back in.
 *
 * The file holds the patch serial and session token: anyone with it can talk to the patch. Delete it
 * after the test.
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
    private val notificationManager: NotificationManager
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var started = false

    /** What [moveOut] and [takeIn] report. [modeToRestore] is set when the loop can be turned back on. */
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
            rxBus.toFlow(EventData.MedtrumSessionToPhone::class).collect {
                saveWatchLog(it.log)
                receiveFromWatch(it.session)
            }
        }
        scope.launch { rxBus.toFlow(EventData.MedtrumWatchLog::class).collect { saveWatchLog(it.log) } }
    }

    /**
     * Writes the session to a file and stops this phone talking to the patch. A running loop is turned
     * off (Disable loop), and the mode it had is kept, to offer it again when a session is taken back in.
     * With [toWatch] the session is also sent to the watch. The file stays as a way back: if the watch
     * never gets it, this phone can take it in again.
     */
    suspend fun moveOut(toWatch: Boolean = false): Result = withContext(Dispatchers.IO) {
        if (medtrumPump.pumpSN == 0L) return@withContext Result(rh.gs(R.string.handover_test_no_patch))
        if (isMovedAway) return@withContext Result(rh.gs(R.string.handover_test_already_moved))
        if (commandQueue.size() > 0 || commandQueue.performing() != null) return@withContext Result(rh.gs(R.string.handover_test_busy))
        val dir = fileListProvider.ensureExportDirExists() ?: return@withContext Result(rh.gs(R.string.handover_test_no_directory))
        // Stop talking to the patch first: the session must not move on after the file is written. From here
        // the plugin reports no active patch, so AAPS neither connects nor sends commands.
        preferences.put(MedtrumBooleanNonKey.SessionMovedAway, true)
        _movedAway.value = true
        medtrumPump.takenInSerial = null
        medtrumPlugin.getService()?.disconnect("Handover test: session moved away")
        val loopNote = turnLoopOff()
        val session = MedtrumSession.read(preferences, dateUtil.now())
        val name = MedtrumSession.FILE_PREFIX + SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + ".txt"
        val written = runCatching {
            val file = dir.createFile("text/plain", name) ?: error("could not create $name")
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(session.toText().toByteArray()) } ?: error("could not write $name")
        }
        written.exceptionOrNull()?.let {
            aapsLogger.error(LTag.PUMP, "Handover test: writing the session failed", it)
            preferences.put(MedtrumBooleanNonKey.SessionMovedAway, false)
            _movedAway.value = false
            return@withContext Result(rh.gs(R.string.handover_test_write_failed), modeToRestore = rememberedMode())
        }
        aapsLogger.info(LTag.PUMP, "Handover test: session of patch ${session.serial.toString(16)} moved out to exports/$name")
        if (toWatch) {
            rxBus.send(EventMobileToWear(EventData.MedtrumSessionToWatch(session.toText())))
            aapsLogger.info(LTag.PUMP, "Handover test: session sent to the watch")
        }
        val moved = if (toWatch) rh.gs(R.string.handover_test_moved_to_watch, "exports/$name") else rh.gs(R.string.handover_test_moved_out, "exports/$name")
        Result(listOfNotNull(moved, loopNote).joinToString("\n\n"))
    }

    /**
     * Takes in the newest session file from the exports folder. When this phone turned its loop off at
     * move out, the result offers that mode again.
     */
    suspend fun takeIn(): Result = withContext(Dispatchers.IO) {
        if (medtrumPump.pumpSN != 0L && !isMovedAway) return@withContext Result(rh.gs(R.string.handover_test_has_own_patch))
        val dir = fileListProvider.ensureExportDirExists() ?: return@withContext Result(rh.gs(R.string.handover_test_no_directory))
        val file = dir.listFiles()
            .filter { it.name?.startsWith(MedtrumSession.FILE_PREFIX) == true }
            .maxByOrNull { it.lastModified() }
            ?: return@withContext Result(rh.gs(R.string.handover_test_no_file))
        val session = runCatching { context.contentResolver.openInputStream(file.uri)?.use { MedtrumSession.parse(it.readBytes().decodeToString()) } }.getOrNull()
        if (session == null || session.serial == 0L) return@withContext Result(rh.gs(R.string.handover_test_bad_file, file.name ?: "?"))
        // Before the serial is written: the service resets a patch whose serial changes, unless it is this one
        medtrumPump.takenInSerial = session.serial
        session.writeTo(preferences)
        preferences.put(MedtrumBooleanNonKey.SessionMovedAway, false)
        _movedAway.value = false
        medtrumPump.loadVarsFromSP()
        medtrumPump.loadUserSettingsFromSP()
        notificationManager.dismiss(NotificationId.PUMP_SESSION_FROM_WATCH)
        aapsLogger.info(LTag.PUMP, "Handover test: session of patch ${session.serial.toString(16)} taken in from ${file.name}, exported ${dateUtil.dateAndTimeString(session.exportedAt)}")
        Result(rh.gs(R.string.handover_test_taken_in, file.name ?: "?", dateUtil.dateAndTimeString(session.exportedAt)), modeToRestore = rememberedMode())
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
        val dir = fileListProvider.ensureExportDirExists() ?: run {
            aapsLogger.error(LTag.PUMP, "Handover test: no exports folder for the session from the watch")
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
        aapsLogger.info(LTag.PUMP, "Handover test: the watch handed back the session of patch ${session.serial.toString(16)}, saved as exports/$name")
        notificationManager.post(NotificationId.PUMP_SESSION_FROM_WATCH, rh.gs(R.string.handover_test_from_watch))
    }

    /** The watch's test log goes next to the session files, as Medtrum_watch_log_<date>.txt. */
    private suspend fun saveWatchLog(log: String) = withContext(Dispatchers.IO) {
        if (log.isBlank()) return@withContext
        val dir = fileListProvider.ensureExportDirExists() ?: run {
            aapsLogger.error(LTag.PUMP, "Handover test: no exports folder for the watch log")
            return@withContext
        }
        val name = WATCH_LOG_PREFIX + SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + ".txt"
        runCatching {
            val file = dir.createFile("text/plain", name) ?: error("could not create $name")
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(log.toByteArray()) } ?: error("could not write $name")
        }.onSuccess { aapsLogger.info(LTag.PUMP, "Handover test: watch log saved as exports/$name") }
            .onFailure { aapsLogger.error(LTag.PUMP, "Handover test: saving the watch log failed", it) }
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
    }
}

package app.aaps.pump.medtrum.handover

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.medtrum.MedtrumPlugin
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.R
import app.aaps.pump.medtrum.handover.MedtrumSession.Companion.writeTo
import app.aaps.pump.medtrum.keys.MedtrumBooleanNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handover test (phase 0, T1): moves a running patch session to another phone and back, through a file
 * in the AAPS exports folder. Only one device may talk to the patch, so the phone that moved its
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
    private val medtrumPlugin: MedtrumPlugin
) {

    private val _movedAway = MutableStateFlow(preferences.get(MedtrumBooleanNonKey.SessionMovedAway))

    /** True while another device has this phone's patch session. */
    val movedAway: StateFlow<Boolean> = _movedAway.asStateFlow()

    val isMovedAway: Boolean get() = _movedAway.value

    /** Writes the session to a file and stops this phone talking to the patch. Returns the message to show. */
    suspend fun moveOut(): String = withContext(Dispatchers.IO) {
        if (medtrumPump.pumpSN == 0L) return@withContext rh.gs(R.string.handover_test_no_patch)
        if (isMovedAway) return@withContext rh.gs(R.string.handover_test_already_moved)
        if (commandQueue.size() > 0 || commandQueue.performing() != null) return@withContext rh.gs(R.string.handover_test_busy)
        val dir = fileListProvider.ensureExportDirExists() ?: return@withContext rh.gs(R.string.handover_test_no_directory)
        // Stop talking to the patch first: the session must not move on after the file is written
        preferences.put(MedtrumBooleanNonKey.SessionMovedAway, true)
        _movedAway.value = true
        medtrumPump.takenInSerial = null
        medtrumPlugin.getService()?.disconnect("Handover test: session moved away")
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
            return@withContext rh.gs(R.string.handover_test_write_failed)
        }
        aapsLogger.info(LTag.PUMP, "Handover test: session of patch ${session.serial.toString(16)} moved out to exports/$name")
        rh.gs(R.string.handover_test_moved_out, "exports/$name")
    }

    /** Takes in the newest session file from the exports folder. Returns the message to show. */
    suspend fun takeIn(): String = withContext(Dispatchers.IO) {
        if (medtrumPump.pumpSN != 0L && !isMovedAway) return@withContext rh.gs(R.string.handover_test_has_own_patch)
        val dir = fileListProvider.ensureExportDirExists() ?: return@withContext rh.gs(R.string.handover_test_no_directory)
        val file = dir.listFiles()
            .filter { it.name?.startsWith(MedtrumSession.FILE_PREFIX) == true }
            .maxByOrNull { it.lastModified() }
            ?: return@withContext rh.gs(R.string.handover_test_no_file)
        val session = runCatching { context.contentResolver.openInputStream(file.uri)?.use { MedtrumSession.parse(it.readBytes().decodeToString()) } }.getOrNull()
        if (session == null || session.serial == 0L) return@withContext rh.gs(R.string.handover_test_bad_file, file.name ?: "?")
        // Before the serial is written: the service resets a patch whose serial changes, unless it is this one
        medtrumPump.takenInSerial = session.serial
        session.writeTo(preferences)
        preferences.put(MedtrumBooleanNonKey.SessionMovedAway, false)
        _movedAway.value = false
        medtrumPump.loadVarsFromSP()
        medtrumPump.loadUserSettingsFromSP()
        aapsLogger.info(LTag.PUMP, "Handover test: session of patch ${session.serial.toString(16)} taken in from ${file.name}, exported ${dateUtil.dateAndTimeString(session.exportedAt)}")
        rh.gs(R.string.handover_test_taken_in, file.name ?: "?", dateUtil.dateAndTimeString(session.exportedAt))
    }
}

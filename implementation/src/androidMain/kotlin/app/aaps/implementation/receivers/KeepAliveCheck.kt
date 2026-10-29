package app.aaps.implementation.receivers

import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.RM
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.alerts.LocalAlertUtils
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.dst.DstHelper
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventProfileChangeRequested
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.implementation.maintenance.PeriodicMaintenance
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs

/**
 * The work of the keep alive, without its schedule: the DST check, the periodic maintenance (the missed
 * reading alarm among it), the pump check (the profile on the pump, a status read when the last one is old,
 * the "pump unreachable" alarm), the zero temp basal while the pump is disconnected, and the device status
 * when the loop has not sent one.
 *
 * On the phone [KeepAliveWorker] runs it every 5 minutes. On the watch the engine runs it on every CGM
 * reading and from an alarm: WorkManager is held back there while the watch is idle, and was seen 13 to 29
 * minutes late.
 */
@SingleIn(AppScope::class)
@Inject
class KeepAliveCheck(
    private val aapsLogger: AAPSLogger,
    private val localAlertUtils: LocalAlertUtils,
    private val config: Config,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val dateUtil: DateUtil,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val rxBus: RxBus,
    private val commandQueue: CommandQueue,
    private val periodicMaintenance: PeriodicMaintenance,
    private val rh: ResourceHelper,
    private val dstHelper: DstHelper,
    private val ch: ConcentrationHelper
) {

    private var lastReadStatus: Long = 0
    private var lastIobUpload: Long = 0

    suspend fun run() {
        dstHelper.dstCheck()
        // The platform-neutral half - missed-reading alarm, snooze shortening, log and database
        // trimming - lives in PeriodicMaintenance so iOS and desktop run the same code instead of
        // nothing. What is here besides is what needs Android: the pump and APS checks.
        periodicMaintenance.runOnce()
        checkPump()
        // Retries a missing zero TBR while the pump is disconnected - nothing else does, as the loop is paused
        loop.verifyZeroDelivery()
        checkAPS()
    }

    // Usually deviceStatus is uploaded through LoopPlugin after every loop cycle.
    // if there is no BG available, we have to upload anyway to have correct
    // IOB displayed in NS
    @VisibleForTesting
    suspend fun checkAPS() {
        var shouldUploadStatus = false
        if (config.AAPSCLIENT) return
        if (config.PUMPCONTROL) shouldUploadStatus = true
        else if (!loop.runningMode().isLoopRunning() || iobCobCalculator.ads.actualBg() == null) shouldUploadStatus = true
        else if (activePlugin.activeAPS?.let { dateUtil.isOlderThan(it.lastAPSRun, 5) } == true) shouldUploadStatus = true
        if (dateUtil.isOlderThan(lastIobUpload, IOB_UPDATE_FREQUENCY_IN_MINUTES) && shouldUploadStatus) {
            lastIobUpload = dateUtil.now()
            loop.scheduleBuildAndStoreDeviceStatus("KeepAliveWorker")
        }
    }

    @VisibleForTesting
    suspend fun checkPump() {
        val pump = activePlugin.activePump
        val ps = profileFunction.getRequestedProfile() ?: return
        val requestedProfile = ProfileSealed.PS(ps, activePlugin)
        val runningProfile = profileFunction.getProfile()
        val lastConnection = pump.lastDataTime.value
        val now = dateUtil.now()
        val isStatusOutdated = lastConnection + STATUS_UPDATE_FREQUENCY < now
        // Against the running profile: it holds the rate the pump really gives, which can be rounded to its step
        // (#2454). The requested one differed by up to a step, and a pump that rounds was read every 5 minutes.
        // A requested profile that is not running yet is caught first, by the profile check below.
        val isBasalOutdated = abs((runningProfile ?: requestedProfile).getBasal() - ch.fromPump(pump.baseBasalRate)) > pump.pumpDescription.basalStep
        aapsLogger.debug(LTag.CORE, "Last connection: " + dateUtil.dateAndTimeString(lastConnection))
        // Sometimes it can happen that keepalive is not triggered every 5 minutes as it should.
        // In some cases, it may not even have been started at all.
        // If these cases aren't handled, false "pump unreachable" alarms can be produced.
        // Avoid this by checking that (a) readStatus was requested at least once (lastReadStatus
        // is != 0 in that case) and (b) the last read status request was not too long ago.
        //
        // Also, use 5:30 as the threshold for (b) above instead of 5 minutes sharp. The keepalive
        // checks come in 5 minute intervals, but due to temporal jitter, the interval between the
        // last read status attempt and the current time can be slightly over 5 minutes (for example,
        // 300041 milliseconds instead of exactly 300000). Add 30 extra seconds to allow for
        // plenty of tolerance.
        val runningMode = loop.runningMode()
        if (lastReadStatus != 0L && (now - lastReadStatus).coerceIn(minimumValue = 0, maximumValue = null) <= T.secs(5 * 60 + 30).msecs()) {
            localAlertUtils.checkPumpUnreachableAlarm(lastConnection, isStatusOutdated, runningMode == RM.Mode.DISCONNECTED_PUMP)
        }
        if (runningMode == RM.Mode.DISCONNECTED_PUMP) {
            // do nothing if pump is disconnected
        } else if (
            runningProfile == null ||
            (
                (!pump.isThisProfileSet(requestedProfile) ||
                    !requestedProfile.isAppliedAs(runningProfile) ||
                    (runningProfile is ProfileSealed.EPS && runningProfile.value.originalEnd < dateUtil.now() && runningProfile.value.originalDuration != 0L)
                    )
                    && !commandQueue.isRunning(Command.CommandType.BASAL_PROFILE)
                )
        ) {
            rxBus.send(EventProfileChangeRequested())
        } else if (isStatusOutdated && !pump.isBusy()) {
            lastReadStatus = now
            commandQueue.readStatus(rh.gs(app.aaps.core.ui.R.string.keepalive_status_outdated))
        } else if (isBasalOutdated && !pump.isBusy()) {
            lastReadStatus = now
            commandQueue.readStatus(rh.gs(app.aaps.core.ui.R.string.keepalive_basal_outdated))
        }
    }

    private companion object {

        val STATUS_UPDATE_FREQUENCY = T.mins(15).msecs()
        const val IOB_UPDATE_FREQUENCY_IN_MINUTES = 5L
    }
}

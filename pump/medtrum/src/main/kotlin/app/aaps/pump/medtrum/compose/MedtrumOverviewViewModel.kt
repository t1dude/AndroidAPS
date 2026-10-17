package app.aaps.pump.medtrum.compose

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Watch
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.RM
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.timeAgoFullString
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.pump.ActionCategory
import app.aaps.core.ui.compose.pump.PumpAction
import app.aaps.core.ui.compose.pump.PumpCommunicationStatus
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.core.ui.compose.pump.PumpOverviewStateBuilder
import app.aaps.core.ui.compose.pump.PumpOverviewUiState
import app.aaps.core.ui.compose.pump.StatusBanner
import app.aaps.core.ui.compose.pump.tickerFlow
import app.aaps.pump.medtrum.MedtrumPlugin
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.R
import app.aaps.pump.medtrum.handover.MedtrumSessionTransfer
import app.aaps.pump.medtrum.code.ConnectionState
import app.aaps.pump.medtrum.code.PatchStep
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.comm.enums.ModelType
import app.aaps.pump.medtrum.keys.MedtrumStringNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import app.aaps.core.ui.R as CoreUiR

sealed class MedtrumOverviewEvent {
    data class StartPatchWorkflow(val startStep: PatchStep) : MedtrumOverviewEvent()
    data class ShowDialog(val title: String, val message: String) : MedtrumOverviewEvent()
    data object ConfirmUnpair : MedtrumOverviewEvent()
    data object ConfirmMoveSessionOut : MedtrumOverviewEvent()
    data object ConfirmMoveSessionToWatch : MedtrumOverviewEvent()
    data object ConfirmTakeSessionIn : MedtrumOverviewEvent()

    /** After a session move: [message], then the question whether to turn the loop back on as [mode]. */
    data class OfferRestoreMode(val title: String, val message: String, val mode: RM.Mode, val modeName: String) : MedtrumOverviewEvent()
}

// Registers itself: @ViewModelKey infers the key from the class. No graph entry, and deliberately
// unscoped so each screen gets its own.
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class MedtrumOverviewViewModel(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val commandQueue: CommandQueue,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil,
    private val medtrumPlugin: MedtrumPlugin,
    val medtrumPump: MedtrumPump,
    private val ch: ConcentrationHelper,
    private val preferences: Preferences,
    private val uel: UserEntryLogger,
    private val context: Context,
    private val sessionTransfer: MedtrumSessionTransfer
) : ViewModel() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val communicationStatus = PumpCommunicationStatus(rxBus, commandQueue, rh, scope)
    private val stateBuilder = PumpOverviewStateBuilder(rh)

    private val _events = MutableSharedFlow<MedtrumOverviewEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<MedtrumOverviewEvent> = _events

    val uiState: StateFlow<PumpOverviewUiState> = combine(
        medtrumPump.connectionStateFlow,
        medtrumPump.pumpStateFlow,
        medtrumPump.lastBasalTypeFlow,
        medtrumPump.lastBasalRateFlow,
        medtrumPump.reservoirFlow,
        medtrumPump.batteryVoltage_BFlow,
        medtrumPump.bolusAmountDeliveredFlow,
        medtrumPump.lastBolusTimeFlow,
        medtrumPump.lastBolusAmountFlow,
        medtrumPump.lastConnectionFlow,
        communicationStatus.refreshTrigger,
        tickerFlow(60_000L),
        sessionTransfer.movedAway
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val connectionState = values[0] as ConnectionState
        val pumpState = values[1] as MedtrumPumpState
        val basalType = values[2] as BasalType
        val tempBasalRate = values[3] as Double
        val reservoir = values[4] as Double
        val batteryVoltage = values[5] as Double
        val bolusDelivered = values[6] as Double
        val lastBolusTime = values[7] as Long?
        val lastBolusAmount = values[8] as Double?
        val lastConnectionTime = values[9] as Long

        buildUiState(
            connectionState, pumpState, medtrumPump.baseBasalRate, basalType, tempBasalRate, medtrumPump.lastBasalStartTime, medtrumPump.lastBasalDuration, reservoir, batteryVoltage,
            bolusDelivered, lastBolusTime, lastBolusAmount, lastConnectionTime
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), buildInitialState())

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }

    fun onClickRefresh() {
        viewModelScope.launch { commandQueue.readStatus(rh.gs(R.string.requested_by_user)) }
    }

    fun onClickResetAlarms() {
        viewModelScope.launch { commandQueue.clearAlarms() }
    }

    fun onClickChangePatch() {
        aapsLogger.debug(LTag.PUMP, "ChangePatch clicked!")
        viewModelScope.launch {
            val nextStep = when {
                medtrumPump.pumpState in listOf(MedtrumPumpState.STOPPED, MedtrumPumpState.NONE)                                                    ->
                    PatchStep.PREPARE_PATCH

                medtrumPump.pumpState <= MedtrumPumpState.EJECTED && !(medtrumPump.pumpState < MedtrumPumpState.PRIMING && medtrumPump.patchPrimed) ->
                    PatchStep.RETRY_ACTIVATION

                else                                                                                                                                ->
                    PatchStep.START_DEACTIVATION
            }
            _events.tryEmit(MedtrumOverviewEvent.StartPatchWorkflow(nextStep))
        }
    }

    fun onUnpairClick() {
        _events.tryEmit(MedtrumOverviewEvent.ConfirmUnpair)
    }

    fun onMoveSessionOutClick() {
        _events.tryEmit(MedtrumOverviewEvent.ConfirmMoveSessionOut)
    }

    fun onMoveSessionToWatchClick() {
        _events.tryEmit(MedtrumOverviewEvent.ConfirmMoveSessionToWatch)
    }

    fun onTakeSessionInClick() {
        _events.tryEmit(MedtrumOverviewEvent.ConfirmTakeSessionIn)
    }

    /** Handover test: the patch session goes to a file, and this phone stops talking to the patch. */
    fun performMoveSessionOut() {
        scope.launch { show(rh.gs(R.string.handover_test_move_out), sessionTransfer.moveOut()) }
    }

    /** Handover test: as [performMoveSessionOut], and the session is also sent to the watch. */
    fun performMoveSessionToWatch() {
        scope.launch { show(rh.gs(R.string.handover_test_move_to_watch), sessionTransfer.moveOut(toWatch = true)) }
    }

    /** Handover test: the newest patch session file is taken in, and this phone drives the patch. */
    fun performTakeSessionIn() {
        scope.launch { show(rh.gs(R.string.handover_test_take_in), sessionTransfer.takeIn()) }
    }

    private fun show(title: String, result: MedtrumSessionTransfer.Result) {
        val mode = result.modeToRestore
        _events.tryEmit(
            if (mode != null) MedtrumOverviewEvent.OfferRestoreMode(title, result.message, mode, sessionTransfer.modeName(mode))
            else MedtrumOverviewEvent.ShowDialog(title, result.message)
        )
    }

    fun restoreMode(mode: RM.Mode) {
        scope.launch { sessionTransfer.restoreMode(mode) }
    }

    fun keepLoopOff() = sessionTransfer.forgetRememberedMode()

    fun performUnpair() {
        aapsLogger.debug(LTag.PUMP, "performUnpair")
        uel.log(Action.CLEAR_PAIRING_KEYS, Sources.Medtrum)
        medtrumPlugin.getService()?.disconnect("Unpair")
        preferences.put(MedtrumStringNonKey.SnInput, "0")
        medtrumPump.patchPrimed = false
        medtrumPump.pumpState = MedtrumPumpState.NONE
        medtrumPump.resetPatchParameters()
        medtrumPump.loadUserSettingsFromSP()
    }

    private fun buildInitialState(): PumpOverviewUiState {
        return buildUiState(
            connectionState = medtrumPump.connectionState,
            pumpState = medtrumPump.pumpState,
            basalRate = medtrumPump.baseBasalRate,
            basalType = medtrumPump.lastBasalType,
            tempBasalRate = medtrumPump.lastBasalRate.takeIf { medtrumPump.tempBasalInProgress }, // only show temp basal if it is in progress
            tempBasalStartTime = medtrumPump.lastBasalStartTime,
            tempBasalDuration = medtrumPump.lastBasalDuration,
            reservoir = medtrumPump.reservoir,
            batteryVoltage = medtrumPump.batteryVoltage_B,
            bolusDelivered = medtrumPump.bolusAmountDeliveredFlow.value,
            lastBolusTime = medtrumPump.lastBolusTime,
            lastBolusAmount = medtrumPump.lastBolusAmount,
            lastConnectionTime = medtrumPump.lastConnection
        )
    }

    private fun buildUiState(
        connectionState: ConnectionState,
        pumpState: MedtrumPumpState,
        basalRate: Double,
        basalType: BasalType,
        tempBasalRate: Double?,
        tempBasalStartTime: Long,
        tempBasalDuration: Int,
        reservoir: Double,
        batteryVoltage: Double,
        bolusDelivered: Double,
        lastBolusTime: Long?,
        lastBolusAmount: Double?,
        lastConnectionTime: Long
    ): PumpOverviewUiState {
        // Status banner: communication status from shared helper, or pump-specific warning
        val statusBanner =
            if (sessionTransfer.isMovedAway) StatusBanner(text = rh.gs(R.string.handover_test_banner), level = StatusLevel.WARNING)
            else buildStatusBanner(pumpState) ?: communicationStatus.statusBanner()
        val queueStatus = communicationStatus.queueStatus()

        // Connection state for action button enablement
        val isDisconnected = connectionState == ConnectionState.DISCONNECTED
        val isPumpActive = pumpState > MedtrumPumpState.EJECTED && pumpState < MedtrumPumpState.STOPPED
        val canRefresh = isDisconnected && isPumpActive

        // Last connection
        val lastConnection = if (lastConnectionTime != 0L) {
            val agoMinutes = (System.currentTimeMillis() - lastConnectionTime) / 1000 / 60
            rh.gs(app.aaps.core.interfaces.R.string.minago, agoMinutes)
        } else ""

        // Last bolus
        val lastBolus = if (lastBolusTime != null && lastBolusAmount != null) {
            ch.insulinAmountAgoString(
                PumpInsulin(lastBolusAmount),
                lastBolusTime
            )
        } else null

        // Active bolus
        val activeBolusText = if (!medtrumPump.bolusDone && medtrumPlugin.isInitialized() && bolusDelivered > 0.0) {
            ch.insulinDeliveryAgoString(
                amount = PumpInsulin(bolusDelivered),
                totalAmount = PumpInsulin(medtrumPump.bolusAmountToBeDelivered),
                startTime = medtrumPump.bolusStartTime
            )
        } else null

        // Battery voltage
        val batteryText = if (batteryVoltage > 0.0) String.format(Locale.getDefault(), "%.2f V", batteryVoltage) else null

        // Reservoir
        val reservoirText = if (reservoir > 0.0) ch.insulinAmountString(PumpInsulin(reservoir)) else null

        // Common rows from builder
        val commonRows = stateBuilder.buildCommonRows(
            lastConnection = lastConnection,
            lastBolus = lastBolus,
            battery = batteryText,
            reservoir = reservoirText,
            serialNumber = medtrumPump.pumpSNFromSP.toString(radix = 16).uppercase()
        )

        // Medtrum-specific rows
        val specificRows = buildList {
            // Pump state
            add(PumpInfoRow(label = rh.gs(R.string.pump_state_label), value = rh.gs(pumpState.label)))
            // tempBasal rate
            add(PumpInfoRow(label = rh.gs(CoreUiR.string.base_basal_rate_label), value = ch.basalRateString(PumpRate(basalRate), true)))
            tempBasalRate?.let {
                // Basal type
                // add(PumpInfoRow(label = rh.gs(R.string.basal_type_label), value = basalType.toString()))
                // tempBasal rate
                add(PumpInfoRow(label = rh.gs(CoreUiR.string.tempbasal_label), value = ch.basalTbrString(PumpRate(it), tempBasalStartTime, tempBasalDuration, basalType != BasalType.RELATIVE_TEMP)))
            }
            // Active bolus
            activeBolusText?.let {
                add(PumpInfoRow(label = rh.gs(R.string.active_bolus_label), value = it))
            }
            // Active alarms
            val activeAlarmStrings = medtrumPump.activeAlarms.map { medtrumPump.alarmStateToString(it) }
            val alarmsText = activeAlarmStrings.joinToString("\n")
            if (alarmsText.isNotEmpty()) {
                add(PumpInfoRow(label = rh.gs(R.string.active_alarms_label), value = alarmsText, level = StatusLevel.WARNING))
            }
            // Pump type
            add(PumpInfoRow(label = rh.gs(R.string.pump_type_label), value = ModelType.fromValue(medtrumPump.deviceType).toString()))
            // FW version
            if (medtrumPump.swVersion.isNotEmpty()) {
                add(PumpInfoRow(label = rh.gs(R.string.fw_version_label), value = medtrumPump.swVersion))
            }
            // Patch no
            add(PumpInfoRow(label = rh.gs(R.string.patch_no_label), value = medtrumPump.patchId.toString()))
            // Patch age
            if (medtrumPump.patchStartTime != 0L) {
                val age = System.currentTimeMillis() - medtrumPump.patchStartTime
                val agoString = timeAgoFullString(age, rh)
                val ageString = dateUtil.dateAndTimeString(medtrumPump.patchStartTime) + "\n" + agoString
                add(PumpInfoRow(label = rh.gs(R.string.patch_activation_time_label), value = ageString))
            }
            // Patch expiry
            val expiryText = buildExpiryText()
            if (expiryText.isNotEmpty()) {
                add(PumpInfoRow(label = rh.gs(R.string.patch_expiry_label), value = expiryText))
            }
        }

        // Primary actions
        val primaryActions = listOf(
            PumpAction(
                label = rh.gs(CoreUiR.string.refresh),
                icon = Icons.Filled.Refresh,
                category = ActionCategory.PRIMARY,
                enabled = canRefresh,
                onClick = { onClickRefresh() }
            ),
            PumpAction(
                label = rh.gs(R.string.reset_alarms_label),
                icon = Icons.Filled.PlayArrow,
                category = ActionCategory.PRIMARY,
                enabled = pumpState.isSuspendedByPump(),
                visible = pumpState.isSuspendedByPump(),
                onClick = { onClickResetAlarms() }
            )
        )

        val isPaired = medtrumPump.pumpSN != 0L

        // Management actions
        val managementActions = buildList {
            add(
                PumpAction(
                    label = rh.gs(R.string.change_patch_label),
                    icon = Icons.Filled.SwapHoriz,
                    category = ActionCategory.MANAGEMENT,
                    onClick = { onClickChangePatch() }
                )
            )
            // Handover test (phase 0): move the running patch session to another phone and back
            if (isPaired && !sessionTransfer.isMovedAway) {
                add(
                    PumpAction(
                        label = rh.gs(R.string.handover_test_move_out),
                        icon = Icons.Filled.Upload,
                        category = ActionCategory.MANAGEMENT,
                        onClick = { onMoveSessionOutClick() }
                    )
                )
                add(
                    PumpAction(
                        label = rh.gs(R.string.handover_test_move_to_watch),
                        icon = Icons.Filled.Watch,
                        category = ActionCategory.MANAGEMENT,
                        onClick = { onMoveSessionToWatchClick() }
                    )
                )
            } else {
                add(
                    PumpAction(
                        label = rh.gs(R.string.handover_test_take_in),
                        icon = Icons.Filled.Download,
                        category = ActionCategory.MANAGEMENT,
                        onClick = { onTakeSessionInClick() }
                    )
                )
            }
            if (isPaired) {
                add(
                    PumpAction(
                        label = rh.gs(CoreUiR.string.pump_unpair),
                        icon = Icons.Filled.Bluetooth,
                        category = ActionCategory.MANAGEMENT,
                        onClick = { onUnpairClick() }
                    )
                )
            }
        }

        return PumpOverviewUiState(
            statusBanner = statusBanner,
            queueStatus = queueStatus,
            infoRows = commonRows + specificRows,
            primaryActions = primaryActions,
            managementActions = managementActions
        )
    }

    private fun buildStatusBanner(pumpState: MedtrumPumpState): StatusBanner? {
        return when {
            pumpState >= MedtrumPumpState.OCCLUSION                                     -> StatusBanner(
                text = pumpState.toString(),
                level = StatusLevel.CRITICAL
            )

            pumpState.isSuspendedByPump()                                               -> StatusBanner(
                text = rh.gs(R.string.pump_is_suspended),
                level = StatusLevel.WARNING
            )

            pumpState == MedtrumPumpState.STOPPED || pumpState == MedtrumPumpState.NONE -> StatusBanner(
                text = rh.gs(R.string.patch_not_active),
                level = StatusLevel.WARNING
            )

            else                                                                        -> null
        }
    }

    private fun buildExpiryText(): String {
        return if (medtrumPump.desiredPatchExpiration) {
            if (medtrumPump.patchStartTime == 0L) {
                ""
            } else {
                val expiry = medtrumPump.patchStartTime + T.hours(72).msecs()
                val currentTime = System.currentTimeMillis()
                val timeLeft = expiry - currentTime
                val daysLeft = T.msecs(timeLeft).days()
                val hoursLeft = T.msecs(timeLeft).hours() % 24

                val daysString = if (daysLeft > 0) "$daysLeft ${rh.gs(app.aaps.core.interfaces.R.string.days)} " else ""
                val hoursString = "$hoursLeft ${rh.gs(app.aaps.core.interfaces.R.string.hours)}"

                dateUtil.dateAndTimeString(expiry) + "\n(" + daysString + hoursString + ")"
            }
        } else {
            rh.gs(R.string.expiry_not_enabled)
        }
    }
}

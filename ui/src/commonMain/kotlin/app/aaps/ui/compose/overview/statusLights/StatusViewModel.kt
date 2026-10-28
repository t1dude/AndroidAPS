package app.aaps.ui.compose.overview.statusLights

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.rx.events.EventConfigBuilderChange
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import app.aaps.ui.UiStrings
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.icons.IcCannulaChange
import app.aaps.core.ui.compose.icons.IcCgmInsert
import app.aaps.core.ui.compose.icons.IcGenericCgm
import app.aaps.core.ui.compose.icons.IcPatchPump
import app.aaps.core.ui.compose.icons.IcPumpBattery
import app.aaps.core.ui.compose.icons.IcPumpCartridge
import app.aaps.core.ui.compose.icons.Pump
import app.aaps.core.ui.compose.pump.tickerFlow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Registers itself: @ViewModelKey infers the key from the class. No graph entry, and deliberately
// unscoped so each screen gets its own.
@OptIn(ExperimentalCoroutinesApi::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class StatusViewModel(
    private val rh: TextResolver,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val rxBus: RxBus,
    private val preferences: Preferences,
    private val tddCalculator: TddCalculator,
    private val decimalFormatter: DecimalFormatter,
    private val processedDeviceStatusData: ProcessedDeviceStatusData
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatusUiState())
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    init {
        // Refresh only while a screen shows the status lights. Every screen collects uiState with
        // collectAsStateWithLifecycle, so in the background there is no subscriber and nothing here runs.
        // The ticker emits at once, so a screen that starts collecting gets a fresh state straight away.
        _uiState.subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .flatMapLatest { visible -> if (visible) refreshTriggers() else emptyFlow() }
            .onEach { refreshState() }
            .launchIn(viewModelScope)
        // Circle home screen: minutes since the last loop run, on every APS result and every minute,
        // also only while a screen shows it
        _uiState.subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .flatMapLatest { visible -> if (visible) merge(tickerFlow(60_000L), rxBus.toFlow(EventAPSCalculationFinished::class).map { }) else emptyFlow() }
            .onEach { refreshLoop() }
            .launchIn(viewModelScope)
    }

    private fun refreshTriggers(): Flow<Unit> = merge(
        // The ages move with time, and the cannula usage grows with every bolus and temp basal
        tickerFlow(60_000L),
        rxBus.toFlow(EventInitializationChanged::class).map { },
        rxBus.toFlow(EventConfigBuilderChange::class).map { },
        persistenceLayer.observeChanges(TE::class).map { },
        persistenceLayer.databaseClearedFlow.map { },
        // AAPSCLIENT: the reservoir comes from the NS device status
        rxBus.toFlow(EventNsClientStatusUpdated::class).map { },
        pumpLevelChanges()
    )

    /**
     * Reservoir and battery level are the only values here that come from the pump. Both are StateFlows,
     * so they emit only when the value really changes. Pump status events were used before, and they came
     * many times per connection with nothing changed (#5246). Selecting another pump gives other flows,
     * so the collection starts again on [EventConfigBuilderChange].
     */
    private fun pumpLevelChanges(): Flow<Unit> =
        rxBus.toFlow(EventConfigBuilderChange::class)
            .map { }
            .onStart { emit(Unit) }
            .flatMapLatest {
                val pump = activePlugin.activePump
                // drop(1): a StateFlow sends its current value first, which is not a change
                merge(pump.reservoirLevel.drop(1), pump.batteryLevel.drop(1)).map { }
            }

    /** Minutes since the last loop run, for the Circle home screen. Cheap, so it runs on every APS result. */
    fun refreshLoop() {
        val lastRun = if (config.AAPSCLIENT) processedDeviceStatusData.openApsTimestamp
        else activePlugin.activeAPS?.lastAPSRun ?: 0L
        val minutesAgo = if (lastRun > 0) ((dateUtil.now() - lastRun) / 60_000L).toInt().coerceAtLeast(0) else null
        val status = when {
            minutesAgo == null                -> StatusLevel.UNSPECIFIED
            minutesAgo <= LOOP_NORMAL_MINUTES -> StatusLevel.NORMAL
            minutesAgo <= LOOP_WARN_MINUTES   -> StatusLevel.WARNING
            else                              -> StatusLevel.CRITICAL
        }
        _uiState.update { it.copy(loopMinutesAgo = minutesAgo, loopStatus = status) }
    }

    /** The refresh that is still running, if any. */
    private var refreshJob: Job? = null

    private fun refreshState() {
        // Several triggers can come close together. Each call used to start its own cannula usage
        // calculation and none was ever cancelled, so they piled up on the database until the IOB
        // calculation and the pump status read stalled. Only the newest refresh matters, so the one
        // still running is cancelled.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val pump = activePlugin.activePump
            val pumpDescription = pump.pumpDescription
            val isInitialized = pump.isInitialized()
            val isPatchPump = pumpDescription.isPatchPump

            // Build status items (without expensive TDD calculation)
            val sensorStatus = buildSensorStatus()
            val insulinStatus = buildInsulinStatus(isPatchPump, pumpDescription.maxReservoirReading.toDouble())
            val cannulaChange = withContext(aapsIoDispatcher) {
                persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
            }
            val cannulaStatus = buildCannulaStatus(cannulaChange, isPatchPump, includeTddCalculation = false)
            val batteryStatus = if (!isPatchPump || pumpDescription.useHardwareLink) {
                buildBatteryStatus()
            } else null

            // Circle home screen pills. On a client the local pump and sensor are not the real ones.
            val pumpPlugin = activePlugin.activePumpInternal as? PluginBase
            val bgSourcePlugin = activePlugin.activeBgSource as? PluginBase
            val pumpTimeLeft = if (config.AAPSCLIENT) null else buildTimeLeft(
                expiresAt = pump.expiresAt(),
                startType = TE.Type.CANNULA_CHANGE,
                warnKey = IntKey.OverviewCageWarning,
                criticalKey = IntKey.OverviewCageCritical
            )
            val sensorTimeLeft = buildTimeLeft(
                expiresAt = if (config.AAPSCLIENT) null else activePlugin.activeBgSource.sensorExpiresAt,
                startType = TE.Type.SENSOR_CHANGE,
                warnKey = IntKey.OverviewSageWarning,
                criticalKey = IntKey.OverviewSageCritical
            )

            _uiState.update { state ->
                state.copy(
                    sensorStatus = sensorStatus,
                    insulinStatus = insulinStatus,
                    // Preserve previous cannula level while TDD recalculates
                    cannulaStatus = state.cannulaStatus?.let { prev ->
                        cannulaStatus.copy(
                            level = prev.level,
                            levelStatus = prev.levelStatus,
                            levelPercent = prev.levelPercent
                        )
                    } ?: cannulaStatus,
                    batteryStatus = batteryStatus,
                    showFill = pumpDescription.isRefillingCapable && isInitialized,
                    showPumpBatteryChange = pumpDescription.isBatteryReplaceable || pump.isBatteryChangeLoggingEnabled(),
                    isPatchPump = isPatchPump,
                    pumpIcon = pumpPlugin?.pluginDescription?.icon ?: Pump,
                    pumpPluginClass = pumpPlugin?.let { it::class.simpleName },
                    pumpTimeLeft = pumpTimeLeft,
                    sensorIcon = bgSourcePlugin?.pluginDescription?.icon ?: IcGenericCgm,
                    bgSourcePluginClass = bgSourcePlugin?.let { it::class.simpleName },
                    sensorTimeLeft = sensorTimeLeft,
                    apsPluginClass = (activePlugin.activeAPS as? PluginBase)?.let { it::class.simpleName }
                )
            }

            // Calculate cannula usage (expensive operation). The items above are already shown, and the
            // usage is part of this job, so a newer refresh cancels it too.
            val cannulaStatusWithUsage = buildCannulaStatus(cannulaChange, isPatchPump, includeTddCalculation = true)
            _uiState.update { state ->
                state.copy(cannulaStatus = cannulaStatusWithUsage)
            }
        }
    }

    private suspend fun buildSensorStatus(): StatusItem {
        val event = withContext(aapsIoDispatcher) {
            persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
        }
        val bgSource = activePlugin.activeBgSource
        // Sensor battery: not shown in Overview (compact), shown in Actions (expanded) unless AAPSCLIENT
        val hasBattery = !config.AAPSCLIENT && bgSource.sensorBatteryLevel != -1
        val level = if (hasBattery) rh.gs(CoreUiStrings.units_format_percent, bgSource.sensorBatteryLevel) else null
        val levelPercent = if (hasBattery) bgSource.sensorBatteryLevel / 100f else -1f

        return StatusItem(
            label = rh.gs(CoreUiStrings.sensor_label),
            age = event?.let { formatAge(it.timestamp) } ?: "-",
            ageStatus = event?.let { getAgeStatus(it.timestamp, IntKey.OverviewSageWarning, IntKey.OverviewSageCritical) } ?: StatusLevel.UNSPECIFIED,
            agePercent = event?.let { getAgePercent(it.timestamp, IntKey.OverviewSageCritical) } ?: 0f,
            level = level,
            levelStatus = if (levelPercent >= 0) getLevelStatus((levelPercent * 100).toDouble(), IntKey.OverviewSbatWarning, IntKey.OverviewSbatCritical) else StatusLevel.UNSPECIFIED,
            levelPercent = if (levelPercent >= 0) 1f - levelPercent else -1f,
            icon = IcCgmInsert,
            compactLevel = false // Overview: sensor battery not shown
        )
    }

    private suspend fun buildInsulinStatus(isPatchPump: Boolean, maxReading: Double): StatusItem {
        val event = withContext(aapsIoDispatcher) {
            persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.INSULIN_CHANGE)
        }
        // AAPSCLIENT: local activePump is VirtualPump with a stale hardcoded reservoir.
        // The followed pump's real reservoir arrives via NS device status (already in display units).
        val reservoirLevel = if (config.AAPSCLIENT) {
            processedDeviceStatusData.pumpData?.reservoir ?: 0.0
        } else {
            // Concentration comes from the running profile, which owns the authoritative iCfg. With no
            // profile there is no IU conversion to make, so the reservoir reads as unavailable — 0.0
            // takes the existing "-" / UNSPECIFIED branch below rather than showing a mis-scaled figure.
            profileFunction.getProfile()?.let { activePlugin.activePump.reservoirLevel.value.iU(it.insulinConcentration()) } ?: 0.0
        }
        val level: String? = if (reservoirLevel > 0) {
            if (!config.AAPSCLIENT && isPatchPump && reservoirLevel >= maxReading) {
                rh.gs(CoreUiStrings.value_with_unit, decimalFormatter.to0Decimal(maxReading) + "+", rh.gs(CoreUiStrings.insulin_unit_shortname))
            } else {
                rh.gs(CoreUiStrings.value_with_unit, decimalFormatter.to0Decimal(reservoirLevel), rh.gs(CoreUiStrings.insulin_unit_shortname))
            }
        } else null

        return StatusItem(
            label = rh.gs(CoreUiStrings.insulin_label),
            age = event?.let { formatAge(it.timestamp) } ?: "-",
            ageStatus = event?.let { getAgeStatus(it.timestamp, IntKey.OverviewIageWarning, IntKey.OverviewIageCritical) } ?: StatusLevel.UNSPECIFIED,
            agePercent = event?.let { getAgePercent(it.timestamp, IntKey.OverviewIageCritical) } ?: 0f,
            level = level,
            levelStatus = if (reservoirLevel > 0) getLevelStatus(reservoirLevel, IntKey.OverviewResWarning, IntKey.OverviewResCritical) else StatusLevel.UNSPECIFIED,
            levelPercent = -1f, // No progress bar - reservoir sizes vary by pump
            icon = IcPumpCartridge,
            compactAge = !isPatchPump, // Overview: insulin age hidden for patch pumps
        )
    }

    private suspend fun buildCannulaStatus(event: TE?, isPatchPump: Boolean, includeTddCalculation: Boolean): StatusItem {
        // Calculate usage since last cannula change (expensive - can be deferred)
        val usage = if (includeTddCalculation && event != null) {
            withContext(aapsIoDispatcher) {
                tddCalculator.calculateIntervalWithCachedDays(event.timestamp, dateUtil.now(), allowMissingData = false)?.totalAmount ?: 0.0
            }
        } else 0.0

        val label = if (isPatchPump) rh.gs(CoreUiStrings.patch_pump) else rh.gs(CoreUiStrings.cannula)
        val icon = if (isPatchPump) IcPatchPump else IcCannulaChange

        return StatusItem(
            label = label,
            age = event?.let { formatAge(it.timestamp) } ?: "-",
            ageStatus = event?.let { getAgeStatus(it.timestamp, IntKey.OverviewCageWarning, IntKey.OverviewCageCritical) } ?: StatusLevel.UNSPECIFIED,
            agePercent = event?.let { getAgePercent(it.timestamp, IntKey.OverviewCageCritical) } ?: 0f,
            level = if (usage > 0) rh.gs(CoreUiStrings.value_with_unit, decimalFormatter.to0Decimal(usage), rh.gs(CoreUiStrings.insulin_unit_shortname)) else null,
            levelStatus = StatusLevel.UNSPECIFIED, // Usage doesn't have warning thresholds
            levelPercent = -1f,
            icon = icon,
            compactLevel = false // Overview: cannula usage not shown
        )
    }

    private suspend fun buildBatteryStatus(): StatusItem? {
        val pump = activePlugin.activePump
        val hasAge = pump.pumpDescription.isBatteryReplaceable || pump.isBatteryChangeLoggingEnabled()

        // Eros doesn't report battery itself, but RileyLink alternatives may
        val erosBatteryLinkAvailable = pump.model() == PumpType.OMNIPOD_EROS && pump.isUseRileyLinkBatteryLevel()
        val batteryLevelValue = pump.batteryLevel.value?.toDouble()
        val hasLevel = batteryLevelValue != null && (pump.model().supportBatteryLevel || erosBatteryLinkAvailable)

        // If neither age nor level can be shown, skip entirely
        if (!hasAge && !hasLevel) return null

        val event = if (hasAge) withContext(aapsIoDispatcher) {
            persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.PUMP_BATTERY_CHANGE)
        } else null

        // AAPSCLIENT: followed pump's battery is shown in the NSClient status card,
        // so suppress it here (no "n/a" placeholder cluttering the pill).
        val showLevel = !config.AAPSCLIENT && hasLevel

        // Overview compact: pbLevel.visibility based on pump model only (Eros OR not Combo/Dash)
        val useBatteryLevel = pump.model() == PumpType.OMNIPOD_EROS
            || (pump.model() != PumpType.ACCU_CHEK_COMBO && pump.model() != PumpType.OMNIPOD_DASH)

        return StatusItem(
            label = rh.gs(CoreUiStrings.pb_label),
            age = event?.let { formatAge(it.timestamp) } ?: "-",
            ageStatus = event?.let { getAgeStatus(it.timestamp, IntKey.OverviewBageWarning, IntKey.OverviewBageCritical) } ?: StatusLevel.UNSPECIFIED,
            agePercent = event?.let { getAgePercent(it.timestamp, IntKey.OverviewBageCritical) } ?: 0f,
            level = if (showLevel) rh.gs(CoreUiStrings.units_format_percent, batteryLevelValue.toInt()) else null,
            levelStatus = if (showLevel) getLevelStatus(batteryLevelValue, IntKey.OverviewBattWarning, IntKey.OverviewBattCritical) else StatusLevel.UNSPECIFIED,
            levelPercent = if (showLevel) 1f - (batteryLevelValue.toFloat() / 100f) else -1f,
            icon = IcPumpBattery,
            compactAge = hasAge, // Overview: pbAge shown only if replaceable/logging
            compactLevel = showLevel && useBatteryLevel, // hidden when no level value (e.g., AAPSCLIENT)
            expandedLevel = showLevel
        )
    }

    /**
     * Time left on the pod, cannula or sensor. The device's own end time is used when it knows it.
     * Otherwise the end is the last change plus the "critical age" status light setting.
     * It turns amber in the last (critical - warning) hours, and red when the time is over.
     */
    private suspend fun buildTimeLeft(expiresAt: Long?, startType: TE.Type, warnKey: IntPreferenceKey, criticalKey: IntPreferenceKey): TimeLeftItem? {
        val criticalHours = preferences.get(criticalKey)
        val end = expiresAt ?: withContext(aapsIoDispatcher) {
            persistenceLayer.getLastTherapyRecordUpToNow(startType)
        }?.let { it.timestamp + criticalHours * HOUR_MS } ?: return null
        val left = end - dateUtil.now()
        val warnWindow = (criticalHours - preferences.get(warnKey)).coerceAtLeast(0) * HOUR_MS
        val status = when {
            left <= 0          -> StatusLevel.CRITICAL
            left <= warnWindow -> StatusLevel.WARNING
            else               -> StatusLevel.NORMAL
        }
        val minutes = left / 60_000L
        val text = when {
            left <= 0          -> rh.gs(CoreUiStrings.time_left_expired)
            minutes >= 24 * 60 -> rh.gs(CoreUiStrings.time_left_days_hours, (minutes / (24 * 60)).toInt(), (minutes / 60 % 24).toInt())
            else               -> rh.gs(CoreUiStrings.time_left_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
        return TimeLeftItem(text = text, status = status)
    }

    // Always the short form, on every screen size: a status row is read at a glance, and the long
    // words ("3 days 4 hours") pushed the row's own name out of its place on a narrow screen.
    // The days part is left out while there is none, so a fresh sensor reads "4h" and not "0d4h".
    private fun formatAge(timestamp: Long): String {
        val diff = dateUtil.computeDiff(timestamp, dateUtil.now())
        return if (diff.days == 0L) rh.gs(UiStrings.status_age_short_hours, diff.hours)
        else rh.gs(UiStrings.status_age_short, diff.days, diff.hours)
    }

    private fun getAgeStatus(timestamp: Long, warnKey: IntPreferenceKey, urgentKey: IntPreferenceKey): StatusLevel {
        val warnHours = preferences.get(warnKey)
        val urgentHours = preferences.get(urgentKey)
        val ageHours = (dateUtil.now() - timestamp) / (1000 * 60 * 60)
        return when {
            ageHours >= urgentHours -> StatusLevel.CRITICAL
            ageHours >= warnHours   -> StatusLevel.WARNING
            else                    -> StatusLevel.NORMAL
        }
    }

    private fun getAgePercent(timestamp: Long, urgentKey: IntPreferenceKey): Float {
        val urgentHours = preferences.get(urgentKey)
        if (urgentHours <= 0) return 0f
        val ageHours = (dateUtil.now() - timestamp) / (1000.0 * 60 * 60)
        return (ageHours / urgentHours).coerceIn(0.0, 1.0).toFloat()
    }

    private fun getLevelStatus(level: Double, warnKey: IntKey, criticalKey: IntKey): StatusLevel {
        val warn = preferences.get(warnKey)
        val critical = preferences.get(criticalKey)
        return when {
            level <= critical -> StatusLevel.CRITICAL
            level <= warn     -> StatusLevel.WARNING
            else              -> StatusLevel.NORMAL
        }
    }

    private companion object {

        const val HOUR_MS = 60 * 60 * 1000L

        /** The loop runs on every 5 minute reading, so up to 6 minutes is normal. */
        const val LOOP_NORMAL_MINUTES = 6
        const val LOOP_WARN_MINUTES = 10
    }
}

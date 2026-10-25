package app.aaps.ui.compose.overview.statusLights

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.ui.compose.StatusLevel

/**
 * UI state for Overview status section (sensor/insulin/cannula/battery)
 */
@Immutable
data class StatusUiState(
    val sensorStatus: StatusItem? = null,
    val insulinStatus: StatusItem? = null,
    val cannulaStatus: StatusItem? = null,
    val batteryStatus: StatusItem? = null,
    val showFill: Boolean = false,
    val showPumpBatteryChange: Boolean = false,
    val isPatchPump: Boolean = false,
    // The pills of the Circle home screen
    val pumpIcon: ImageVector? = null,
    val pumpPluginClass: String? = null,
    val pumpTimeLeft: TimeLeftItem? = null,
    val sensorIcon: ImageVector? = null,
    val bgSourcePluginClass: String? = null,
    val sensorTimeLeft: TimeLeftItem? = null,
    val apsPluginClass: String? = null,
    /** Minutes since the loop last ran, null when it has not run yet. */
    val loopMinutesAgo: Int? = null,
    val loopStatus: StatusLevel = StatusLevel.UNSPECIFIED
)

/** Time left on a pod, cannula or sensor, already formatted. */
@Immutable
data class TimeLeftItem(
    val text: String,
    val status: StatusLevel
)

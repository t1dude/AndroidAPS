package app.aaps.wear.engine

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.TextRef
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * The limits of the AAPS engine on the watch, whatever the phone's settings say. Always on.
 *
 * - **Temp basals of [MAX_TEMP_BASAL_MINUTES] at most.** The phone never takes the patch back by itself, so a
 *   watch that dies leaves its last temp basal running. The loop asks for a zero temp of 120 minutes when the
 *   glucose goes low; short ones mean the profile basal again within half an hour.
 * - **LGS only** (step 4 of the plan): the loop may lower the basal and stop it, never give more. It goes when
 *   closed loop is allowed on the watch (step 5).
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(447)
@SingleIn(AppScope::class)
@Inject
class WatchEngineSafetyPlugin(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    notificationManager: NotificationManager
) : PluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.CONSTRAINTS)
        .pluginName(TextRef.Literal("Watch engine limits"))
        .description(TextRef.Literal("Short temp basals, LGS only"))
        .enforce(EnforcedState.Enabled)
        .showInList { false },
    aapsLogger = aapsLogger,
    rh = rh,
    notificationManager = notificationManager
), PluginConstraints {

    override fun applyTempBasalDurationConstraints(minutes: Constraint<Int>): Constraint<Int> =
        minutes.setIfSmaller(MAX_TEMP_BASAL_MINUTES, "The watch sets temp basals of $MAX_TEMP_BASAL_MINUTES minutes at most", this)

    override fun isLgsForced(value: Constraint<Boolean>): Constraint<Boolean> =
        value.set(true, "The watch runs LGS only", this)

    companion object {

        const val MAX_TEMP_BASAL_MINUTES = 30
    }
}

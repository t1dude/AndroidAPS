package app.aaps.wear.engine.source

import app.aaps.core.data.model.GV
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.keys.interfaces.TextRef
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * The engine's glucose source on the watch: the Dexcom G7 readings the watch app reads itself, in the
 * sensor's watch slot (direct to watch). The companion owns the sensor link and hands each new reading to
 * the engine ([store]), which keeps it the way every glucose source does, so the loop runs on it as on
 * the phone.
 *
 * Always on: the phone's glucose sources are not on the watch (see `EnginePlugins`), so whatever source the
 * imported settings name, this is the one the engine has.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(446)
@SingleIn(AppScope::class)
@Inject
class WatchG7SourcePlugin(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    notificationManager: NotificationManager,
    private val persistenceLayer: PersistenceLayer
) : PluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .pluginName(TextRef.Literal("Dexcom G7 (watch)"))
        .description(TextRef.Literal("The G7 readings the watch reads itself"))
        .enforce(EnforcedState.Enabled)
        // AAPS falls back to the default of each category; the phone's default source is not on the watch
        .setDefault(),
    aapsLogger = aapsLogger,
    rh = rh,
    notificationManager = notificationManager
), BgSource {

    /** Readings from the companion's G7 link, already checked as usable there. */
    suspend fun store(readings: List<GV>) {
        if (readings.isEmpty()) return
        runCatching { persistenceLayer.insertCgmSourceData(Sources.Dexcom, readings, emptyList(), null) }
            .onSuccess { aapsLogger.debug(LTag.BGSOURCE, "Watch G7: stored ${readings.size} readings, newest ${readings.maxOf { it.timestamp }}") }
            .onFailure { aapsLogger.error(LTag.BGSOURCE, "Watch G7: storing readings failed", it) }
    }
}

package app.aaps.wear.engine

import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.plugins.source.AidexPlugin
import app.aaps.plugins.source.DexcomPlugin
import app.aaps.plugins.source.GlimpPlugin
import app.aaps.plugins.source.GlunovoPlugin
import app.aaps.plugins.source.IntelligoPlugin
import app.aaps.plugins.source.MM640gPlugin
import app.aaps.plugins.source.NSClientSourcePlugin
import app.aaps.plugins.source.NotificationReaderPlugin
import app.aaps.plugins.source.PatchedSiAppPlugin
import app.aaps.plugins.source.PatchedSinoAppPlugin
import app.aaps.plugins.source.PoctechPlugin
import app.aaps.plugins.source.RandomBgPlugin
import app.aaps.plugins.source.SyaiPlugin
import app.aaps.plugins.source.TomatoPlugin
import app.aaps.plugins.source.XdripSourcePlugin
import app.aaps.plugins.source.instara.InstaraPlugin
import app.aaps.plugins.sync.garmin.GarminPlugin
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import app.aaps.plugins.sync.tidepool.TidepoolPlugin
import app.aaps.plugins.sync.tizen.TizenPlugin
import app.aaps.plugins.sync.wear.WearPlugin
import app.aaps.plugins.sync.xdrip.XdripPlugin
import kotlin.reflect.KClass

/**
 * Which plugins the engine on the watch may run.
 *
 * The plugins the watch does not use are left out of the plugin list the engine registers. A plugin that
 * is not in the list is never started and cannot be found by the rest of AAPS, whatever the settings say,
 * also settings imported from the phone. That is stronger than a switch in the settings.
 */
object EnginePlugins {

    val leftOut: Set<KClass<out PluginBase>> = setOf(
        // Nightscout and the other uploads: off on the watch, the phone syncs at hand back
        NSClientV3Plugin::class, TidepoolPlugin::class, XdripPlugin::class, GarminPlugin::class, TizenPlugin::class,
        // The phone's end of the phone-watch link
        WearPlugin::class,
        // Glucose sources of the phone: the watch reads its own G7
        AidexPlugin::class, DexcomPlugin::class, GlimpPlugin::class, GlunovoPlugin::class, InstaraPlugin::class,
        IntelligoPlugin::class, MM640gPlugin::class, NotificationReaderPlugin::class, NSClientSourcePlugin::class,
        PatchedSiAppPlugin::class, PatchedSinoAppPlugin::class, PoctechPlugin::class, RandomBgPlugin::class,
        SyaiPlugin::class, TomatoPlugin::class, XdripSourcePlugin::class
    )

    /** [plugins] without the ones the watch does not run. */
    fun forWatch(plugins: List<PluginBase>): List<PluginBase> = plugins.filterNot { it::class in leftOut }
}

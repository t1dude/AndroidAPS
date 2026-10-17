package app.aaps.wear.engine

import app.aaps.core.interfaces.di.MetroMemberInjector
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.objects.di.CoreObjectsGraph
import app.aaps.database.di.DatabaseConfig
import app.aaps.pump.virtual.VirtualPumpPlugin
import app.aaps.wear.engine.di.EngineGraph
import app.aaps.wear.engine.source.WatchG7SourcePlugin
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The engine's start on a real graph, as the watch runs it. Found the hard way: AAPS needs a default plugin
 * in every category, and the engine once had no calibration plugin at all, so its start threw on the watch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineStartTest {

    private fun graph(): EngineGraph = createGraphFactory<EngineGraph.Factory>().create(
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        context = RuntimeEnvironment.getApplication(),
        memberInjector = MetroMemberInjector { false },
        databaseConfig = DatabaseConfig.IN_MEMORY,
        buildInfo = EngineBuildInfo(versionName = "4.0.0-test", buildVersion = "test", applicationId = "info.nightscout.androidaps", buildType = "debug", debug = true),
        companion = object : CompanionLink {
            override fun sendToPhone(data: EventData) = Unit
        },
        coreObjects = CoreObjectsGraph
    )

    @Test
    fun theEngineStartsWithTheWatchsPlugins() = runBlocking {
        val graph = graph()

        graph.startEngine(settings = null)

        assertThat(graph.activePlugin.activePump.selectedActivePump()).isInstanceOf(VirtualPumpPlugin::class.java)
        assertThat(graph.activePlugin.activeBgSource).isInstanceOf(WatchG7SourcePlugin::class.java)
        assertThat(graph.pluginStore.plugins.map { it::class }).containsNoneIn(EnginePlugins.leftOut)
        assertThat(graph.config.appInitialized).isTrue()
    }
}

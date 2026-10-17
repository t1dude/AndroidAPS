package app.aaps.wear.engine

import android.content.Context
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.aaps.core.interfaces.configuration.ExternalOptions
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
import org.junit.Before
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

    @Before
    fun setUp() {
        // The engine's start schedules its keep alive, as the watch app's WorkManager does on the watch
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
    }

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
        // Found on the watch: without the keep alive the virtual pump never got the profile, and the loop
        // stopped at "no basal on the pump" on every reading
        assertThat(WorkManager.getInstance(RuntimeEnvironment.getApplication()).getWorkInfosForUniqueWork("KeepAlive").get()).isNotEmpty()
    }

    /** Engineering mode is the phone's, as the last snapshot brought it: the watch has no AAPS folder for the file. */
    @Test
    fun engineeringModeFollowsThePhone() {
        val graph = graph()
        val state = RuntimeEnvironment.getApplication().getSharedPreferences(EngineConfig.STATE_FILE, Context.MODE_PRIVATE)

        state.edit().putBoolean(EngineConfig.ENGINEERING_MODE_KEY, false).commit()
        assertThat(graph.config.isEngineeringMode()).isFalse()
        assertThat(graph.config.isEnabled(ExternalOptions.ENGINEERING_MODE)).isFalse()

        state.edit().putBoolean(EngineConfig.ENGINEERING_MODE_KEY, true).commit()
        assertThat(graph.config.isEngineeringMode()).isTrue()
        assertThat(graph.config.isEnabled(ExternalOptions.ENGINEERING_MODE)).isTrue()
        assertThat(graph.config.isEngineeringModeOrRelease()).isTrue()
    }

    /**
     * With the phone's settings, as a handover snapshot brings them. Found on the watch: the import reads the
     * plugin list to find each setting's key, and the settings were applied before the list existed.
     */
    @Test
    fun theEngineStartsWithThePhonesSettings() = runBlocking {
        val graph = graph()
        val settings = mapOf(
            // A setting of a plugin the engine has, and a phone glucose source the watch does not run
            "ConfigBuilder_Enabled_APS_OpenAPSSMBPlugin" to "true",
            "ConfigBuilder_Enabled_BGSOURCE_DexcomPlugin" to "true",
            "ConfigBuilder_Enabled_PUMP_MedtrumPlugin" to "true"
        )

        graph.startEngine(settings)

        assertThat(graph.config.appInitialized).isTrue()
        // The pump choice stays the engine's own, and the glucose source the watch's G7
        assertThat(graph.activePlugin.activePump.selectedActivePump()).isInstanceOf(VirtualPumpPlugin::class.java)
        assertThat(graph.activePlugin.activeBgSource).isInstanceOf(WatchG7SourcePlugin::class.java)
    }
}

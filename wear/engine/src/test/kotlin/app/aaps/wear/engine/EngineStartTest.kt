package app.aaps.wear.engine

import android.app.AlarmManager
import android.content.Context
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.configuration.ExternalOptions
import app.aaps.core.interfaces.handover.HandoverSnapshot
import app.aaps.core.interfaces.di.MetroMemberInjector
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.di.CoreObjectsGraph
import app.aaps.database.di.DatabaseConfig
import app.aaps.pump.medtrum.MedtrumPlugin
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.handover.MedtrumSession
import app.aaps.pump.medtrum.keys.MedtrumIntNonKey
import app.aaps.pump.medtrum.keys.MedtrumStringNonKey
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The engine's start on a real graph, as the watch runs it. Found the hard way: AAPS needs a default plugin
 * in every category, and the engine once had no calibration plugin at all, so its start threw on the watch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineStartTest {

    @Before
    fun setUp() {
        // The engine's start schedules the phone's other workers (running mode expiry), as on the watch
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
            override suspend fun sendFileToPhone(file: File, channelPath: String) = true
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
        // stopped at "no basal on the pump" on every reading. It runs from the CGM and an alarm, not from
        // WorkManager, which was 13 to 29 minutes late on the watch.
        val alarmManager = RuntimeEnvironment.getApplication().getSystemService(AlarmManager::class.java)
        assertThat(shadowOf(alarmManager).scheduledAlarms).isNotEmpty()
        assertThat(WorkManager.getInstance(RuntimeEnvironment.getApplication()).getWorkInfosForUniqueWork("KeepAlive").get()).isEmpty()
    }

    /** With a patch session the engine's pump is the Medtrum driver, the phone's own, whatever was set before. */
    @Test
    fun withAPatchSessionThePumpIsTheMedtrumDriver() = runBlocking {
        val graph = graph()

        graph.startEngine(settings = null, pumpSession = patchSession())

        assertThat(graph.medtrumWatchLink.holdsSession).isTrue()
        assertThat(graph.activePlugin.activePump.selectedActivePump()).isInstanceOf(MedtrumPlugin::class.java)
    }

    private fun patchSession(): String = MedtrumSession(
        exportedAt = 1_793_222_415_279L,
        values = mapOf(MedtrumStringNonKey.SnInput.key to "FC41637C", MedtrumIntNonKey.PumpState.key to MedtrumPumpState.ACTIVE.state.toInt().toString())
    ).toText()

    /** What an earlier test run without a patch left on the watch: the virtual pump registered. */
    private fun EngineGraph.registerTheVirtualPump() {
        preferences.put(StringNonKey.ActivePumpType, PumpType.GENERIC_AAPS.description)
        preferences.put(StringNonKey.ActivePumpSerialNumber, "N2XV9PYK83")
        preferences.put(LongNonKey.ActivePumpChangeTimestamp, 1_792_000_000_000L)
    }

    /**
     * Found on the watch: it still had the virtual pump of an earlier test registered, and threw away the
     * patch's records ("Ignoring pump history record"). The engine takes the pump the phone takes records from.
     */
    @Test
    fun theEngineTakesRecordsFromThePhonesPump() = runBlocking {
        val graph = graph()
        graph.registerTheVirtualPump()
        val phones = HandoverSnapshot.PumpIdentity(type = PumpType.MEDTRUM_NANO.description, serial = "fc41637c", since = 1_793_290_348_000L)

        graph.startEngine(settings = null, pumpSession = patchSession(), pumpIdentity = phones)

        assertThat(graph.pumpSync.verifyPumpIdentification(PumpType.MEDTRUM_NANO, "fc41637c")).isTrue()
        // From the phone's time: a temporary basal the phone set before the handover ends on the watch
        assertThat(graph.preferences.get(LongNonKey.ActivePumpChangeTimestamp)).isEqualTo(phones.since)
    }

    /** A phone without a registered pump: the old one goes, so the patch's first record registers the patch. */
    @Test
    fun withoutThePhonesPumpTheOldOneGoes() = runBlocking {
        val graph = graph()
        graph.registerTheVirtualPump()

        graph.startEngine(settings = null, pumpSession = patchSession(), pumpIdentity = null)

        assertThat(graph.preferences.get(StringNonKey.ActivePumpType)).isEmpty()
        assertThat(graph.preferences.get(StringNonKey.ActivePumpSerialNumber)).isEmpty()
    }

    /**
     * The engine's limits (step 4): the loop asks for a zero temp of 120 minutes, the watch sets 30 at most, so a
     * watch that dies leaves the profile basal again within half an hour. And LGS only.
     */
    @Test
    fun theWatchSetsShortTempBasalsAndLgsOnly() = runBlocking {
        val graph = graph()

        graph.startEngine(settings = null, pumpSession = patchSession())

        assertThat(graph.pluginStore.plugins.filterIsInstance<WatchEngineSafetyPlugin>().single().isEnabled()).isTrue()
        assertThat(graph.constraintsChecker.applyTempBasalDurationConstraints(ConstraintObject(120, graph.aapsLogger)).value()).isEqualTo(30)
        assertThat(graph.constraintsChecker.applyTempBasalDurationConstraints(ConstraintObject(20, graph.aapsLogger)).value()).isEqualTo(20)
        assertThat(graph.constraintsChecker.isLgsForced().value()).isTrue()
    }

    /**
     * Found on the watch: the engine set its running mode before init had completed. There is no profile
     * until then, so open loop was never set and the loop stayed disabled. The mode is set after init.
     */
    @Test
    fun theRunningModeIsSetAfterInit() = runBlocking {
        val graph = graph()
        var initializedWhenModeWasSet: Boolean? = null

        graph.startEngine(settings = null) {
            initializedWhenModeWasSet = graph.config.appInitialized
            true
        }

        assertThat(initializedWhenModeWasSet).isTrue()
    }

    /**
     * Files the engine's plugins read from the assets. Found on the watch: without `definition.json` the
     * version checker threw inside the loop's constraints, and the algorithm never gave a result.
     */
    @Test
    fun theAssetsThePluginsReadAreThere() {
        val assets = RuntimeEnvironment.getApplication().assets
        listOf("definition.json", "revoked_certs.txt").forEach { name ->
            assertThat(assets.open(name).use { it.readBytes() }).isNotEmpty()
        }
        // The phone's log setup must not change how the watch app logs
        assertThat(assets.list("").orEmpty().toList()).doesNotContain("logback.xml")
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

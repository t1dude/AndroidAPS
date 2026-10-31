package app.aaps.wear.engine.di

import android.content.Context
import androidx.work.ListenableWorker
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.di.APS
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.di.FeatureMemberInjectors
import app.aaps.core.interfaces.di.MetroMemberInjector
import app.aaps.core.interfaces.di.NotNSClient
import app.aaps.core.interfaces.di.PumpDriver
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.di.CoreObjectsGraph
import app.aaps.core.objects.workflow.MetroWorkerCreator
import app.aaps.core.objects.workflow.WorkerKey
import app.aaps.database.di.DatabaseConfig
import app.aaps.implementation.maintenance.PreferenceImportApplier
import app.aaps.implementation.plugin.PluginStore
import app.aaps.implementation.resources.ResourceHelperImpl
import app.aaps.implementation.scenes.SceneExpiryWorker
import app.aaps.implementation.utils.fabric.FabricPrivacyImpl
import app.aaps.plugins.aps.loop.runningMode.RunningModeExpiryScheduler
import app.aaps.plugins.aps.loop.runningMode.RunningModeExpiryWorker
import app.aaps.plugins.aps.loop.runningMode.RunningModeReconciler
import app.aaps.plugins.constraints.bgQualityCheck.BgQualityCheckPlugin
import app.aaps.plugins.constraints.objectives.objectives.Objective
import app.aaps.plugins.sync.smsCommunicator.SmsCommunicatorPlugin
import app.aaps.ui.compose.history.HistoryViewModel
import app.aaps.ui.widget.WidgetUpdaterImpl
import app.aaps.wear.engine.CompanionLink
import app.aaps.wear.engine.EngineBuildInfo
import app.aaps.wear.engine.EngineKeepAlive
import app.aaps.wear.engine.EngineReporter
import app.aaps.wear.engine.medtrum.MedtrumWatchLink
import app.aaps.wear.engine.source.WatchG7SourcePlugin
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelMultibindings
import kotlinx.coroutines.CoroutineScope
import kotlin.reflect.KClass

/**
 * The AAPS engine on the watch: the watch's own Metro root for `AppScope`, built from the same modules as
 * the phone's `AppRootGraph`, the same way `IosAppGraph` and `DesktopAppGraph` are.
 *
 * Built only while the watch holds the pump (see `WatchEngine`). The companion graph of the watch app has
 * its own scope (`WearScope`), so the two never mix.
 */
@DependencyGraph(
    AppScope::class,
    // Phone features the watch does not have. Left out, so they are never built.
    excludes = [
        HistoryViewModel::class, // the history browser
        SmsCommunicatorPlugin::class, // SMS commands
        FabricPrivacyImpl::class, // Firebase, which the watch does not set up: WatchFabricPrivacy instead
        WidgetUpdaterImpl::class // home screen widgets, which the watch does not have: WatchWidgetUpdater instead
    ]
)
interface EngineGraph : MetroViewModelMultibindings {

    val contributedPlugins: Map<Int, PluginBase>

    @APS
    val contributedApsPlugins: Map<Int, PluginBase>

    @NotNSClient
    val contributedNotNsClientPlugins: Map<Int, PluginBase>

    @Multibinds(allowEmpty = true)
    @PumpDriver
    val contributedPumpDriverPlugins: Map<Int, PluginBase>

    @Multibinds(allowEmpty = true)
    @FeatureMemberInjectors
    val contributedMemberInjectors: Map<KClass<*>, MembersInjector<*>>

    val config: Config
    val configBuilder: ConfigBuilder
    val pluginStore: PluginStore
    val preferences: Preferences
    val rxBus: RxBus
    val aapsLogger: AAPSLogger
    val medtrumWatchLink: MedtrumWatchLink
    val watchG7Source: WatchG7SourcePlugin

    // What `WatchEngine.start` runs, as `MainApp` does on the phone
    val resourceHelperImpl: ResourceHelperImpl
    val runningModeReconciler: RunningModeReconciler

    // What a handover snapshot needs at the start
    val preferenceImportApplier: PreferenceImportApplier
    val profileFunction: ProfileFunction
    val loop: Loop
    val activePlugin: ActivePlugin
    val pumpSync: PumpSync
    val constraintsChecker: ConstraintsChecker
    val engineReporter: EngineReporter
    val engineKeepAlive: EngineKeepAlive
    val context: Context

    /**
     * The phone's workers the engine runs, as `AppWorkersGraph` lists them on the phone. Not the keep alive:
     * WorkManager is held back while the watch is idle, so the engine runs its work itself (`EngineKeepAlive`).
     */
    val workerCreators: Map<KClass<out ListenableWorker>, MetroWorkerCreator>
    val runningModeExpiryScheduler: RunningModeExpiryScheduler

    @Provides
    @IntoMap
    @WorkerKey(RunningModeExpiryWorker::class)
    fun bindRunningModeExpiryWorker(factory: RunningModeExpiryWorker.Factory): MetroWorkerCreator = factory

    @Provides
    @IntoMap
    @WorkerKey(SceneExpiryWorker::class)
    fun bindSceneExpiryWorker(factory: SceneExpiryWorker.Factory): MetroWorkerCreator = factory

    @ApplicationScope val appScope: CoroutineScope

    /** The objectives, in order. Same as on the phone. */
    @Provides
    fun objectivesList(objectives: Map<Int, Objective>): List<Objective> =
        objectives.toList().sortedBy { it.first }.map { it.second }

    @Provides fun bgQualityCheck(plugin: BgQualityCheckPlugin): BgQualityCheck = plugin

    @DependencyGraph.Factory
    fun interface Factory {

        fun create(
            @Provides @ApplicationScope appScope: CoroutineScope,
            @Provides context: Context,
            @Provides memberInjector: MetroMemberInjector,
            @Provides databaseConfig: DatabaseConfig,
            @Provides buildInfo: EngineBuildInfo,
            @Provides companion: CompanionLink,
            // The wizard and running-mode objects, as on the phone. Metro may call it unused; it is not.
            @Includes coreObjects: CoreObjectsGraph
        ): EngineGraph
    }
}

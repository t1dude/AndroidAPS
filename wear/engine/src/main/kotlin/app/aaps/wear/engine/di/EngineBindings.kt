package app.aaps.wear.engine.di

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.interfaces.constraints.Objectives
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.L
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.workflow.CalculationSignalsEmitter
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.receivers.DataInbox
import app.aaps.plugins.constraints.objectives.ObjectivesPlugin
import app.aaps.plugins.main.iob.iobCobCalculator.IobCobCalculatorPlugin
import app.aaps.shared.impl.logging.AAPSLoggerProduction
import app.aaps.shared.impl.logging.LImpl
import app.aaps.shared.impl.rx.AapsSchedulersImpl
import app.aaps.shared.impl.rx.bus.RxBusImpl
import app.aaps.shared.impl.sharedPreferences.SPImpl
import app.aaps.shared.impl.utils.DateUtilImpl
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope

/**
 * What the phone app binds itself (`AppAndroidBindings`, `SharedImplBindings`, `AppCoroutineBindings`,
 * `MainPluginsBindings` in `:app`), for the engine on the watch. Kept the same as there, except:
 *
 * - **Its own settings file.** The companion uses the default one; engine and companion settings must never
 *   mix (a phone settings import on the watch would otherwise change the companion too).
 */
@ContributesTo(AppScope::class)
@BindingContainer
object EngineBindings {

    const val PREFERENCES_FILE = "aaps_engine"

    @Provides
    @SingleIn(AppScope::class)
    fun sharedPreferences(context: Context): SharedPreferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    @Provides
    @SingleIn(AppScope::class)
    fun dataInbox(context: Context): DataInbox = DataInbox(context)

    @Provides
    fun textResolver(rh: ResourceHelper): TextResolver = rh

    @Provides
    fun unqualifiedAppScope(@ApplicationScope scope: CoroutineScope): CoroutineScope = scope

    @Provides
    @SingleIn(AppScope::class)
    fun sp(sharedPreferences: SharedPreferences, context: Context): SP = SPImpl(sharedPreferences, context)

    @Provides
    @SingleIn(AppScope::class)
    fun keyValueStore(sp: SP): KeyValueStore = sp

    @Provides
    @SingleIn(AppScope::class)
    fun l(preferences: () -> Preferences): L = LImpl(preferences)

    @Provides
    @SingleIn(AppScope::class)
    fun aapsLogger(l: L): AAPSLogger = AAPSLoggerProduction(l)

    @Provides
    @SingleIn(AppScope::class)
    fun dateUtil(context: Context): DateUtil = DateUtilImpl(context)

    @Provides
    @SingleIn(AppScope::class)
    fun rxBus(aapsLogger: AAPSLogger): RxBus = RxBusImpl(aapsLogger)

    @Provides
    @SingleIn(AppScope::class)
    fun aapsSchedulers(): AapsSchedulers = AapsSchedulersImpl()

    @Suppress("LongParameterList")
    @Provides
    @SingleIn(AppScope::class)
    fun iobCobCalculatorPlugin(
        aapsLogger: AAPSLogger,
        rxBus: RxBus,
        preferences: Preferences,
        rh: TextResolver,
        profileFunction: ProfileFunction,
        activePlugin: ActivePlugin,
        dateUtil: DateUtil,
        persistenceLayer: PersistenceLayer,
        overviewData: OverviewData,
        calculationWorkflow: CalculationWorkflow,
        decimalFormatter: DecimalFormatter,
        processedTbrEbData: ProcessedTbrEbData,
        signals: CalculationSignalsEmitter,
        notificationManager: NotificationManager,
        cache: () -> OverviewDataCache
    ): IobCobCalculatorPlugin = IobCobCalculatorPlugin(
        aapsLogger, rxBus, preferences, rh, profileFunction, activePlugin, dateUtil, persistenceLayer,
        overviewData, calculationWorkflow, decimalFormatter, processedTbrEbData, signals, notificationManager
    ) { cache() }

    @Provides
    fun iobCobCalculator(plugin: IobCobCalculatorPlugin): IobCobCalculator = plugin

    @Provides
    @IntoMap
    @IntKey(10)
    fun iobCobCalculatorEntry(plugin: IobCobCalculatorPlugin): PluginBase = plugin

    @Provides
    fun objectives(plugin: ObjectivesPlugin): Objectives = plugin
}

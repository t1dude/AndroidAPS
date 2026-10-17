package app.aaps.wear.engine

import android.content.Context
import android.content.Intent
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.di.MetroMemberInjector
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.rx.events.EventAppInitialized
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.objects.di.CoreObjectsGraph
import app.aaps.database.di.DatabaseConfig
import app.aaps.pump.medtrum.keys.MedtrumBooleanNonKey
import app.aaps.pump.medtrum.keys.MedtrumStringNonKey
import app.aaps.wear.engine.di.EngineBindings
import app.aaps.wear.engine.di.EngineGraph
import app.aaps.wear.engine.di.EngineStringOwners
import app.aaps.wear.engine.medtrum.MedtrumWatchActivity
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The engine's entry point for the rest of the watch app (the companion).
 *
 * The engine's graph is built only when it is needed: when the phone hands over a pump session, when the
 * watch still holds one after a restart, or when one of the engine's own screens or receivers starts. In
 * companion mode nothing of the engine runs (Tim's lesson from Watch-APS: an engine kept alive without a
 * pump drains the battery). What the companion asks before that ([hasSession]) is read from the engine's
 * settings file directly, without building anything.
 */
object WatchEngine {

    private lateinit var context: Context
    private lateinit var memberInjector: MetroMemberInjector
    private lateinit var buildInfo: EngineBuildInfo
    private lateinit var companion: CompanionLink

    @Volatile private var graph: EngineGraph? = null

    /** Called once by the companion at app start. Builds nothing. */
    fun install(context: Context, memberInjector: MetroMemberInjector, buildInfo: EngineBuildInfo, companion: CompanionLink) {
        this.context = context.applicationContext
        this.memberInjector = memberInjector
        this.buildInfo = buildInfo
        this.companion = companion
        // The watch held a pump session when the app stopped: carry on with it
        if (hasSession()) start { it.medtrumWatchLink.start() }
    }

    /** True while the engine holds a pump session. Reads the settings file only. */
    fun hasSession(): Boolean {
        val prefs = context.getSharedPreferences(EngineBindings.PREFERENCES_FILE, Context.MODE_PRIVATE)
        return prefs.getString(MedtrumStringNonKey.SnInput.key, "").orEmpty().isNotEmpty() &&
            !prefs.getBoolean(MedtrumBooleanNonKey.SessionMovedAway.key, false)
    }

    /** The engine's screen, for the companion's menu. */
    fun screenIntent(context: Context): Intent = Intent(context, MedtrumWatchActivity::class.java)

    /** A message from the phone that is the engine's business. False when it is not. */
    fun onMessageFromPhone(data: EventData): Boolean = when (data) {
        is EventData.MedtrumSessionToWatch -> {
            start {
                it.medtrumWatchLink.start()
                it.medtrumWatchLink.receiveSession(data.session)
            }
            true
        }

        else                               -> false
    }

    /**
     * New readings from the watch's own G7 (companion). Kept only while the engine runs: in companion mode
     * the engine is not built for them.
     */
    fun onGlucose(readings: List<GV>) {
        if (!started) return
        val graph = graph
        graph?.appScope?.launch { graph.watchG7Source.store(readings) }
    }

    /** Fills the fields of an engine class that Android built (screen, service, receiver, worker). */
    @Suppress("UNCHECKED_CAST")
    fun injectMembers(target: Any): Boolean {
        val injector = graph().contributedMemberInjectors[target::class] ?: return false
        (injector as MembersInjector<Any>).injectMembers(target)
        return true
    }

    /**
     * Starts the engine once, as `MainApp` starts the phone: the plugin list, then the plugins the settings
     * enable, then the running mode check. [then] runs after that, on the engine's scope.
     */
    private fun start(then: (EngineGraph) -> Unit) {
        val graph = graph()
        graph.appScope.launch {
            startMutex.withLock {
                if (!started) {
                    EngineStringOwners.registerAll()
                    graph.resourceHelperImpl.start()
                    val (plugins, problems) = mergePlugins(
                        listOf(
                            "Metro" to graph.contributedPlugins,
                            "Metro @APS" to graph.contributedApsPlugins,
                            "Metro @PumpDriver" to graph.contributedPumpDriverPlugins,
                            "Metro @NotNSClient" to graph.contributedNotNsClientPlugins
                        )
                    )
                    problems.forEach { graph.aapsLogger.error(LTag.CORE, "Engine plugin list: $it") }
                    graph.pluginStore.plugins = EnginePlugins.forWatch(plugins)
                    val pluginsStarted = graph.configBuilder.initialize()
                    if (withTimeoutOrNull(PLUGIN_START_WAIT_MS) { pluginsStarted.joinAll() } == null)
                        graph.aapsLogger.warn(LTag.CORE, "Engine: plugins did not finish starting within $PLUGIN_START_WAIT_MS ms")
                    graph.runningModeReconciler.start()
                    graph.config.initCompleted()
                    graph.rxBus.send(EventAppInitialized())
                    graph.aapsLogger.info(LTag.CORE, "Engine started with ${graph.pluginStore.plugins.size} plugins")
                    started = true
                }
            }
            then(graph)
        }
    }

    /** The plugin buckets in display order, as `mergePlugins` in `:app` merges them. A clash is reported, not hidden. */
    private fun mergePlugins(sources: List<Pair<String, Map<Int, PluginBase>>>): Pair<List<PluginBase>, List<String>> {
        val problems = mutableListOf<String>()
        val merged = LinkedHashMap<Int, PluginBase>()
        val owner = mutableMapOf<Int, String>()
        for ((name, plugins) in sources) {
            for ((key, plugin) in plugins) {
                owner[key]?.let { problems += "order key $key used by both $it and $name (${plugin.javaClass.simpleName})" }
                merged[key] = plugin
                owner[key] = name
            }
        }
        return merged.toList().sortedBy { it.first }.map { it.second } to problems
    }

    @Synchronized
    private fun graph(): EngineGraph = graph ?: createGraphFactory<EngineGraph.Factory>()
        .create(
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            context = context,
            memberInjector = memberInjector,
            databaseConfig = DatabaseConfig(inMemory = false, fileName = DATABASE_FILE),
            buildInfo = buildInfo,
            companion = companion,
            coreObjects = CoreObjectsGraph
        )
        .also { graph = it }

    /** Its own database file, so it is never taken for anything else on the watch. */
    private const val DATABASE_FILE = "aaps_engine.db"

    /** As on the phone: a plugin that will not start must not hold the engine up for ever. */
    private const val PLUGIN_START_WAIT_MS = 60_000L

    private val startMutex = Mutex()
    @Volatile private var started = false
}

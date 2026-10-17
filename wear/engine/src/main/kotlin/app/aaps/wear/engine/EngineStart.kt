package app.aaps.wear.engine

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.Prefs
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.rx.events.EventAppInitialized
import app.aaps.implementation.receivers.KeepAliveWorker
import app.aaps.wear.engine.di.EngineGraph
import app.aaps.wear.engine.di.EngineStringOwners
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull

/** As on the phone: a plugin that will not start must not hold the engine up for ever. */
private const val PLUGIN_START_WAIT_MS = 60_000L

/**
 * Starts the engine, as `MainApp` starts the phone: the plugin list without what the watch does not run, the
 * phone's [settings] if a snapshot brought them (keeping the engine's own pump), the plugins the settings
 * enable, then the running mode check. Called once per process by `WatchEngine`; kept here so a test can run
 * the same start on a graph of its own.
 */
internal suspend fun EngineGraph.startEngine(settings: Map<String, String>?) {
    EngineStringOwners.registerAll()
    resourceHelperImpl.start()
    val (plugins, problems) = mergePlugins(
        listOf(
            "Metro" to contributedPlugins,
            "Metro @APS" to contributedApsPlugins,
            "Metro @PumpDriver" to contributedPumpDriverPlugins,
            "Metro @NotNSClient" to contributedNotNsClientPlugins
        )
    )
    problems.forEach { aapsLogger.error(LTag.CORE, "Engine plugin list: $it") }
    pluginStore.plugins = EnginePlugins.forWatch(plugins)
    // After the plugin list, which the import reads to find each setting's key; before the plugins start,
    // so they start with the phone's choices
    if (settings != null) {
        val outcome = preferenceImportApplier.apply(Prefs(settings, emptyMap()), keepPumpSettings = true)
        aapsLogger.info(LTag.CORE, "Engine: phone settings applied: $outcome")
        medtrumWatchLink.note("Engine: ${settings.size} phone settings taken in")
    }
    val pluginsStarted = configBuilder.initialize()
    if (withTimeoutOrNull(PLUGIN_START_WAIT_MS) { pluginsStarted.joinAll() } == null)
        aapsLogger.warn(LTag.CORE, "Engine: plugins did not finish starting within $PLUGIN_START_WAIT_MS ms")
    runningModeReconciler.start()
    runningModeExpiryScheduler.start()
    config.initCompleted()
    rxBus.send(EventAppInitialized())
    aapsLogger.info(LTag.CORE, "Engine started with ${pluginStore.plugins.size} plugins")
    // A line for the test log only: it must never stop the start
    val pump = runCatching { activePlugin.activePump.selectedActivePump().javaClass.simpleName }.getOrDefault("none")
    val glucose = runCatching { activePlugin.activeBgSource.javaClass.simpleName }.getOrDefault("none")
    medtrumWatchLink.note("Engine started: ${pluginStore.plugins.count { it.isEnabled() }} of ${pluginStore.plugins.size} plugins on, pump $pump, glucose $glucose, engineering mode ${config.isEngineeringMode()}")
    engineReporter.start()
    // As MainApp does on the phone: the pump gets the running profile, its status is read, the pump
    // unreachable alarm is checked. Every 5 minutes, while the engine runs (cancelled at hand back).
    KeepAliveWorker.schedule(context)
}

/** The plugin buckets in display order, as `mergePlugins` in `:app` merges them. A clash is reported, not hidden. */
internal fun mergePlugins(sources: List<Pair<String, Map<Int, PluginBase>>>): Pair<List<PluginBase>, List<String>> {
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

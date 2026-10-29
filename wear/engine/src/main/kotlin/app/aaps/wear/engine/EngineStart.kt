package app.aaps.wear.engine

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.handover.HandoverSnapshot
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.Prefs
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.rx.events.EventAppInitialized
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.pump.medtrum.MedtrumPlugin
import app.aaps.pump.virtual.VirtualPumpPlugin
import app.aaps.wear.engine.di.EngineGraph
import app.aaps.wear.engine.di.EngineStringOwners
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull

/** As on the phone: a plugin that will not start must not hold the engine up for ever. */
private const val PLUGIN_START_WAIT_MS = 60_000L

/**
 * Starts the engine, as `MainApp` starts the phone: the plugin list without what the watch does not run, the
 * phone's [settings] if a snapshot brought them (keeping the engine's own pump), the [pumpSession] if one came
 * with the phone's [pumpIdentity] ([takePumpIdentity]), the engine's pump ([selectPump]), the plugins the settings enable, then the running mode check. Called
 * once per process by `WatchEngine`; kept here so a test can run the same start on a graph of its own.
 *
 * [setRunningMode] runs after init has completed and before the running mode check. A snapshot brings the
 * mode the phone set for the move out (loop disabled), and for that mode the check cancels the temporary
 * basal the pump runs. So the engine sets its own mode first. When [setRunningMode] returns false, the
 * check does not start: the engine sends no pump command for a mode nobody chose on the watch.
 */
internal suspend fun EngineGraph.startEngine(
    settings: Map<String, String>?,
    pumpSession: String? = null,
    pumpIdentity: HandoverSnapshot.PumpIdentity? = null,
    setRunningMode: suspend () -> Boolean = { true }
) {
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
    // After the settings, which leave the engine's pump settings as they were; before the plugins start, so
    // the Medtrum driver starts with its patch
    if (pumpSession != null) {
        medtrumWatchLink.takeIn(pumpSession)
        takePumpIdentity(pumpIdentity)
    }
    selectPump(medtrum = medtrumWatchLink.holdsSession)
    val pluginsStarted = configBuilder.initialize()
    if (withTimeoutOrNull(PLUGIN_START_WAIT_MS) { pluginsStarted.joinAll() } == null)
        aapsLogger.warn(LTag.CORE, "Engine: plugins did not finish starting within $PLUGIN_START_WAIT_MS ms")
    runningModeExpiryScheduler.start()
    // Before the mode: there is no profile until init has completed (ProfileFunction), and the mode needs
    // one. Nothing runs the loop before EventAppInitialized: the engine takes readings only after this start.
    config.initCompleted()
    if (setRunningMode()) runningModeReconciler.start()
    else {
        aapsLogger.warn(LTag.CORE, "Engine: running mode not set, the running mode check does not start")
        medtrumWatchLink.note("Engine: running mode not set, the running mode check does not start")
    }
    rxBus.send(EventAppInitialized())
    aapsLogger.info(LTag.CORE, "Engine started with ${pluginStore.plugins.size} plugins")
    // A line for the test log only: it must never stop the start
    val pump = runCatching { activePlugin.activePump.selectedActivePump().javaClass.simpleName }.getOrDefault("none")
    val glucose = runCatching { activePlugin.activeBgSource.javaClass.simpleName }.getOrDefault("none")
    medtrumWatchLink.note("Engine started: ${pluginStore.plugins.count { it.isEnabled() }} of ${pluginStore.plugins.size} plugins on, pump $pump, glucose $glucose, engineering mode ${config.isEngineeringMode()}")
    engineReporter.start()
    // As MainApp does on the phone: the pump gets the running profile, its status is read, the pump
    // unreachable alarm is checked. On every CGM reading and from an alarm, not WorkManager (see EngineKeepAlive).
    engineKeepAlive.start()
}

/**
 * The pump the engine takes records from is the phone's (see [HandoverSnapshot.PumpIdentity]), whatever an
 * earlier run registered. Without one from the phone none is registered, so the pump's first record registers it.
 */
private fun EngineGraph.takePumpIdentity(identity: HandoverSnapshot.PumpIdentity?) {
    if (identity == null) {
        preferences.remove(StringNonKey.ActivePumpType)
        preferences.remove(StringNonKey.ActivePumpSerialNumber)
        preferences.remove(LongNonKey.ActivePumpChangeTimestamp)
        medtrumWatchLink.note("Engine: the phone sent no pump identity, the pump's first record registers it")
        return
    }
    preferences.put(StringNonKey.ActivePumpType, identity.type)
    preferences.put(StringNonKey.ActivePumpSerialNumber, identity.serial)
    preferences.put(LongNonKey.ActivePumpChangeTimestamp, identity.since)
    medtrumWatchLink.note("Engine: records taken from ${identity.type} ${identity.serial}, as on the phone")
}

/**
 * The engine's pump: the Medtrum driver while the watch holds a patch session, the virtual pump otherwise (a
 * test run without a patch). Set in the config builder's own settings before the plugins start, whatever the
 * phone's settings or an earlier run left there, and only one of them.
 */
private fun EngineGraph.selectPump(medtrum: Boolean) {
    val chosen = if (medtrum) MedtrumPlugin::class else VirtualPumpPlugin::class
    pluginStore.plugins.filter { it.getType() == PluginType.PUMP }.forEach { plugin ->
        preferences.put(BooleanComposedKey.ConfigBuilderEnabled, "${PluginType.PUMP.name}_${plugin::class.simpleName}", value = plugin::class == chosen)
    }
    aapsLogger.info(LTag.CORE, "Engine: pump ${chosen.simpleName}")
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

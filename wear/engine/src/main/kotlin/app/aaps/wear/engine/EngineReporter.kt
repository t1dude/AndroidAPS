package app.aaps.wear.engine

import android.content.Context
import android.os.BatteryManager
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.plugins.aps.loop.events.EventLoopSetLastRunGui
import app.aaps.wear.engine.medtrum.MedtrumWatchLink
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes what the engine's loop does to the watch's test log, which reaches the phone: one line per loop
 * run (what it suggests, or why it did not run), with the watch's battery level, for the engine test on the
 * watch (T2: a steady cycle, the battery over a day). The watch's own log is too short to hold a test.
 *
 * The loop can also stop without telling anyone (a few early returns in `LoopPlugin.invoke` send no event).
 * So after each new glucose reading the engine stored, it waits for a loop result, and when none comes it
 * writes which of the loop's own preconditions does not hold.
 */
@SingleIn(AppScope::class)
@Inject
class EngineReporter(
    private val context: Context,
    private val rxBus: RxBus,
    private val loop: Loop,
    private val link: MedtrumWatchLink,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val commandQueue: CommandQueue,
    private val iobCobCalculator: IobCobCalculator,
    private val ch: ConcentrationHelper
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var lastReportedRun = 0L

    fun start() {
        if (started) return
        started = true
        scope.launch { rxBus.toFlow(EventLoopUpdateGui::class).collect { reportRun() } }
        scope.launch { rxBus.toFlow(EventLoopSetLastRunGui::class).collect { link.note("Loop: ${it.text} (battery ${battery()} %)") } }
        scope.launch { link.note("Loop check at start: ${preconditions()}") }
    }

    /** A new glucose reading went into the engine's database: the loop should run on it within a minute. */
    fun onReadingStored(timestamp: Long, mgdl: Double) {
        if (!started) return
        val runBefore = loop.lastRun?.lastAPSRun ?: 0L
        scope.launch {
            delay(LOOP_WAIT_MS)
            if ((loop.lastRun?.lastAPSRun ?: 0L) == runBefore)
                link.note("No loop result for the reading of ${time(timestamp)} (${mgdl.toInt()} mg/dl): ${preconditions()}")
        }
    }

    private suspend fun reportRun() {
        val run = loop.lastRun ?: return
        if (run.lastAPSRun == lastReportedRun) return
        lastReportedRun = run.lastAPSRun
        val result = run.constraintsProcessed ?: run.request
        val suggestion = result?.let {
            if (it.isChangeRequested()) "temp basal ${"%.2f".format(Locale.US, it.rate)} U/h for ${it.duration} min, SMB ${"%.2f".format(Locale.US, it.smb)} U"
            else "no change"
        } ?: "no result"
        link.note("Loop ran (${run.source ?: "?"}): $suggestion (battery ${battery()} %)")
    }

    /** The conditions `LoopPlugin.invoke` checks before the algorithm runs, as they are now. */
    private suspend fun preconditions(): String {
        val aps = activePlugin.activeAPS
        val bg = iobCobCalculator.ads.actualBg()
        return listOf(
            "mode ${loop.runningMode()}",
            "loop ${if ((loop as? PluginBase)?.isEnabled() == true) "on" else "off"}",
            "profile ${if (profileFunction.getProfile() != null && profileFunction.isProfileValid("EngineReporter")) profileFunction.getProfileName() else "none or not valid"}",
            "queue ${if (commandQueue.size() == 0 && commandQueue.performing() == null) "empty" else "busy"}",
            "pump ${runCatching { activePlugin.activePump.selectedActivePump().javaClass.simpleName }.getOrDefault("none")} " +
                "basal ${runCatching { "%.2f".format(Locale.US, ch.fromPump(activePlugin.activePump.baseBasalRate)) }.getOrDefault("?")} U/h",
            "APS ${(aps as? PluginBase)?.let { "${it.javaClass.simpleName} ${if (it.isEnabled()) "on" else "off"}" } ?: "none"}",
            "glucose for the loop ${bg?.let { "${it.value.toInt()} mg/dl at ${time(it.timestamp)}" } ?: "none (no recent reading)"}"
        ).joinToString(", ")
    }

    private fun time(at: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

    private fun battery(): Int = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    private companion object {

        /** The calculation and the loop run take seconds; a minute is plenty. */
        const val LOOP_WAIT_MS = 60_000L
    }
}

package app.aaps.wear.engine

import android.content.Context
import android.os.BatteryManager
import app.aaps.core.interfaces.aps.Loop
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
import kotlinx.coroutines.launch

/**
 * Writes what the engine's loop does to the watch's test log, which reaches the phone: one line per loop
 * run (what it suggests, or why it did not run), with the watch's battery level, for the engine test on the
 * watch (T2: a steady cycle, the battery over a day). The watch's own log is too short to hold a test.
 */
@SingleIn(AppScope::class)
@Inject
class EngineReporter(
    private val context: Context,
    private val rxBus: RxBus,
    private val loop: Loop,
    private val link: MedtrumWatchLink
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var lastReportedRun = 0L

    fun start() {
        if (started) return
        started = true
        scope.launch { rxBus.toFlow(EventLoopUpdateGui::class).collect { reportRun() } }
        scope.launch { rxBus.toFlow(EventLoopSetLastRunGui::class).collect { link.note("Loop: ${it.text} (battery ${battery()} %)") } }
    }

    private suspend fun reportRun() {
        val run = loop.lastRun ?: return
        if (run.lastAPSRun == lastReportedRun) return
        lastReportedRun = run.lastAPSRun
        val result = run.constraintsProcessed ?: run.request
        val suggestion = result?.let {
            if (it.isChangeRequested()) "temp basal ${"%.2f".format(it.rate)} U/h for ${it.duration} min, SMB ${"%.2f".format(it.smb)} U"
            else "no change"
        } ?: "no result"
        link.note("Loop ran (${run.source ?: "?"}): $suggestion (battery ${battery()} %)")
    }

    private fun battery(): Int = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
}

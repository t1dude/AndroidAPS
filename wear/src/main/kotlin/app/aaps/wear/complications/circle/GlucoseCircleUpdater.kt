package app.aaps.wear.complications.circle

import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.complications.cwf.CwfComplicationUpdater
import app.aaps.wear.complications.cwf.CwfFaceComplication
import app.aaps.wear.data.ComplicationDataRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the "min ago" text in the glucose circle picture up to date.
 *
 * New data already reaches the circle through `DataHandlerWear`, like every other complication. What
 * nothing else does is redraw it as the reading ages: `UPDATE_PERIOD_SECONDS` was measured being
 * honoured only every 1.5 to 6 minutes (see [CwfComplicationUpdater]), so the age would lag by
 * minutes. A pushed update is delivered at once, so this asks for one exactly when the shown minute
 * count changes - once a minute, on the reading's own grid rather than the clock's.
 *
 * Also refreshes the picture when the watch wakes, because it may be minutes old by then, and the
 * always-on readout on every mode change, because its tap action depends on the mode.
 *
 * Only while a face shows the circle, judged the same way as the Custom watchface: a request within
 * the last ten minutes. Without one nothing is drawn or asked for.
 */
@SingleIn(AppScope::class)
@Inject
class GlucoseCircleUpdater(
    private val context: Context,
    private val complicationDataRepository: ComplicationDataRepository,
    private val aapsLogger: AAPSLogger
) {

    companion object {

        /** How often an idle loop looks whether a face has asked */
        private const val IDLE_POLL_MS = 5_000L

        /** Just past the minute boundary, so the redraw lands on the new count and not before it */
        private const val BOUNDARY_SLACK_MS = 200L

        /** Longest single wait, so new data or a clock change is picked up within a minute anyway */
        private const val MAX_WAIT_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val circle by lazy {
        ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, GlucoseCircleComplication::class.java))
    }
    private val ambient by lazy {
        ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, GlucoseCircleAmbientComplication::class.java))
    }

    private fun hasDemand(): Boolean =
        CwfComplicationUpdater.demandActive(GlucoseCircleComplication.lastRequestMs, System.currentTimeMillis())

    private var tickJob: Job? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        private var wasAmbient: Boolean? = null
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val dozing = CwfFaceComplication.isAmbient(context)
            if (dozing == wasAmbient) return
            wasAmbient = dozing
            if (!hasDemand()) return
            aapsLogger.debug(LTag.WEAR, "GlucoseCircleUpdater: ambient=$dozing")
            // The picture only on waking: it is hidden while dozing. On a Galaxy Watch the picture
            // sometimes stayed visible in ambient. A new picture that lands just after the watch
            // dozed is the likely cause.
            if (!dozing) circle.requestUpdateAll()
            ambient.requestUpdateAll()
            // The tick loop may be asleep on a wait computed before the watch froze
            startTicks()
        }
    }

    private fun startTicks() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                if (!hasDemand()) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                val timeStamp = complicationDataRepository.complicationData.first().bgData.timeStamp
                val now = System.currentTimeMillis()
                val wait =
                    if (timeStamp == 0L) MAX_WAIT_MS
                    else (GlucoseCircleComplication.nextAgeChangeMs(timeStamp, now) - now + BOUNDARY_SLACK_MS).coerceIn(0, MAX_WAIT_MS)
                delay(wait)
                // Hidden while dozing, and our process is frozen then anyway
                if (!CwfFaceComplication.isAmbient(context)) circle.requestUpdateAll()
            }
        }
    }

    fun start() {
        context.getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        startTicks()
        // One knock, in case the face is already showing: its answer is the demand the loop waits for
        circle.requestUpdateAll()
    }
}

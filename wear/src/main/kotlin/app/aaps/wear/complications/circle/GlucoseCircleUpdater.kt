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
 * Asks for a new circle picture each time the age in it ("2 min ago") changes. The system's own
 * update period is too slow for that.
 *
 * Also asks for a new picture when the watch wakes, and for new ambient text on every mode change,
 * because the tap action of that text depends on the mode.
 *
 * Does nothing unless a face asked for the picture in the last ten minutes.
 */
@SingleIn(AppScope::class)
@Inject
class GlucoseCircleUpdater(
    private val context: Context,
    private val complicationDataRepository: ComplicationDataRepository,
    private val aapsLogger: AAPSLogger
) {

    companion object {

        /** Wait between checks while no face shows the circle */
        private const val IDLE_POLL_MS = 5_000L

        /** Added to the wait, so the picture is drawn just after the age changes */
        private const val BOUNDARY_SLACK_MS = 200L

        /** Longest wait */
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
            // Not when the watch dims: the picture is hidden then, and on a Galaxy Watch a new picture
            // that arrived just after dimming stayed visible in ambient mode.
            if (!dozing) circle.requestUpdateAll()
            ambient.requestUpdateAll()
            // The current wait was computed before the mode changed
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
                // Hidden in ambient mode
                if (!CwfFaceComplication.isAmbient(context)) circle.requestUpdateAll()
            }
        }
    }

    fun start() {
        context.getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        startTicks()
        // If the face is already showing, its request starts the loop
        circle.requestUpdateAll()
    }
}

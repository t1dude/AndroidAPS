package app.aaps.wear.engine.absent

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.widget.WidgetUpdater
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * There is no home screen widget to update on the watch. The phone's `WidgetUpdaterImpl` asks the launcher
 * for its widgets, and the watch has no widget manager, so every update failed with an error in the test
 * log. Nothing is lost by doing nothing here, as on desktop (`DesktopWidgetUpdater`).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WatchWidgetUpdater(
    private val aapsLogger: AAPSLogger
) : WidgetUpdater {

    override fun update(from: String) {
        aapsLogger.debug(LTag.CORE, "No watch widget to update (from $from)")
    }
}

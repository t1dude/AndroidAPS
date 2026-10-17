package app.aaps.wear.engine.absent

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Crash and usage reporting, which the engine on the watch does not have. The phone's `FabricPrivacyImpl`
 * goes to Firebase, which the watch app does not set up, and some of its calls throw then. Here everything
 * goes to the engine's log instead, so a reported exception is kept and never crashes the engine.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WatchFabricPrivacy(
    private val aapsLogger: AAPSLogger
) : FabricPrivacy {

    override fun setUserProperty(key: String, value: String) = Unit

    override fun logCustom(event: String) = Unit

    override fun logCustom(name: String, params: Map<String, Long>) = Unit

    override fun logMessage(message: String) {
        aapsLogger.info(LTag.CORE, "Report: $message")
    }

    override fun logException(throwable: Throwable) {
        aapsLogger.error(LTag.CORE, "Reported exception", throwable)
    }

    override fun fabricEnabled(): Boolean = false

    override fun logWearException(wearException: EventData.WearException) {
        aapsLogger.error(LTag.WEAR, "Watch exception from ${wearException.board} ${wearException.model}")
    }
}

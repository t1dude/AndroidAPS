package app.aaps.wear.engine.absent

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * SMS remote control, which the engine on the watch does not have (the SMS plugin is left out of
 * `EngineGraph`). Same idea as `IosSmsCommunicator`: other code injects [SmsCommunicator] whether or not
 * the plugin is on, so the graph needs an answer. [isEnabled] is false and callers check it first, so SMS
 * is visibly absent rather than present and failing. A caller that sends anyway is logged, not crashed.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class WatchSmsCommunicator(
    private val aapsLogger: AAPSLogger
) : SmsCommunicator {

    override var messages: ArrayList<Sms> = ArrayList()

    override fun isEnabled(): Boolean = false

    override fun sendNotificationToAllNumbers(text: String): Boolean {
        aapsLogger.debug(LTag.SMS, "The watch cannot send SMS, dropping notification to all numbers")
        return false
    }

    override fun sendSMS(sms: Sms): Boolean {
        aapsLogger.debug(LTag.SMS, "The watch cannot send SMS, dropping message")
        return false
    }
}

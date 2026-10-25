package app.aaps.wear.engine

import app.aaps.core.interfaces.rx.weardata.EventData

/**
 * The engine's way out to the rest of the watch app (the companion), which owns the link to the phone and
 * the watch's screens. The engine has its own message bus, separate from the companion's.
 */
interface CompanionLink {

    /** Sends [data] to the phone, over the companion's link. */
    fun sendToPhone(data: EventData)
}

package app.aaps.wear.engine

import app.aaps.core.interfaces.rx.weardata.EventData
import java.io.File

/**
 * The engine's way out to the rest of the watch app (the companion), which owns the link to the phone and
 * the watch's screens. The engine has its own message bus, separate from the companion's.
 */
interface CompanionLink {

    /** Sends [data] to the phone, over the companion's link. */
    fun sendToPhone(data: EventData)

    /**
     * Sends [file] to the phone over a Wear channel on [channelPath], for what is too big for a message.
     * Returns when it was sent: true when the whole file went out.
     */
    suspend fun sendFileToPhone(file: File, channelPath: String): Boolean
}

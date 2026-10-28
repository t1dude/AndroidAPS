package app.aaps.core.interfaces.rx.events

import kotlinx.coroutines.CompletableDeferred

/**
 * Sends the file at [path] from the watch to the phone, over a Wear channel on [channelPath]. For files too
 * big for a message. [done] is completed with true when the whole file was sent, false when it was not, so
 * the sender can wait before it ends the app process.
 */
class EventWearToMobileFile(val path: String, val channelPath: String, val done: CompletableDeferred<Boolean>) : Event()

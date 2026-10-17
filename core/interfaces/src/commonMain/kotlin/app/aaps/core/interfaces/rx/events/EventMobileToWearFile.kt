package app.aaps.core.interfaces.rx.events

/** Sends the file at [path] to the watch, over a Wear channel on [channelPath]. For files too big for a message. */
class EventMobileToWearFile(val path: String, val channelPath: String) : Event()

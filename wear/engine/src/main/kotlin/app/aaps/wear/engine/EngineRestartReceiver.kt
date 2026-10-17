package app.aaps.wear.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the watch app back after `WatchEngine` stopped it to take in a snapshot or to stop the engine at
 * hand back. Receiving the alarm is enough: Android starts the app process for it, and the app starts the
 * engine itself if it should run.
 */
class EngineRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) = Unit
}

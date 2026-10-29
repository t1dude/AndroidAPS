package app.aaps.wear.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The alarm of the engine's keep alive (see [EngineKeepAlive]): no CGM reading for a while. Wakes the watch
 * even when it sleeps. Starting the app process for it also starts the engine, when it holds a session.
 */
class EngineKeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        WatchEngine.onKeepAliveAlarm()
    }
}

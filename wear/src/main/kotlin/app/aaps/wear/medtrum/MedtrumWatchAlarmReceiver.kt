package app.aaps.wear.medtrum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.aaps.core.interfaces.di.injectMetroMembers
import dev.zacsweers.metro.Inject

/** Handover test: the alarm for the next Medtrum status read. Wakes the watch even when it sleeps. */
class MedtrumWatchAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var link: MedtrumWatchLink

    override fun onReceive(context: Context, intent: Intent) {
        context.injectMetroMembers(this)
        link.onAlarm()
    }
}

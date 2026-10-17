package app.aaps.wear.handover

import android.content.Context
import android.content.Intent
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.di.MetroMemberInjector
import app.aaps.core.interfaces.rx.bus.RxBus

/** No AAPS engine in this flavour: only the full watch app has one (see src/full). */
object WatchEngineHook {

    @Suppress("UNUSED_PARAMETER")
    fun install(context: Context, memberInjector: MetroMemberInjector, rxBus: RxBus) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun injectMembers(target: Any): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun onGlucose(readings: List<GV>) = Unit

    fun hasSession(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun menuLabel(context: Context): String = ""

    fun screenIntent(context: Context): Intent? = null
}

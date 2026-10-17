package app.aaps.wear.handover

import android.content.Context
import android.content.Intent
import androidx.work.WorkerFactory
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.di.MetroMemberInjector
import app.aaps.core.interfaces.rx.bus.RxBus
import java.io.File

/** No AAPS engine in this flavour: only the full watch app has one (see src/full). */
object WatchEngineHook {

    @Suppress("UNUSED_PARAMETER")
    fun install(context: Context, memberInjector: MetroMemberInjector, rxBus: RxBus) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun injectMembers(target: Any): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun onGlucose(readings: List<GV>) = Unit

    /** No engine to take it: the file is dropped. */
    fun onSnapshot(file: File) {
        file.delete()
    }

    fun hasSession(): Boolean = false

    /** No engine, no workers of its own: WorkManager builds whatever comes itself. */
    fun workerFactory(): WorkerFactory? = null

    @Suppress("UNUSED_PARAMETER")
    fun menuLabel(context: Context): String = ""

    fun screenIntent(context: Context): Intent? = null
}

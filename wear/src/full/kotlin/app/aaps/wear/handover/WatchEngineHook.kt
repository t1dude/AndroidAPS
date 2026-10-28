package app.aaps.wear.handover

import android.content.Context
import android.content.Intent
import androidx.work.WorkerFactory
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.di.MetroMemberInjector
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.BuildConfig
import app.aaps.wear.engine.CompanionLink
import app.aaps.wear.engine.EngineBuildInfo
import app.aaps.wear.engine.EngineWorkerFactory
import app.aaps.wear.engine.R
import app.aaps.wear.engine.WatchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The full watch app's hook to the AAPS engine (`:wear:engine`). The other flavours have no engine and get
 * the stand-in from src/noEngine, which answers "no engine" to everything.
 */
object WatchEngineHook {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** At app start. Builds no engine unless the watch still holds a pump session. */
    fun install(context: Context, memberInjector: MetroMemberInjector, rxBus: RxBus) {
        val buildInfo = EngineBuildInfo(
            versionName = BuildConfig.VERSION_NAME,
            buildVersion = BuildConfig.BUILDVERSION,
            applicationId = BuildConfig.APPLICATION_ID,
            buildType = BuildConfig.BUILD_TYPE,
            debug = BuildConfig.DEBUG
        )
        val companion = object : CompanionLink {
            override fun sendToPhone(data: EventData) = rxBus.send(EventWearToMobile(data))
        }
        WatchEngine.install(context, memberInjector, buildInfo, companion)
        // Messages from the phone that are the engine's business
        scope.launch { rxBus.toFlow(EventData.MedtrumSessionToWatch::class).collect { WatchEngine.onMessageFromPhone(it) } }
    }

    fun injectMembers(target: Any): Boolean = WatchEngine.injectMembers(target)

    /** Builds the engine's workers; WorkManager builds its own internal ones itself. */
    fun workerFactory(): WorkerFactory = EngineWorkerFactory()

    /** The handover snapshot from the phone: the engine starts from it. */
    fun onSnapshot(file: File) = WatchEngine.onSnapshot(file)

    /** New readings from the watch's own G7, for the engine while it runs. */
    fun onGlucose(readings: List<GV>) = WatchEngine.onGlucose(readings)

    /** True while the engine holds a pump session or runs a test: the menu then offers the engine's screen. */
    fun isEngineActive(): Boolean = WatchEngine.isActive()

    fun menuLabel(context: Context): String = context.getString(R.string.medtrum_watch_title)

    fun screenIntent(context: Context): Intent? = WatchEngine.screenIntent(context)
}

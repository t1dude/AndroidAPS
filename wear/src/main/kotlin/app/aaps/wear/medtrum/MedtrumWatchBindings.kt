package app.aaps.wear.medtrum

import android.content.Context
import app.aaps.core.interfaces.di.FeatureMemberInjectors
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.TemporaryBasalStorage
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.medtrum.comm.packets.AuthorizePacket
import app.aaps.pump.medtrum.comm.packets.GetDeviceTypePacket
import app.aaps.pump.medtrum.comm.packets.GetTimePacket
import app.aaps.pump.medtrum.comm.packets.NotificationPacket
import app.aaps.pump.medtrum.comm.packets.SubscribePacket
import app.aaps.pump.medtrum.comm.packets.SynchronizePacket
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.Continuation
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Handover test: what the Medtrum driver needs on the watch, where there is no pump database and no
 * command queue.
 *
 * The watch only reads the patch status ([MedtrumWatchLink]); it sends no bolus or basal. When a status read
 * tells the driver about a basal change, the driver wants to write it to the pump database. On the watch
 * that write is only logged: the phone reads the patch history again when it takes the session back, so
 * the records end up in its database from there.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object MedtrumWatchBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun resourceHelper(context: Context): ResourceHelper = WatchResourceHelper(context)

    @Provides
    @SingleIn(AppScope::class)
    fun pumpSync(aapsLogger: AAPSLogger): PumpSync =
        logOnly(aapsLogger) { method -> if (method.name == "expectedPumpState") PumpSync.PumpState(null, null, null, null, "") else null }

    @Provides
    @SingleIn(AppScope::class)
    fun temporaryBasalStorage(aapsLogger: AAPSLogger): TemporaryBasalStorage = logOnly(aapsLogger)

    @Provides
    @SingleIn(AppScope::class)
    fun bolusProgressData(aapsLogger: AAPSLogger): BolusProgressData =
        BolusProgressData(logOnly<ConcentrationHelper>(aapsLogger), CoroutineScope(Dispatchers.Default + SupervisorJob()))

    // The packets the status read sends. Each packet fills its own fields from this map.

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(AuthorizePacket::class)
    fun bindAuthorizePacket(injector: MembersInjector<AuthorizePacket>): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(GetDeviceTypePacket::class)
    fun bindGetDeviceTypePacket(injector: MembersInjector<GetDeviceTypePacket>): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(GetTimePacket::class)
    fun bindGetTimePacket(injector: MembersInjector<GetTimePacket>): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(SynchronizePacket::class)
    fun bindSynchronizePacket(injector: MembersInjector<SynchronizePacket>): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(SubscribePacket::class)
    fun bindSubscribePacket(injector: MembersInjector<SubscribePacket>): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(NotificationPacket::class)
    fun bindNotificationPacket(injector: MembersInjector<NotificationPacket>): MembersInjector<*> = injector

    /**
     * An object that only logs each call and returns an empty answer (false, 0, null), or what [answer]
     * gives. A suspend function gets its answer straight back, which a proxy may do; it answers false,
     * because the suspend functions used here report whether a database write was done.
     */
    private inline fun <reified T : Any> logOnly(aapsLogger: AAPSLogger, crossinline answer: (Method) -> Any? = { null }): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "watch stand-in for ${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals"   -> proxy === args?.firstOrNull()
                else       -> {
                    aapsLogger.debug(LTag.PUMP, "Medtrum watch test: ${T::class.java.simpleName}.${method.name} is not done on the watch")
                    answer(method) ?: emptyAnswer(method)
                }
            }
        } as T

    private fun emptyAnswer(method: Method): Any? {
        if (method.parameterTypes.lastOrNull() == Continuation::class.java) return false
        return when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType     -> 0
            Long::class.javaPrimitiveType    -> 0L
            Double::class.javaPrimitiveType  -> 0.0
            Float::class.javaPrimitiveType   -> 0f
            else                             -> null
        }
    }
}

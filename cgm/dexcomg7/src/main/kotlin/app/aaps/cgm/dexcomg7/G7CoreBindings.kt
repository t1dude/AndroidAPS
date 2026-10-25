package app.aaps.cgm.dexcomg7

import android.content.Context
import app.aaps.cgm.dexcomg7.ble.G7Bluetooth
import app.aaps.cgm.dexcomg7.data.G7CommLog
import app.aaps.cgm.dexcomg7.data.G7StateStore
import app.aaps.cgm.dexcomg7.session.G7PairingService
import app.aaps.cgm.dexcomg7.session.G7ReadingHistory
import app.aaps.cgm.dexcomg7.session.G7Session
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * The Dexcom G7 connection from `:cgm:dexcomg7:core`, one of each for the app.
 *
 * Scoped here rather than on the classes: the watch app holds the same classes in its companion graph,
 * which has its own scope (`WearScope`), so a scope on the class would fit only one of them.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object G7CoreBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun bluetooth(context: Context): G7Bluetooth = G7Bluetooth(context)

    @Provides
    @SingleIn(AppScope::class)
    fun commLog(context: Context, aapsLogger: AAPSLogger): G7CommLog = G7CommLog(context, aapsLogger)

    @Provides
    @SingleIn(AppScope::class)
    fun stateStore(preferences: Preferences, aapsLogger: AAPSLogger): G7StateStore = G7StateStore(preferences, aapsLogger)

    @Provides
    @SingleIn(AppScope::class)
    fun session(context: Context, bluetooth: G7Bluetooth, store: G7StateStore, commLog: G7CommLog, history: G7ReadingHistory): G7Session =
        G7Session(context, bluetooth, store, commLog, history)

    @Provides
    @SingleIn(AppScope::class)
    fun pairingService(context: Context, bluetooth: G7Bluetooth, store: G7StateStore, session: G7Session, commLog: G7CommLog): G7PairingService =
        G7PairingService(context, bluetooth, store, session, commLog)
}

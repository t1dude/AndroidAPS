package app.aaps.wear.g7

import android.content.Context
import app.aaps.cgm.dexcomg7.ble.G7Bluetooth
import app.aaps.cgm.dexcomg7.data.G7CommLog
import app.aaps.cgm.dexcomg7.data.G7StateStore
import app.aaps.cgm.dexcomg7.session.G7PairingService
import app.aaps.cgm.dexcomg7.session.G7ReadingHistory
import app.aaps.cgm.dexcomg7.session.G7Session
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.wear.di.WearScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Direct to watch: the Dexcom G7 connection from `:cgm:dexcomg7:core`, one of each for the companion
 * graph. The phone binds the same classes in `G7CoreBindings`, for its own scope.
 */
@ContributesTo(WearScope::class)
@BindingContainer
object WearG7Bindings {

    @Provides
    @SingleIn(WearScope::class)
    fun bluetooth(context: Context): G7Bluetooth = G7Bluetooth(context)

    @Provides
    @SingleIn(WearScope::class)
    fun commLog(context: Context, aapsLogger: AAPSLogger): G7CommLog = G7CommLog(context, aapsLogger)

    @Provides
    @SingleIn(WearScope::class)
    fun stateStore(preferences: Preferences, aapsLogger: AAPSLogger): G7StateStore = G7StateStore(preferences, aapsLogger)

    @Provides
    @SingleIn(WearScope::class)
    fun session(context: Context, bluetooth: G7Bluetooth, store: G7StateStore, commLog: G7CommLog, history: G7ReadingHistory): G7Session =
        G7Session(context, bluetooth, store, commLog, history)

    @Provides
    @SingleIn(WearScope::class)
    fun pairingService(context: Context, bluetooth: G7Bluetooth, store: G7StateStore, session: G7Session, commLog: G7CommLog): G7PairingService =
        G7PairingService(context, bluetooth, store, session, commLog)
}

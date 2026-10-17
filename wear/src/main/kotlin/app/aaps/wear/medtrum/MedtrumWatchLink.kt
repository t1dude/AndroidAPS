package app.aaps.wear.medtrum

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.aaps.core.interfaces.di.MetroMemberInjector
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.ble.MedtrumBleCallback
import app.aaps.pump.medtrum.ble.MedtrumBleTransport
import app.aaps.pump.medtrum.comm.packets.AuthorizePacket
import app.aaps.pump.medtrum.comm.packets.GetDeviceTypePacket
import app.aaps.pump.medtrum.comm.packets.GetTimePacket
import app.aaps.pump.medtrum.comm.packets.MedtrumPacket
import app.aaps.pump.medtrum.comm.packets.NotificationPacket
import app.aaps.pump.medtrum.comm.packets.SubscribePacket
import app.aaps.pump.medtrum.comm.packets.SynchronizePacket
import app.aaps.pump.medtrum.handover.MedtrumSession
import app.aaps.pump.medtrum.handover.MedtrumSession.Companion.writeTo
import app.aaps.pump.medtrum.keys.MedtrumBooleanNonKey
import app.aaps.wear.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Handover test, watch side: the phone hands its running Medtrum patch session to the watch, the watch
 * reads the patch status with it, and hands it back.
 *
 * A status read is the phone's connect sequence (authorize, device type, time, synchronize, subscribe) and
 * nothing more: the watch sends no bolus or basal, does not set the patch clock and does not read the patch
 * history. The phone reads the history it missed when it takes the session back, so nothing is lost or
 * doubled.
 *
 * Only one device may talk to the patch. The phone stops before it sends the session; the watch stops
 * before it sends it back.
 */
@SingleIn(AppScope::class)
@Inject
class MedtrumWatchLink(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val preferences: Preferences,
    private val medtrumPump: MedtrumPump,
    private val transport: MedtrumBleTransport,
    private val injector: MetroMemberInjector
) : MedtrumBleCallback {

    data class Status(
        /** The patch serial, 0 when the watch has no session. */
        val serial: Long = 0L,
        val reading: Boolean = false,
        val autoRead: Boolean = false,
        val lastReadAt: Long? = null,
        val lastReadOk: Boolean? = null,
        /** What the last good read found, ready to show. */
        val result: String? = null,
        val log: List<String> = emptyList()
    ) {

        val hasSession: Boolean get() = serial != 0L
    }

    private class Pending(val packet: MedtrumPacket, val done: CompletableDeferred<Boolean>) {

        var retries = 0
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private var started = false
    private var autoReadJob: Job? = null

    @Volatile private var connected: CompletableDeferred<Boolean>? = null
    @Volatile private var pending: Pending? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The Bluetooth permissions the driver uses exist from Android 12. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun start() {
        if (started) return
        started = true
        medtrumPump.loadVarsFromSP()
        _status.update { it.copy(serial = currentSerial()) }
        scope.launch { rxBus.toFlow(EventData.MedtrumSessionToWatch::class).collect { takeIn(it.session) } }
    }

    /** One status read, as the "Read status" button does. */
    fun readNow() {
        scope.launch { readStatus("button") }
    }

    /** Reads every [AUTO_READ_MS] while on, to see whether the link holds over time and with the screen off. */
    fun setAutoRead(on: Boolean) {
        autoReadJob?.cancel()
        autoReadJob = null
        _status.update { it.copy(autoRead = on) }
        if (on) {
            MedtrumWatchService.start(context)
            autoReadJob = scope.launch {
                while (true) {
                    readStatus("auto")
                    delay(AUTO_READ_MS)
                }
            }
        } else MedtrumWatchService.stop(context)
    }

    /** Stops talking to the patch and sends the session back to the phone. */
    fun handBack() {
        scope.launch {
            setAutoRead(false)
            mutex.withLock {
                if (!_status.value.hasSession) return@withLock
                // Stop first: the session must not move on after it is sent
                preferences.put(MedtrumBooleanNonKey.SessionMovedAway, true)
                val session = MedtrumSession.read(preferences, System.currentTimeMillis())
                rxBus.send(EventWearToMobile(EventData.MedtrumSessionToPhone(session.toText())))
                _status.update { it.copy(serial = 0L, result = null, lastReadOk = null) }
                log("Session of patch ${session.serial.toString(16)} handed back to the phone")
            }
        }
    }

    private suspend fun takeIn(text: String) = mutex.withLock {
        val session = MedtrumSession.parse(text)
        if (session == null || session.serial == 0L) {
            log("The phone sent a session that cannot be read")
            return@withLock
        }
        session.writeTo(preferences)
        preferences.put(MedtrumBooleanNonKey.SessionMovedAway, false)
        medtrumPump.loadVarsFromSP()
        _status.update { it.copy(serial = currentSerial(), lastReadAt = null, lastReadOk = null, result = null) }
        log("Took in the session of patch ${session.serial.toString(16)}, exported ${time(session.exportedAt)}")
        notifySessionReceived()
    }

    private suspend fun readStatus(reason: String): Boolean = mutex.withLock {
        val serial = _status.value.serial
        if (serial == 0L) return@withLock false
        if (!isSupported) {
            log("This watch runs Android 11 or older; the Medtrum link needs Android 12")
            return@withLock false
        }
        _status.update { it.copy(reading = true) }
        val ok = try {
            read(serial, reason)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPCOMM, "Medtrum watch test: status read failed", e)
            log("Read failed: ${e.message}")
            false
        } finally {
            pending = null
            connected = null
            transport.disconnect("Medtrum watch test")
            transport.setMedtrumCallback(null)
        }
        _status.update {
            it.copy(reading = false, lastReadAt = System.currentTimeMillis(), lastReadOk = ok, result = if (ok) describe() else it.result)
        }
        ok
    }

    private suspend fun read(serial: Long, reason: String): Boolean {
        val started = System.currentTimeMillis()
        log("Connecting to patch ${serial.toString(16)} ($reason)")
        transport.setMedtrumCallback(this)
        val link = CompletableDeferred<Boolean>()
        connected = link
        if (!transport.connect("Medtrum watch test", serial)) {
            log("Connect refused: Bluetooth off or not allowed")
            return false
        }
        if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { link.await() } != true) {
            log("No connection to the patch")
            return false
        }
        log("Connected after ${(System.currentTimeMillis() - started) / 1000} s")
        val steps = listOf(
            "authorize" to AuthorizePacket(injector),
            "device type" to GetDeviceTypePacket(injector),
            "time" to GetTimePacket(injector),
            "synchronize" to SynchronizePacket(injector),
            "subscribe" to SubscribePacket(injector)
        )
        for ((name, packet) in steps) {
            if (!exchange(packet)) {
                log("Step $name failed")
                return false
            }
        }
        val clockOff = ((medtrumPump.lastTimeReceivedFromPump - System.currentTimeMillis()) / 1000.0).roundToLong()
        log("Status read in ${(System.currentTimeMillis() - started) / 1000} s, patch clock off by $clockOff s")
        return true
    }

    private suspend fun exchange(packet: MedtrumPacket): Boolean {
        val done = CompletableDeferred<Boolean>()
        pending = Pending(packet, done)
        transport.sendMessage(packet.getRequest())
        val ok = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { done.await() } ?: false
        pending = null
        return ok
    }

    private fun describe(): String {
        val clockOff = ((medtrumPump.lastTimeReceivedFromPump - System.currentTimeMillis()) / 1000.0).roundToLong()
        return context.getString(
            R.string.medtrum_watch_result,
            context.getString(medtrumPump.pumpState.label),
            medtrumPump.reservoir,
            medtrumPump.batteryVoltage_B,
            medtrumPump.lastBasalRate,
            medtrumPump.lastBasalType.name,
            clockOff
        )
    }

    private fun currentSerial(): Long =
        if (preferences.get(MedtrumBooleanNonKey.SessionMovedAway)) 0L else medtrumPump.pumpSN

    // MedtrumBleCallback: called on the transport's threads

    override fun onConnected() {
        connected?.complete(true)
    }

    override fun onDisconnected() {
        connected?.complete(false)
        pending?.done?.complete(false)
    }

    override fun onNotification(data: ByteArray) {
        NotificationPacket(injector).handleNotification(data)
    }

    override fun onIndication(data: ByteArray) {
        val current = pending ?: return
        if (current.packet.handleResponse(data)) current.done.complete(true)
        else if (current.packet.failed) current.done.complete(false)
    }

    override fun onSendMessageError(reason: String, isRetryAble: Boolean) {
        val current = pending ?: return
        log("Send failed: $reason")
        if (isRetryAble && current.retries < 3) {
            current.retries++
            transport.sendMessage(current.packet.getRequest())
        } else current.done.complete(false)
    }

    private fun log(text: String) {
        aapsLogger.info(LTag.PUMPCOMM, "Medtrum watch test: $text")
        val line = "${time(System.currentTimeMillis())} $text"
        _status.update { it.copy(log = (it.log + line).takeLast(LOG_LINES)) }
    }

    private fun time(at: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))

    private fun notifySessionReceived() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.medtrum_watch_title), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MedtrumWatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_icon)
            .setContentTitle(context.getString(R.string.medtrum_watch_title))
            .setContentText(context.getString(R.string.medtrum_watch_received))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {

        private const val AUTO_READ_MS = 5 * 60 * 1000L
        private const val CONNECT_TIMEOUT_MS = 60 * 1000L
        private const val COMMAND_TIMEOUT_MS = 30 * 1000L
        private const val LOG_LINES = 40
        private const val CHANNEL_ID = "AndroidAPS-MedtrumWatchTest"

        // Clear of the ids DataLayerListenerServiceWear (1, 2, 556677), CgmAlarmWear (7700..7955) and
        // the G7 notifications (7960, 7961).
        private const val NOTIFICATION_ID = 7970
    }
}

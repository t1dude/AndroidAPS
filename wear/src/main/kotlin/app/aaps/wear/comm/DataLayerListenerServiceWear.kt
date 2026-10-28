package app.aaps.wear.comm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aaps.core.interfaces.di.injectMetroMembers
import app.aaps.core.interfaces.handover.HandoverSnapshot
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventWearDataToMobile
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.events.EventWearToMobileFile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.R
import app.aaps.wear.events.EventWearPreferenceChange
import app.aaps.wear.handover.WatchEngineHook
import app.aaps.wear.heartrate.HeartRateListener
import app.aaps.wear.interaction.ConfigurationActivity
import app.aaps.wear.watchfaces.WatchFacePushHelper
import app.aaps.wear.wearStepCount.StepCountListener
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.zacsweers.metro.Inject
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File

class DataLayerListenerServiceWear : WearableListenerService() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var sp: SP
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var watchFacePushHelper: WatchFacePushHelper
    @Inject lateinit var cgmAlarmWear: CgmAlarmWear

    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(this) }
    private val channelClient by lazy { Wearable.getChannelClient(this) }
    //private val nodeClient by lazy { Wearable.getNodeClient(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

    private val disposable = CompositeDisposable()
    private var heartRateListener: HeartRateListener? = null
    private var stepCountListener: StepCountListener? = null

    private val rxPath get() = getString(app.aaps.core.interfaces.R.string.path_rx_bridge)
    private val rxDataPath get() = getString(app.aaps.core.interfaces.R.string.path_rx_data_bridge)

    @ExperimentalSerializationApi
    override fun onCreate() {
        injectMetroMembers(this)
        super.onCreate()
        startForegroundService()
        handler.post { updateTranscriptionCapability() }
        // scope is Main.immediate, which is the right lifetime. The two sends observed on io and talk
        // to the Data Layer, so those bodies go back to IO; the preference one observed on main and
        // touches the listeners, so it stays where the collector is.
        rxBus.toFlow(EventWearToMobile::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) {
                withContext(Dispatchers.IO) { sendMessage(rxPath, it.payload.serialize()) }
            }
        rxBus.toFlow(EventWearDataToMobile::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) {
                withContext(Dispatchers.IO) { sendMessage(rxDataPath, it.payload.serializeByte()) }
            }
        rxBus.toFlow(EventWearToMobileFile::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) {
                withContext(Dispatchers.IO) { it.done.complete(sendFile(it.path, it.channelPath)) }
            }
        rxBus.toFlow(EventWearPreferenceChange::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) { event ->
                if (event.changedKey == getString(R.string.key_heart_rate_sampling)) updateHeartRateListener()
                if (event.changedKey == getString(R.string.key_steps_sampling)) updateStepsCountListener()
            }

        updateHeartRateListener()
        updateStepsCountListener()

        // A watch with Watch Face Push has no code-based face to ask the phone for its data, so
        // nothing would ask until a menu or a tile is opened. The pushed face choice lives on the
        // phone, and after a reinstall the watch starts with the default face until the
        // preferences arrive. So ask now. Posted behind the capability lookup above, on the same
        // thread: that is the first moment a node is known, and the collectors above exist by then.
        handler.post {
            if (watchFacePushHelper.isSupported())
                rxBus.send(EventWearToMobile(EventData.ActionResendData("DataLayerListenerServiceWear::onCreate")))
        }
    }

    override fun onCapabilityChanged(p0: CapabilityInfo) {
        super.onCapabilityChanged(p0)
        handler.post { updateTranscriptionCapability() }
        aapsLogger.debug(LTag.WEAR, "onCapabilityChanged:  ${p0.name} ${p0.nodes.joinToString(", ") { it.displayName + "(" + it.id + ")" }}")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
        scope.cancel()
        disposable.clear()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        when (messageEvent.path) {
            rxPath     -> {
                // Size only, like the data path below: the whole payload (GraphData is the big one)
                // went into the log file on every sync, and the type is logged by its handler anyway.
                aapsLogger.debug(LTag.WEAR, "onMessageReceived: ${messageEvent.data.size}")
                val command = EventData.deserialize(String(messageEvent.data))
                rxBus.send(command.also { it.sourceNodeId = messageEvent.sourceNodeId })
                // Use this sender
                transcriptionNodeId = messageEvent.sourceNodeId
                aapsLogger.debug(LTag.WEAR, "Updated node: $transcriptionNodeId")
            }

            rxDataPath -> {
                aapsLogger.debug(LTag.WEAR, "onMessageReceived: ${messageEvent.data.size}")
                ZipWatchfaceFormat.loadCustomWatchface(messageEvent.data, "", false)?.let {
                    val command = EventData.ActionSetCustomWatchface(it.cwfData)
                    rxBus.send(command.also { it.sourceNodeId = messageEvent.sourceNodeId })
                }
                // Use this sender
                transcriptionNodeId = messageEvent.sourceNodeId
                aapsLogger.debug(LTag.WEAR, "Updated node: $transcriptionNodeId")
            }
        }
    }

    /**
     * A file from the phone, too big for a message: the handover snapshot. It is received into the app's
     * files and handed on only when the channel closed normally, so a half received file is never used.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        if (channel.path != HandoverSnapshot.CHANNEL_PATH) return
        val file = snapshotFile()
        file.parentFile?.mkdirs()
        file.delete()
        aapsLogger.info(LTag.WEAR, "Handover snapshot: receiving")
        channelClient.receiveFile(channel, Uri.fromFile(file), false)
    }

    override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        super.onInputClosed(channel, closeReason, appSpecificErrorCode)
        if (channel.path != HandoverSnapshot.CHANNEL_PATH) return
        val file = snapshotFile()
        if (closeReason == ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL && file.exists()) {
            aapsLogger.info(LTag.WEAR, "Handover snapshot: received ${file.length()} bytes")
            WatchEngineHook.onSnapshot(file)
        } else {
            aapsLogger.error(LTag.WEAR, "Handover snapshot: transfer failed, reason $closeReason/$appSpecificErrorCode")
            file.delete()
        }
    }

    private fun snapshotFile() = File(filesDir, "handover/incoming_snapshot.zip")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            INTENT_CANCEL_BOLUS        -> {
                //dismiss notification
                NotificationManagerCompat.from(this).cancel(BOLUS_PROGRESS_NOTIF_ID)
                //send cancel-request to phone.
                rxBus.send(EventWearToMobile(EventData.CancelBolus(System.currentTimeMillis())))
            }

            INTENT_WEAR_TO_MOBILE      -> sendMessage(rxPath, intent.extras?.getString(KEY_ACTION_DATA))
            INTENT_CANCEL_NOTIFICATION -> (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(CHANGE_NOTIF_ID)
            INTENT_CGM_ALARM_ACKNOWLEDGE -> intent.getStringExtra(KEY_CGM_ALARM_ID)?.let { cgmAlarmWear.acknowledge(it) }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notificationIntent = Intent(this, ConfigurationActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.datalayer_notification_title))
            .setContentText(getString(R.string.datalayer_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(R.drawable.ic_icon)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(FOREGROUND_NOTIF_ID, notification)
    }

    private fun updateHeartRateListener() {
        if (sp.getBoolean(R.string.key_heart_rate_sampling, false)) {
            if (heartRateListener == null) {
                heartRateListener = HeartRateListener(
                    this, aapsLogger, sp, aapsSchedulers
                ).also { hrl -> disposable += hrl }
            }
        } else {
            heartRateListener?.let { hrl ->
                disposable.remove(hrl)
                heartRateListener = null
            }
        }
    }

    private fun updateStepsCountListener() {
        if (sp.getBoolean(R.string.key_steps_sampling, false)) {
            if (stepCountListener == null) {
                stepCountListener = StepCountListener(
                    this, aapsLogger, aapsSchedulers
                ).also { scl -> disposable += scl }
            }
        } else {
            stepCountListener?.let { scl ->
                disposable.remove(scl)
                stepCountListener = null
            }
        }
    }

    @Suppress("PrivatePropertyName")
    private val CHANNEL_ID: String = "DataLayerForegroundServiceChannel"
    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(CHANNEL_ID, "Data Layer Foreground Service Channel", NotificationManager.IMPORTANCE_LOW)
        serviceChannel.setShowBadge(false)
        serviceChannel.enableLights(false)
        serviceChannel.enableVibration(false)
        serviceChannel.setSound(null, null)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private var transcriptionNodeId: String? = null

    private fun updateTranscriptionCapability() {
        val capabilityInfo: CapabilityInfo = Tasks.await(
            capabilityClient.getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
        )
        aapsLogger.debug(LTag.WEAR, "Nodes: ${capabilityInfo.nodes.joinToString(", ") { it.displayName + "(" + it.id + ")" }}")
        pickBestNodeId(capabilityInfo.nodes)?.let { transcriptionNodeId = it }
        aapsLogger.debug(LTag.WEAR, "Selected node: $transcriptionNodeId")
    }

    // Find a nearby node or pick one arbitrarily
    private fun pickBestNodeId(nodes: Set<Node>): String? =
        nodes.firstOrNull { it.isNearby }?.id ?: nodes.firstOrNull()?.id

    @Suppress("unused")
    private fun sendData(path: String, vararg params: DataMap) {
        scope.launch {
            try {
                for (dm in params) {
                    val request = PutDataMapRequest.create(path).apply {
                        dataMap.putAll(dm)
                    }
                        .asPutDataRequest()
                        .setUrgent()

                    val result = dataClient.putDataItem(request).await()
                    aapsLogger.debug(LTag.WEAR, "sendData: ${result.uri} ${params.joinToString()}")
                }
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (exception: Exception) {
                aapsLogger.error(LTag.WEAR, "DataItem failed: $exception")
            }
        }
    }

    private fun sendMessage(path: String, data: String?) {
        transcriptionNodeId?.also { nodeId ->
            aapsLogger.debug(LTag.WEAR, "sendMessage: $path $data")
            messageClient
                .sendMessage(nodeId, path, data?.toByteArray() ?: byteArrayOf()).apply {
                    addOnSuccessListener { }
                    addOnFailureListener {
                        aapsLogger.debug(LTag.WEAR, "sendMessage:  $path failure $it")
                    }
                }
        } ?: aapsLogger.debug(LTag.WEAR, "sendMessage: Ignoring message. No node selected.")
    }

    /**
     * Sends a file to the phone over a Wear channel, for what is too big for a message (the engine's test
     * log). True when the whole file went out: the channel's output closed normally after the send.
     */
    private suspend fun sendFile(path: String, channelPath: String): Boolean {
        val nodeId = transcriptionNodeId ?: run {
            aapsLogger.error(LTag.WEAR, "sendFile: no phone connected, $channelPath not sent")
            return false
        }
        return try {
            val channel = channelClient.openChannel(nodeId, channelPath).await()
            val closed = CompletableDeferred<Int>()
            val callback = object : ChannelClient.ChannelCallback() {
                override fun onOutputClosed(closedChannel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
                    if (closedChannel.path == channel.path && closedChannel.nodeId == channel.nodeId) closed.complete(closeReason)
                }
            }
            channelClient.registerChannelCallback(channel, callback).await()
            try {
                channelClient.sendFile(channel, Uri.fromFile(File(path))).await()
                val reason = withTimeoutOrNull(FILE_SEND_TIMEOUT_MS) { closed.await() }
                aapsLogger.debug(LTag.WEAR, "sendFile: $channelPath ${File(path).length()} bytes, close reason $reason")
                reason == ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL
            } finally {
                channelClient.unregisterChannelCallback(channel, callback)
            }
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (exception: Exception) {
            aapsLogger.error(LTag.WEAR, "sendFile: $channelPath failed", exception)
            false
        }
    }

    private fun sendMessage(path: String, data: ByteArray) {
        aapsLogger.debug(LTag.WEAR, "sendMessage: $path ${data.size}")
        transcriptionNodeId?.also { nodeId ->
            messageClient
                .sendMessage(nodeId, path, data).apply {
                    addOnSuccessListener { }
                    addOnFailureListener {
                        aapsLogger.debug(LTag.WEAR, "sendMessage:  $path failure ${data.size}")
                    }
                }
        }
    }

    companion object {

        const val PHONE_CAPABILITY = "androidaps_mobile"

        /** A test log of a night is about 2 MB; over Bluetooth that takes well under this. */
        private const val FILE_SEND_TIMEOUT_MS = 2 * 60 * 1000L

        // Accepted intents
        val INTENT_NEW_DATA = DataLayerListenerServiceWear::class.java.name + ".NewData"
        val INTENT_CANCEL_BOLUS = DataLayerListenerServiceWear::class.java.name + ".CancelBolus"
        val INTENT_WEAR_TO_MOBILE = DataLayerListenerServiceWear::class.java.name + ".WearToMobile"
        val INTENT_CANCEL_NOTIFICATION = DataLayerListenerServiceWear::class.java.name + ".CancelNotification"
        val INTENT_CGM_ALARM_ACKNOWLEDGE = DataLayerListenerServiceWear::class.java.name + ".CgmAlarmAcknowledge"

        //data keys
        const val KEY_ACTION_DATA = "actionData"
        const val KEY_CGM_ALARM_ID = "cgmAlarmId"
        const val KEY_WIZARD_DETAIL = "wizardDetail"

        // Master-authored confirmation rows (parallel arrays: role name + text) rendered verbatim by AcceptActivity.
        const val KEY_LINE_ROLES = "lineRoles"
        const val KEY_LINE_TEXTS = "lineTexts"
        const val KEY_ACTION = "action"
        const val KEY_MESSAGE = "message"
        const val KEY_TITLE = "title"
        const val KEY_IS_ERROR = "isError"
        const val KEY_DEFER_CONFIRM = "deferConfirm"

        const val BOLUS_PROGRESS_NOTIF_ID = 1
        const val CONFIRM_NOTIF_ID = 2
        const val FOREGROUND_NOTIF_ID = 3
        const val CHANGE_NOTIF_ID = 556677

        // Do not change the ID: Android keeps the user's channel settings by ID. The name shown is set
        // where the channel is created ("AAPS Open Loop").
        const val AAPS_NOTIFY_CHANNEL_ID_OPEN_LOOP_OLD = "AndroidAPS-OpenLoop"
        const val AAPS_NOTIFY_CHANNEL_ID_OPEN_LOOP = "AndroidAPS-OpenLoop-v2"
        const val AAPS_NOTIFY_CHANNEL_ID_BOLUS_PROGRESS = "bolus progress vibration"
        const val AAPS_NOTIFY_CHANNEL_ID_BOLUS_PROGRESS_SILENT = "bolus progress silent"
    }
}
package app.aaps.plugins.sync.wear.wearintegration

import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.di.injectMetroMembers
import app.aaps.core.interfaces.handover.HandoverSnapshot
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.events.EventMobileToWearFile
import app.aaps.core.interfaces.rx.events.EventMobileToWearWatchface
import app.aaps.core.interfaces.rx.events.EventWearUpdateGui
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.plugins.sync.wear.WearPlugin
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File

class DataLayerListenerServiceMobile : WearableListenerService() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var loop: Loop
    @Inject lateinit var wearPlugin: WearPlugin
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var rxBus: RxBus

    inner class LocalBinder : Binder() {

        fun getService(): DataLayerListenerServiceMobile = this@DataLayerListenerServiceMobile
    }

    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val channelClient by lazy { Wearable.getChannelClient(this) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(this) }
    //private val nodeClient by lazy { Wearable.getNodeClient(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)


    private val rxPath get() = getString(app.aaps.core.interfaces.R.string.path_rx_bridge)
    private val rxWatchfacePath get() = getString(app.aaps.core.interfaces.R.string.path_rx_data_bridge)

    override fun onCreate() {
        injectMetroMembers(this)
        super.onCreate()
        aapsLogger.debug(LTag.WEAR, "onCreate")
        handler.post { updateTranscriptionCapability() }
        // scope is Main.immediate and onDestroy cancels it, so it is the right lifetime - but these two
        // observed on aapsSchedulers.io, and sendMessage talks to the Wear Data Layer. So the collector
        // lives on the service's scope and the send goes back to IO.
        rxBus.toFlow(EventMobileToWear::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) {
                withContext(Dispatchers.IO) { sendMessage(rxPath, it.payload.serialize()) }
            }
        rxBus.toFlow(EventMobileToWearWatchface::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) {
                withContext(Dispatchers.IO) { sendMessage(rxWatchfacePath, it.payload) }
            }
        rxBus.toFlow(EventMobileToWearFile::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) {
                withContext(Dispatchers.IO) { sendFile(it.path, it.channelPath) }
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
    }

    /**
     * The watch's engine test log, too big for a message. It is received into the cache and handed on as
     * [EventData.MedtrumWatchLog], as a log in a message was, only when the channel closed normally.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        if (channel.path != HandoverSnapshot.TEST_LOG_CHANNEL_PATH) return
        val file = testLogFile()
        file.parentFile?.mkdirs()
        file.delete()
        channelClient.receiveFile(channel, Uri.fromFile(file), false)
    }

    override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        super.onInputClosed(channel, closeReason, appSpecificErrorCode)
        if (channel.path != HandoverSnapshot.TEST_LOG_CHANNEL_PATH) return
        val file = testLogFile()
        if (closeReason == ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL && file.exists()) {
            aapsLogger.info(LTag.WEAR, "Watch test log: received ${file.length()} bytes")
            rxBus.send(EventData.MedtrumWatchLog(file.readText()))
        } else aapsLogger.error(LTag.WEAR, "Watch test log: transfer failed, reason $closeReason/$appSpecificErrorCode")
        file.delete()
    }

    private fun testLogFile() = File(cacheDir, "handover/incoming_test_log.txt")

    @ExperimentalSerializationApi
    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        if (wearPlugin.isEnabled()) {
            when (messageEvent.path) {
                rxPath          -> {
                    aapsLogger.debug(LTag.WEAR, "onMessageReceived rxPath: ${String(messageEvent.data)}")
                    val command = EventData.deserialize(String(messageEvent.data))
                    rxBus.send(command.also { it.sourceNodeId = messageEvent.sourceNodeId })
                }

                rxWatchfacePath -> {
                    aapsLogger.debug(LTag.WEAR, "onMessageReceived rxDataPath: ${messageEvent.data.size}")
                    val command = EventData.deserializeByte(messageEvent.data)
                    rxBus.send(command.also { it.sourceNodeId = messageEvent.sourceNodeId })
                }
            }
        }
    }

    private var transcriptionNodeId: String? = null

    private fun updateTranscriptionCapability() {
        try {
            val capabilityInfo: CapabilityInfo = Tasks.await(
                capabilityClient.getCapability(WEAR_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            )
            aapsLogger.debug(LTag.WEAR, "Nodes: ${capabilityInfo.nodes.joinToString(", ") { it.displayName + "(" + it.id + ")" }}")
            val bestNode = pickBestNodeId(capabilityInfo.nodes)
            transcriptionNodeId = bestNode?.id
            wearPlugin.updateConnectedDevice(bestNode?.displayName)
            rxBus.send(EventWearUpdateGui())
            aapsLogger.debug(LTag.WEAR, "Selected node: ${bestNode?.displayName} $transcriptionNodeId")
            rxBus.send(EventMobileToWear(EventData.ActionPing(System.currentTimeMillis())))
            rxBus.send(EventData.ActionResendData("WatchUpdaterService"))
        } catch (_: Exception) {
            fabricPrivacy.logCustom("WearOS_unsupported")
        }
    }

    // Find a nearby node or pick one arbitrarily
    private fun pickBestNodeId(nodes: Set<Node>): Node? =
        nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()

    @Suppress("unused")
    private fun sendData(path: String, vararg params: DataMap) {
        if (wearPlugin.isEnabled()) {
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
    }

    private fun sendMessage(path: String, data: String?) {
        // Size only, like the ByteArray overload: the full GraphData payload copied into a log string
        // on every resend was a cost of its own.
        aapsLogger.debug(LTag.WEAR, "sendMessage: $path ${data?.length ?: 0}")
        transcriptionNodeId?.also { nodeId ->
            messageClient
                .sendMessage(nodeId, path, data?.toByteArray() ?: byteArrayOf()).apply {
                    addOnSuccessListener { }
                    addOnFailureListener {
                        aapsLogger.debug(LTag.WEAR, "sendMessage:  $path failure")
                    }
                }
        }
    }

    /**
     * Sends a file to the watch over a Wear channel, for what is too big for a message (the handover
     * snapshot). The watch receives it whole or not at all: it acts on it only when the channel closed
     * normally.
     */
    private suspend fun sendFile(path: String, channelPath: String) {
        val file = File(path)
        val nodeId = transcriptionNodeId
        if (nodeId == null) {
            aapsLogger.error(LTag.WEAR, "sendFile: no watch connected, $channelPath not sent")
            return
        }
        try {
            val channel = channelClient.openChannel(nodeId, channelPath).await()
            channelClient.sendFile(channel, Uri.fromFile(file)).await()
            aapsLogger.debug(LTag.WEAR, "sendFile: $channelPath ${file.length()} bytes started")
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (exception: Exception) {
            aapsLogger.error(LTag.WEAR, "sendFile: $channelPath failed", exception)
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

        const val WEAR_CAPABILITY = "androidaps_wear"
    }
}

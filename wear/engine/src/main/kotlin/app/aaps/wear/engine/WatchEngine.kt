package app.aaps.wear.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.di.MetroMemberInjector
import app.aaps.core.interfaces.handover.HandoverSnapshot
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.objects.di.CoreObjectsGraph
import app.aaps.database.DATABASE_VERSION
import app.aaps.database.di.DatabaseConfig
import app.aaps.pump.medtrum.keys.MedtrumBooleanNonKey
import app.aaps.pump.medtrum.keys.MedtrumStringNonKey
import app.aaps.wear.engine.di.EngineBindings
import app.aaps.wear.engine.di.EngineGraph
import app.aaps.wear.engine.medtrum.MedtrumWatchActivity
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The engine's entry point for the rest of the watch app (the companion).
 *
 * The engine's graph is built only when it is needed: when the phone hands over a pump session, when the
 * watch still holds one after a restart, or when one of the engine's own screens or receivers starts. In
 * companion mode nothing of the engine runs (Tim's lesson from Watch-APS: an engine kept alive without a
 * pump drains the battery). What the companion asks before that ([hasSession]) is read from the engine's
 * settings file directly, without building anything.
 */
object WatchEngine {

    private lateinit var context: Context
    private lateinit var memberInjector: MetroMemberInjector
    private lateinit var buildInfo: EngineBuildInfo
    private lateinit var companion: CompanionLink

    @Volatile private var graph: EngineGraph? = null

    /** Called once by the companion at app start. Builds nothing. */
    fun install(context: Context, memberInjector: MetroMemberInjector, buildInfo: EngineBuildInfo, companion: CompanionLink) {
        this.context = context.applicationContext
        this.memberInjector = memberInjector
        this.buildInfo = buildInfo
        this.companion = companion
        // A snapshot came while the engine ran, and the app restarted for it: start from the snapshot
        val snapshot = importPendingSnapshot()
        when {
            snapshot != null -> startFromSnapshot(snapshot)
            // The watch held a pump session when the app stopped: carry on with it
            hasSession()     -> start { it.medtrumWatchLink.start() }
        }
    }

    /**
     * The handover snapshot arrived from the phone (see `HandoverSnapshot`): the cut down database, the
     * settings and the pump session. The engine starts from it. When the engine already runs in this
     * process, its database is open and its plugins started, so the app restarts once and takes the
     * snapshot in at the start (see [install]).
     */
    fun onSnapshot(received: File) {
        val pending = pendingSnapshotFile()
        pending.parentFile?.mkdirs()
        pending.delete()
        if (!received.renameTo(pending)) {
            received.copyTo(pending, overwrite = true)
            received.delete()
        }
        if (graph != null) restartApp("a snapshot arrived while the engine ran")
        else importPendingSnapshot()?.let { startFromSnapshot(it) }
    }

    /**
     * The watch handed the pump back. The engine stops with the app process, which restarts at once without
     * it (there is no session any more). A moment later, so the hand back message is sent first.
     */
    fun afterHandback() {
        graph?.appScope?.launch {
            delay(HANDBACK_RESTART_DELAY_MS)
            restartApp("the pump was handed back")
        }
    }

    /** True while the engine holds a pump session. Reads the settings file only. */
    fun hasSession(): Boolean {
        val prefs = context.getSharedPreferences(EngineBindings.PREFERENCES_FILE, Context.MODE_PRIVATE)
        return prefs.getString(MedtrumStringNonKey.SnInput.key, "").orEmpty().isNotEmpty() &&
            !prefs.getBoolean(MedtrumBooleanNonKey.SessionMovedAway.key, false)
    }

    /** The engine's screen, for the companion's menu. */
    fun screenIntent(context: Context): Intent = Intent(context, MedtrumWatchActivity::class.java)

    /** A message from the phone that is the engine's business. False when it is not. */
    fun onMessageFromPhone(data: EventData): Boolean = when (data) {
        is EventData.MedtrumSessionToWatch -> {
            start {
                it.medtrumWatchLink.start()
                it.medtrumWatchLink.receiveSession(data.session)
            }
            true
        }

        else                               -> false
    }

    /**
     * New readings from the watch's own G7 (companion). Kept only while the engine runs: in companion mode
     * the engine is not built for them.
     */
    fun onGlucose(readings: List<GV>) {
        if (!started) return
        val graph = graph
        graph?.appScope?.launch { graph.watchG7Source.store(readings) }
    }

    private class Snapshot(val settings: Map<String, String>, val pumpSession: String)

    private fun pendingSnapshotFile() = File(context.filesDir, PENDING_SNAPSHOT)

    /**
     * Unpacks a waiting snapshot: its database becomes the engine's database (before anything opens it),
     * its settings and pump session are returned to be applied at the start. A snapshot from another
     * database version is refused: the engine could not open it.
     */
    private fun importPendingSnapshot(): Snapshot? {
        val file = pendingSnapshotFile()
        if (!file.exists()) return null
        try {
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
            }
            val manifest = HandoverSnapshot.decodeLines(entries[HandoverSnapshot.MANIFEST]?.decodeToString().orEmpty())
            val version = manifest[HandoverSnapshot.KEY_DATABASE_VERSION]?.toIntOrNull()
            if (version != DATABASE_VERSION) {
                Log.e(TAG, "Handover snapshot refused: database version $version, the engine has $DATABASE_VERSION. Build the phone and the watch from the same code.")
                return null
            }
            val database = entries[HandoverSnapshot.DATABASE] ?: run {
                Log.e(TAG, "Handover snapshot refused: no database in it")
                return null
            }
            // Before the engine is built: its config reads this
            val engineeringMode = manifest[HandoverSnapshot.KEY_ENGINEERING_MODE].toBoolean()
            context.getSharedPreferences(EngineConfig.STATE_FILE, Context.MODE_PRIVATE).edit()
                .putBoolean(EngineConfig.ENGINEERING_MODE_KEY, engineeringMode).commit()
            val target = context.getDatabasePath(DATABASE_FILE)
            target.parentFile?.mkdirs()
            listOf("", "-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
            target.writeBytes(database)
            Log.i(TAG, "Handover snapshot taken in: database ${database.size / 1024} KB, made ${manifest[HandoverSnapshot.KEY_CREATED_AT]} by ${manifest[HandoverSnapshot.KEY_BUILD_VERSION]}, engineering mode $engineeringMode")
            return Snapshot(
                settings = HandoverSnapshot.decodeLines(entries[HandoverSnapshot.SETTINGS]?.decodeToString().orEmpty()),
                pumpSession = entries[HandoverSnapshot.PUMP_SESSION]?.decodeToString().orEmpty()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Handover snapshot could not be read", e)
            return null
        } finally {
            file.delete()
        }
    }

    /**
     * Starts the engine from a snapshot: the phone's settings go in before the plugins start, keeping the
     * engine's own pump choice (the virtual pump while the engine is tested). Then the pump session, and the
     * loop in open loop: it suggests, it does not dose.
     */
    private fun startFromSnapshot(snapshot: Snapshot) {
        start(settings = snapshot.settings) { graph ->
            graph.medtrumWatchLink.start()
            if (snapshot.pumpSession.isNotEmpty()) graph.medtrumWatchLink.receiveSession(snapshot.pumpSession)
            graph.appScope.launch { runCatching { openLoop(graph) }.onFailure { graph.aapsLogger.error(LTag.CORE, "Engine: open loop failed", it) } }
        }
    }

    private suspend fun openLoop(graph: EngineGraph) {
        val profile = graph.profileFunction.getProfile()
        if (profile == null) {
            graph.aapsLogger.warn(LTag.CORE, "Engine: no profile in the snapshot, the loop stays as it was")
            graph.medtrumWatchLink.note("Engine: no profile in the snapshot, the loop stays as it was")
            return
        }
        val done = graph.loop.handleRunningModeChange(RM.Mode.OPEN_LOOP, Action.OPEN_LOOP_MODE, Sources.Aaps, profile = profile)
        graph.aapsLogger.info(LTag.CORE, "Engine: open loop ${if (done) "set" else "could not be set"}")
        graph.medtrumWatchLink.note("Engine: profile ${graph.profileFunction.getProfileName()}, open loop ${if (done) "set" else "could not be set"}")
    }

    /** Stops the app process and starts it again a moment later (the alarm brings it back). */
    private fun restartApp(reason: String) {
        Log.i(TAG, "Restarting the watch app: $reason")
        val intent = PendingIntent.getBroadcast(context, 0, Intent(context, EngineRestartReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java)
            .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RESTART_DELAY_MS, intent)
        Process.killProcess(Process.myPid())
    }

    /** Fills the fields of an engine class that Android built (screen, service, receiver, worker). */
    @Suppress("UNCHECKED_CAST")
    fun injectMembers(target: Any): Boolean {
        val injector = graph().contributedMemberInjectors[target::class] ?: return false
        (injector as MembersInjector<Any>).injectMembers(target)
        return true
    }

    /**
     * Starts the engine once, as `MainApp` starts the phone: the plugin list, then the plugins the settings
     * enable, then the running mode check. [then] runs after that, on the engine's scope.
     */
    private fun start(settings: Map<String, String>? = null, then: (EngineGraph) -> Unit) {
        val graph = graph()
        graph.appScope.launch {
            // The engine must never take the watch app down: a failure is logged and the companion goes on
            try {
                startMutex.withLock {
                    if (!started) {
                        graph.startEngine(settings)
                        started = true
                    }
                }
                then(graph)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.aapsLogger.error(LTag.CORE, "Engine: start failed", e)
                runCatching { graph.medtrumWatchLink.note("Engine: start failed: ${e.javaClass.simpleName} ${e.message}") }
            }
        }
    }

    @Synchronized
    private fun graph(): EngineGraph = graph ?: createGraphFactory<EngineGraph.Factory>()
        .create(
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            context = context,
            memberInjector = memberInjector,
            databaseConfig = DatabaseConfig(inMemory = false, fileName = DATABASE_FILE),
            buildInfo = buildInfo,
            companion = companion,
            coreObjects = CoreObjectsGraph
        )
        .also { graph = it }

    /** Its own database file, so it is never taken for anything else on the watch. */
    private const val DATABASE_FILE = "aaps_engine.db"

    private const val PENDING_SNAPSHOT = "handover/pending_snapshot.zip"
    private const val RESTART_DELAY_MS = 2_000L
    private const val HANDBACK_RESTART_DELAY_MS = 5_000L
    private const val TAG = "AAPS-Engine"

    private val startMutex = Mutex()
    @Volatile private var started = false
}

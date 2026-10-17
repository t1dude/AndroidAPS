package app.aaps.implementation.handover

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.handover.HandoverDatabase
import app.aaps.core.interfaces.handover.HandoverSnapshot
import app.aaps.core.interfaces.handover.HandoverSnapshotWriter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Writes the watch handover snapshot (see [HandoverSnapshot]): the cut down database, the exportable settings
 * and the pump session, in one zip file.
 *
 * Only exportable settings go, as in a settings export: the ones that describe this phone rather than the
 * therapy (a paired device, a token, the Nightscout address) stay here.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class HandoverSnapshotWriterImpl(
    private val database: HandoverDatabase,
    private val store: KeyValueStore,
    private val preferences: Preferences,
    private val config: Config,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger
) : HandoverSnapshotWriter {

    override suspend fun write(path: String, pumpSession: String): Boolean = withContext(aapsIoDispatcher) {
        val target = File(path)
        val databaseCopy = File(target.parentFile, target.name + ".db")
        runCatching {
            target.parentFile?.mkdirs()
            target.delete()
            databaseCopy.delete()
            val now = dateUtil.now()
            database.writeSnapshot(
                path = databaseCopy.absolutePath,
                since = now - HandoverSnapshot.WINDOW_HOURS.hours.inWholeMilliseconds,
                dailyDosesSince = now - HandoverSnapshot.DAILY_DOSE_DAYS.days.inWholeMilliseconds
            )
            val settings = store.getAll()
                .filterKeys { preferences.isExportableKey(it) }
                .mapNotNull { (key, value) -> value?.let { key to it.toString() } }
                .toMap()
            val manifest = mapOf(
                HandoverSnapshot.KEY_DATABASE_VERSION to database.version.toString(),
                HandoverSnapshot.KEY_BUILD_VERSION to config.BUILD_VERSION,
                HandoverSnapshot.KEY_CREATED_AT to now.toString()
            )
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.putText(HandoverSnapshot.MANIFEST, HandoverSnapshot.encodeLines(manifest))
                zip.putText(HandoverSnapshot.SETTINGS, HandoverSnapshot.encodeLines(settings))
                zip.putText(HandoverSnapshot.PUMP_SESSION, pumpSession)
                zip.putNextEntry(ZipEntry(HandoverSnapshot.DATABASE))
                databaseCopy.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            aapsLogger.info(
                LTag.CORE,
                "Handover snapshot written: ${target.length() / 1024} KB, database ${databaseCopy.length() / 1024} KB, ${settings.size} settings"
            )
        }.onFailure {
            aapsLogger.error(LTag.CORE, "Handover snapshot could not be written", it)
            target.delete()
        }.also {
            databaseCopy.delete()
        }.isSuccess
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray())
        closeEntry()
    }
}

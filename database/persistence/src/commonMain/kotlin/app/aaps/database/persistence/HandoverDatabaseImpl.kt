package app.aaps.database.persistence

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.handover.HandoverDatabase
import app.aaps.database.AppRepository
import app.aaps.database.DATABASE_VERSION
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext

/** The database side of the watch handover snapshot. See `AppRepository.writeHandoverSnapshot`. */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class HandoverDatabaseImpl(
    private val repository: AppRepository
) : HandoverDatabase {

    override val version: Int = DATABASE_VERSION

    override suspend fun writeSnapshot(path: String, since: Long, dailyDosesSince: Long) = withContext(aapsIoDispatcher) {
        repository.writeHandoverSnapshot(path, since, dailyDosesSince)
    }
}

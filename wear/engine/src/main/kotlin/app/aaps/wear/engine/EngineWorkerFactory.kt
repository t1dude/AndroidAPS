package app.aaps.wear.engine

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * Builds the engine's workers (keep alive, running mode expiry, scene expiry), as `MetroWorkerFactory`
 * builds the phone's. Null for any other class: WorkManager then builds its own internal workers itself.
 */
class EngineWorkerFactory : WorkerFactory() {

    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        WatchEngine.workerCreator(workerClassName)?.create(appContext, workerParameters)
}

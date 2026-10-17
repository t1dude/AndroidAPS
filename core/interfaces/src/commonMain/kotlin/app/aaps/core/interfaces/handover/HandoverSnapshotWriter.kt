package app.aaps.core.interfaces.handover

/** Writes the handover snapshot file (see [HandoverSnapshot]) on the phone. */
interface HandoverSnapshotWriter {

    /** Writes the snapshot with [pumpSession] to [path]. False when it could not be written; the reason is logged. */
    suspend fun write(path: String, pumpSession: String): Boolean
}

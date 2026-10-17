package app.aaps.core.interfaces.handover

/** The database side of the handover snapshot (see [HandoverSnapshot]). */
interface HandoverDatabase {

    /** The version of this build's database. A snapshot opens only where the version is the same. */
    val version: Int

    /**
     * Writes a copy of the database to [path], cut down to the records from [since] on, the ones in force at
     * [since], and the daily doses from [dailyDosesSince] on. This database is only read.
     */
    suspend fun writeSnapshot(path: String, since: Long, dailyDosesSince: Long)
}

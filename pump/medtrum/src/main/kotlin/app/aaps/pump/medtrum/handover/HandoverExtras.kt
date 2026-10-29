package app.aaps.pump.medtrum.handover

import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.medtrum.comm.enums.BasalType

/**
 * What travels with a patch session besides the driver's own values (handover). Kept in the session as
 * extra entries, so it goes along every way a session moves: to the watch, back, and in a file.
 *
 * - [tdds]: the total daily doses of the last finished days. Dynamic ISF needs 7 of them on the device
 *   that runs the loop, from its first cycle.
 * - [temporaryBasal]: the temporary basal running when the session left, with its duration. The patch
 *   reports a running temporary basal without its duration, so the receiving device would not know when
 *   it ends.
 * - [basalStatus]: the basal the patch reported at the sender's last read, for the receiver's database.
 * - [movedOutAt]: when the session first left the phone. The phone recalculates its daily doses from that
 *   day on when the session comes back.
 */
data class HandoverExtras(
    val tdds: List<HandoverTdd> = emptyList(),
    val temporaryBasal: HandoverTemporaryBasal? = null,
    val basalStatus: MedtrumBasalStatus? = null,
    val movedOutAt: Long? = null
) {

    fun toValues(): Map<String, String> = buildMap {
        tdds.forEachIndexed { index, tdd -> put(TDD_PREFIX + index, tdd.encode()) }
        temporaryBasal?.let { put(TEMPORARY_BASAL, it.encode()) }
        basalStatus?.let { put(BASAL_STATUS, it.encode()) }
        movedOutAt?.let { put(MOVED_OUT_AT, it.toString()) }
    }

    companion object {

        const val PREFIX = "handover_"
        private const val TDD_PREFIX = PREFIX + "tdd_"
        private const val TEMPORARY_BASAL = PREFIX + "temporary_basal"
        private const val BASAL_STATUS = PREFIX + "basal_status"
        private const val MOVED_OUT_AT = PREFIX + "moved_out_at"

        /** Entries that cannot be read are left out: a session from an older build has none at all. */
        fun from(values: Map<String, String>): HandoverExtras = HandoverExtras(
            tdds = values.filterKeys { it.startsWith(TDD_PREFIX) }.values.mapNotNull { HandoverTdd.decode(it) }.sortedBy { it.timestamp },
            temporaryBasal = values[TEMPORARY_BASAL]?.let { HandoverTemporaryBasal.decode(it) },
            basalStatus = values[BASAL_STATUS]?.let { MedtrumBasalStatus.decode(it) },
            movedOutAt = values[MOVED_OUT_AT]?.toLongOrNull()
        )
    }
}

/** A total daily dose: [timestamp] is the start of the day. */
data class HandoverTdd(val timestamp: Long, val basalAmount: Double, val bolusAmount: Double, val totalAmount: Double, val carbs: Double) {

    fun encode(): String = listOf(timestamp, basalAmount, bolusAmount, totalAmount, carbs).joinToString(",")

    companion object {

        fun decode(text: String): HandoverTdd? {
            val parts = text.split(',')
            if (parts.size != 5) return null
            return HandoverTdd(
                timestamp = parts[0].toLongOrNull() ?: return null,
                basalAmount = parts[1].toDoubleOrNull() ?: return null,
                bolusAmount = parts[2].toDoubleOrNull() ?: return null,
                totalAmount = parts[3].toDoubleOrNull() ?: return null,
                carbs = parts[4].toDoubleOrNull() ?: return null
            )
        }
    }
}

/**
 * A running temporary basal as AAPS has it: [rate] in U/h when [isAbsolute], else in percent. [type] is the
 * name of a `PumpSync.TemporaryBasalType`, or empty.
 */
data class HandoverTemporaryBasal(val timestamp: Long, val duration: Long, val rate: Double, val isAbsolute: Boolean, val type: String, val pumpId: Long?) {

    val end: Long get() = timestamp + duration

    fun encode(): String = listOf(timestamp, duration, rate, isAbsolute, type, pumpId ?: "").joinToString(",")

    companion object {

        /** The temporary basal AAPS knows is running, as it goes along with the session. */
        fun of(temporaryBasal: PumpSync.PumpState.TemporaryBasal): HandoverTemporaryBasal =
            with(temporaryBasal) { HandoverTemporaryBasal(timestamp, duration, rate, isAbsolute, type.name, pumpId) }

        fun decode(text: String): HandoverTemporaryBasal? {
            val parts = text.split(',')
            if (parts.size != 6) return null
            return HandoverTemporaryBasal(
                timestamp = parts[0].toLongOrNull() ?: return null,
                duration = parts[1].toLongOrNull() ?: return null,
                rate = parts[2].toDoubleOrNull() ?: return null,
                isAbsolute = parts[3].toBooleanStrictOrNull() ?: return null,
                type = parts[4],
                pumpId = parts[5].toLongOrNull()
            )
        }
    }
}

/** The basal the patch reported in a status read, as `MedtrumPump.handleBasalStatusUpdate` takes it. */
data class MedtrumBasalStatus(val type: BasalType, val rate: Double, val sequence: Int, val patchId: Long, val startTime: Long, val readAt: Long) {

    fun encode(): String = listOf(type.name, rate, sequence, patchId, startTime, readAt).joinToString(",")

    companion object {

        fun decode(text: String): MedtrumBasalStatus? {
            val parts = text.split(',')
            if (parts.size != 6) return null
            return MedtrumBasalStatus(
                type = BasalType.entries.firstOrNull { it.name == parts[0] } ?: return null,
                rate = parts[1].toDoubleOrNull() ?: return null,
                sequence = parts[2].toIntOrNull() ?: return null,
                patchId = parts[3].toLongOrNull() ?: return null,
                startTime = parts[4].toLongOrNull() ?: return null,
                readAt = parts[5].toLongOrNull() ?: return null
            )
        }
    }
}

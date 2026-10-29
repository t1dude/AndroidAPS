package app.aaps.pump.medtrum.handover

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.pump.medtrum.keys.MedtrumBooleanKey
import app.aaps.pump.medtrum.keys.MedtrumBooleanNonKey
import app.aaps.pump.medtrum.keys.MedtrumDoubleNonKey
import app.aaps.pump.medtrum.keys.MedtrumIntKey
import app.aaps.pump.medtrum.keys.MedtrumIntNonKey
import app.aaps.pump.medtrum.keys.MedtrumLongNonKey
import app.aaps.pump.medtrum.keys.MedtrumStringKey
import app.aaps.pump.medtrum.keys.MedtrumStringNonKey

/**
 * A running Medtrum patch session as plain values, to move it to another device and back (handover).
 *
 * The session is the patch serial, the session token the patch was activated with, and the state the
 * driver keeps between connections: pump state, history sequence numbers, patch id and start time, last
 * bolus, basal profile, alarms. The key is derived from the serial, so nothing here is device bound.
 * The Medtrum settings (hourly and daily max, alarms, expiry warning) go along too: the driver writes
 * its settings to the patch, and a device with other settings would change the patch.
 *
 * Moving it does not pair or activate anything: the patch must already be running.
 */
data class MedtrumSession(val exportedAt: Long, val values: Map<String, String>) {

    /** The patch serial, 0 when the session holds no patch. */
    val serial: Long get() = values[MedtrumStringNonKey.SnInput.key]?.toLongOrNull(radix = 16) ?: 0L

    /** Daily doses, temporary basal and basal status that travel with the session. */
    val extras: HandoverExtras get() = HandoverExtras.from(values)

    /** The same session with [extras] in place of the ones it had. */
    fun withExtras(extras: HandoverExtras): MedtrumSession =
        copy(values = values.filterKeys { !it.startsWith(HandoverExtras.PREFIX) } + extras.toValues())

    fun toText(): String = buildString {
        append(HEADER).append('\t').append(exportedAt).append('\n')
        values.toSortedMap().forEach { (key, value) -> append(key).append('\t').append(escape(value)).append('\n') }
    }

    companion object {

        const val HEADER = "medtrum-session-1"
        const val FILE_PREFIX = "Medtrum_session_"

        /**
         * Everything that is moved. [MedtrumBooleanNonKey.SessionMovedAway], [MedtrumStringNonKey.ModeBeforeMoveOut]
         * and [MedtrumIntNonKey.SyncedSequenceAtMoveOut] are about this device, not the patch.
         */
        val keys: List<NonPreferenceKey> =
            (MedtrumBooleanNonKey.entries - MedtrumBooleanNonKey.SessionMovedAway) +
                MedtrumDoubleNonKey.entries + (MedtrumIntNonKey.entries - MedtrumIntNonKey.SyncedSequenceAtMoveOut) + MedtrumLongNonKey.entries +
                (MedtrumStringNonKey.entries - MedtrumStringNonKey.ModeBeforeMoveOut) +
                MedtrumBooleanKey.entries + MedtrumIntKey.entries + MedtrumStringKey.entries

        fun read(preferences: Preferences, now: Long): MedtrumSession =
            MedtrumSession(now, keys.associate { it.key to valueOf(preferences, it) })

        /** Writes every value it knows a key for. Values for keys this build does not have are left out. */
        fun MedtrumSession.writeTo(preferences: Preferences) {
            keys.forEach { key -> values[key.key]?.let { put(preferences, key, it) } }
        }

        fun parse(text: String): MedtrumSession? {
            val lines = text.lines().filter { it.isNotEmpty() }
            val header = lines.firstOrNull()?.split('\t') ?: return null
            if (header.size != 2 || header[0] != HEADER) return null
            val exportedAt = header[1].toLongOrNull() ?: return null
            val values = lines.drop(1).mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to unescape(line.substring(tab + 1))
            }.toMap()
            return MedtrumSession(exportedAt, values)
        }

        private fun valueOf(preferences: Preferences, key: NonPreferenceKey): String = when (key) {
            is BooleanNonPreferenceKey -> preferences.get(key).toString()
            is IntNonPreferenceKey     -> preferences.get(key).toString()
            is LongNonPreferenceKey    -> preferences.get(key).toString()
            is DoubleNonPreferenceKey  -> preferences.get(key).toString()
            is StringNonPreferenceKey  -> preferences.get(key)
            else                       -> error("No value type for ${key.key}")
        }

        private fun put(preferences: Preferences, key: NonPreferenceKey, value: String) {
            when (key) {
                is BooleanNonPreferenceKey -> value.toBooleanStrictOrNull()?.let { preferences.put(key, it) }
                is IntNonPreferenceKey     -> value.toIntOrNull()?.let { preferences.put(key, it) }
                is LongNonPreferenceKey    -> value.toLongOrNull()?.let { preferences.put(key, it) }
                is DoubleNonPreferenceKey  -> value.toDoubleOrNull()?.let { preferences.put(key, it) }
                is StringNonPreferenceKey  -> preferences.put(key, value)
            }
        }

        // One value per line, separated from its key by a tab
        private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

        private fun unescape(value: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        'n'  -> out.append('\n')
                        't'  -> out.append('\t')
                        else -> out.append(value[i + 1])
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}

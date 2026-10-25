package app.aaps.core.interfaces.handover

/**
 * The handover snapshot: what the phone hands the watch with a pump session, so the watch's AAPS engine
 * starts where the phone stopped. One zip file, sent over a Wear channel (a message is too small for it).
 *
 * - [DATABASE]: a copy of the phone's database cut down to what the loop needs (see `HandoverDatabase`).
 * - [SETTINGS]: the phone's exportable settings, one `name<TAB>value` per line. Device state and secrets are
 *   not exportable, so they do not travel (a paired device's address, the Nightscout token, ...).
 * - [PUMP_SESSION]: the pump driver's session, in the driver's own text form.
 * - [MANIFEST]: `name<TAB>value` lines: [KEY_DATABASE_VERSION], [KEY_BUILD_VERSION], [KEY_CREATED_AT],
 *   [KEY_ENGINEERING_MODE].
 */
object HandoverSnapshot {

    /** The Wear channel path the snapshot is sent on. */
    const val CHANNEL_PATH = "/aaps_handover_snapshot"

    const val MANIFEST = "manifest.txt"
    const val DATABASE = "database.db"
    const val SETTINGS = "settings.txt"
    const val PUMP_SESSION = "pump_session.txt"

    const val KEY_DATABASE_VERSION = "database_version"
    const val KEY_BUILD_VERSION = "build_version"
    const val KEY_CREATED_AT = "created_at"

    /**
     * Whether the phone runs in engineering mode (the `engineering_mode` file in its AAPS extra folder). The
     * watch has no such folder, so it takes the phone's state; without it a dev build keeps closed loop off.
     */
    const val KEY_ENGINEERING_MODE = "engineering_mode"

    /** How far back the records go: autosens looks at 24 hours, the insulin of the hours before still acts. */
    const val WINDOW_HOURS = 30L

    /** Dynamic ISF needs the total daily dose of the last 7 days; one more for the day it is running in. */
    const val DAILY_DOSE_DAYS = 8L

    /** One `name<TAB>value` per line, with tab, newline and backslash escaped. */
    fun encodeLines(values: Map<String, String>): String = buildString {
        values.toSortedMap().forEach { (name, value) -> append(escape(name)).append('\t').append(escape(value)).append('\n') }
    }

    fun decodeLines(text: String): Map<String, String> =
        text.lines().filter { it.isNotEmpty() }.mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else unescape(line.substring(0, tab)) to unescape(line.substring(tab + 1))
        }.toMap()

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

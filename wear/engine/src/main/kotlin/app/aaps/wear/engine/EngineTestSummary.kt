package app.aaps.wear.engine

import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A short summary for the top of the test log the phone gets, so a test of hours (T2) can be judged without
 * reading thousands of lines. Worked out from the lines of the log, so it stays right across app restarts.
 *
 * The test is the part of the log from its last start line (`----- `: a session taken in, or a test run
 * without a patch).
 */
internal object EngineTestSummary {

    /** The loop is started by every G7 reading, so a run is due every 5 minutes. */
    private const val LOOP_INTERVAL_MIN = 5

    private val battery = Regex("""\(battery (\d+) %\)""")

    fun of(lines: List<String>, now: Long): List<String> {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val entries = lines.mapNotNull { line ->
            if (line.length < TIME_LENGTH + 1) return@mapNotNull null
            val at = runCatching { format.parse(line.substring(0, TIME_LENGTH))?.time }.getOrNull() ?: return@mapNotNull null
            Entry(at, line.substring(TIME_LENGTH + 1))
        }
        val startIndex = entries.indexOfLast { it.text.startsWith("----- ") }
        if (startIndex < 0) return listOf("===== Summary: no test start in the log", "")
        val test = entries.drop(startIndex)
        val start = test.first().at
        val clock = SimpleDateFormat("HH:mm", Locale.US)
        val minutes = ((now - start) / 60_000L).toInt()

        val loopLines = test.filter { it.text.startsWith("Loop ran (") || it.text.startsWith("Loop: ") }
        val withResult = loopLines.count { it.text.startsWith("Loop ran (") }
        // The gaps between loop runs, and from the start to the first and from the last to now
        val times = listOf(start) + loopLines.map { it.at } + now
        val gap = times.zipWithNext().maxByOrNull { (from, to) -> to - from }
        val gapText = gap?.let { (from, to) -> "${(to - from) / 60_000L} min (from ${clock.format(from)})" } ?: "-"

        val levels = test.mapNotNull { entry -> battery.find(entry.text)?.groupValues?.get(1)?.toIntOrNull()?.let { entry.at to it } }
        val batteryText = if (levels.size < 2) "-" else {
            val (fromAt, from) = levels.first()
            val (toAt, to) = levels.last()
            val hours = (toAt - fromAt) / 3_600_000.0
            val perHour = if (hours > 0) " (about ${((from - to) / hours * 10).roundToInt() / 10.0} % per hour)" else ""
            "$from % at ${clock.format(fromAt)}, $to % at ${clock.format(toAt)}$perHour"
        }

        return listOf(
            "===== Summary of the last test (made ${format.format(now)})",
            "Test: ${test.first().text.removePrefix("----- ")}",
            "Since ${format.format(start)}, ${minutes / 60} h ${minutes % 60} min",
            "Loop runs: $withResult with a result, ${loopLines.size - withResult} without (about ${minutes / LOOP_INTERVAL_MIN} due)",
            "Longest time without a loop run: $gapText",
            "Keep alive late: ${test.count { it.text.contains("KeepAlive fail") }} times",
            "Engine restarts: ${test.count { it.text.startsWith("Engine started") }}",
            "Patch reads: ${test.count { it.text.startsWith("Read ok") }} ok, ${test.count { it.text.startsWith("Read failed") || (it.text.startsWith("Step ") && it.text.contains(" failed")) }} failed",
            "Battery: $batteryText",
            "===== Log",
            ""
        )
    }

    private class Entry(val at: Long, val text: String)

    /** `yyyy-MM-dd HH:mm:ss`, as `EngineTestLog` writes it. */
    private const val TIME_LENGTH = 19
}

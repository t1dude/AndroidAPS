package app.aaps.wear.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/** The summary on top of the test log the phone gets: a test of hours must be judged from it alone. */
class EngineTestSummaryTest {

    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun at(time: String): Long = format.parse("2026-10-29 $time")!!.time

    private fun line(time: String, text: String) = "2026-10-29 $time $text"

    @Test
    fun theLastTestIsSummed() {
        val lines = listOf(
            // An earlier test must not count
            line("10:00:00", "----- Took in the session of patch fc41637c, exported 10:00:00"),
            line("10:05:00", "Loop ran (OpenAPS SMB): temp basal 0.00 U/h for 30 min, SMB 0.00 U (battery 90 %)"),
            line("22:00:00", "Engine started: 15 of 25 plugins on, pump VirtualPumpPlugin, glucose WatchG7SourcePlugin, engineering mode true"),
            line("22:00:01", "----- Engine test run: no patch, the phone keeps it. Virtual pump, LGS."),
            line("22:05:00", "Loop ran (OpenAPS SMB): temp basal 0.50 U/h for 30 min, SMB 0.00 U (battery 80 %)"),
            line("22:10:00", "Loop: Loop deaktivert av bruker (battery 80 %)"),
            line("22:15:00", "E/CORE: KeepAlive fail"),
            // 25 minutes without a run, then the app came back
            line("22:35:00", "Engine started: 15 of 25 plugins on, pump VirtualPumpPlugin, glucose WatchG7SourcePlugin, engineering mode true"),
            line("22:35:30", "Loop ran (OpenAPS SMB): temp basal 0.50 U/h for 30 min, SMB 0.00 U (battery 78 %)"),
            line("23:00:00", "Loop ran (OpenAPS SMB): temp basal 0.50 U/h for 30 min, SMB 0.00 U (battery 76 %)"),
            "a line without a time"
        )

        val summary = EngineTestSummary.of(lines, now = at("23:01:00"))

        assertThat(summary).contains("Test: Engine test run: no patch, the phone keeps it. Virtual pump, LGS.")
        assertThat(summary).contains("Since 2026-10-29 22:00:01, 1 h 0 min")
        assertThat(summary).contains("Loop runs: 3 with a result, 1 without (about 12 due)")
        assertThat(summary).contains("Longest time without a loop run: 25 min (from 22:10)")
        assertThat(summary).contains("Keep alive late: 1 times")
        assertThat(summary).contains("Engine restarts: 1")
        assertThat(summary).contains("Battery: 80 % at 22:05, 76 % at 23:00 (about 4.4 % per hour)")
        assertThat(summary.last()).isEmpty()
    }

    @Test
    fun aLogWithoutATestSaysSo() {
        val summary = EngineTestSummary.of(listOf(line("10:00:00", "Reading every 5 minutes: on")), now = at("10:01:00"))

        assertThat(summary.first()).isEqualTo("===== Summary: no test start in the log")
    }
}

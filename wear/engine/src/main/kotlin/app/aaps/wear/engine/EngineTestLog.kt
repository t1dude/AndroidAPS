package app.aaps.wear.engine

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The engine's test log on the watch: a file that goes to the phone at hand back, at the end of a test run,
 * or on request. The watch's own log is too short to hold a test and cannot be read without a cable. One
 * writer for everything that writes to it (the Medtrum test link, the reporter, the engine's logger), so lines
 * never mix.
 *
 * It holds a night of loop runs (about 85 KB an hour). It goes to the phone whole, as a file over a Wear
 * channel, with a summary of the last test on top ([EngineTestSummary]).
 */
object EngineTestLog {

    private const val FILE = "medtrum_watch_test.log"
    private const val MAX_BYTES = 2_000_000L
    private const val KEEP_CHARS = 1_500_000

    private val lock = Any()

    /** Appends [text] with the time and returns the line as written. Never throws: a failed write is lost. */
    fun append(context: Context, text: String, at: Long = System.currentTimeMillis()): String {
        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))} $text"
        synchronized(lock) {
            runCatching {
                val file = file(context)
                file.appendText(line + "\n")
                if (file.length() > MAX_BYTES) file.writeText(file.readText().takeLast(KEEP_CHARS))
            }
        }
        return line
    }

    fun lastLines(context: Context, count: Int): List<String> = synchronized(lock) {
        runCatching { file(context).takeIf { it.exists() }?.readLines()?.takeLast(count) }.getOrNull().orEmpty()
    }

    /** Writes the whole log to [target], with the summary of the last test on top. False when it could not. */
    fun writeForPhone(context: Context, target: File): Boolean = synchronized(lock) {
        runCatching {
            val text = file(context).takeIf { it.exists() }?.readText().orEmpty()
            val summary = EngineTestSummary.of(text.lines(), System.currentTimeMillis())
            target.parentFile?.mkdirs()
            target.writeText(summary.joinToString("\n") + text)
        }.isSuccess
    }

    private fun file(context: Context) = File(context.filesDir, FILE)
}

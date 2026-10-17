package app.aaps.wear.engine

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The engine's test log on the watch: a file that goes to the phone with the session at hand back, or on
 * request. The watch's own log is too short to hold a test and cannot be read without a cable. One writer for
 * everything that writes to it (the Medtrum test link, the reporter, the engine's logger), so lines never mix.
 */
object EngineTestLog {

    private const val FILE = "medtrum_watch_test.log"
    private const val MAX_BYTES = 400_000L
    private const val KEEP_CHARS = 200_000

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

    /** The newest [chars] characters: a message to the phone may not be much larger. */
    fun newest(context: Context, chars: Int): String = synchronized(lock) {
        runCatching { file(context).takeIf { it.exists() }?.readText()?.takeLast(chars) }.getOrNull().orEmpty()
    }

    private fun file(context: Context) = File(context.filesDir, FILE)
}

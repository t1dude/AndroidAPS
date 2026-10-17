package app.aaps.wear.engine

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

/**
 * The engine's logger: everything goes to the normal log, and what is needed to follow the loop from the
 * test log ([EngineTestLog]) goes there too: every line of the loop and the algorithm (APS) and of the
 * calculation that starts the loop on a new reading (WORKER), and every warning and error of the whole engine. Found that way: the loop stopped without an error event, and only
 * its own log lines say where.
 */
class EngineTeeLogger(private val delegate: AAPSLogger, private val context: Context) : AAPSLogger by delegate {

    override fun debug(tag: LTag, message: String) {
        delegate.debug(tag, message)
        if (tag in FOLLOWED) copy("D", tag, message)
    }

    override fun debug(tag: LTag, accessor: () -> String) {
        delegate.debug(tag, accessor)
        if (tag in FOLLOWED) copy("D", tag, accessor())
    }

    override fun debug(tag: LTag, format: String, vararg arguments: Any?) {
        delegate.debug(tag, format, *arguments)
        if (tag in FOLLOWED) copy("D", tag, format(format, arguments))
    }

    override fun debug(enable: Boolean, tag: LTag, message: String) {
        delegate.debug(enable, tag, message)
        if (enable && tag in FOLLOWED) copy("D", tag, message)
    }

    override fun info(tag: LTag, message: String) {
        delegate.info(tag, message)
        if (tag in FOLLOWED) copy("I", tag, message)
    }

    override fun info(tag: LTag, format: String, vararg arguments: Any?) {
        delegate.info(tag, format, *arguments)
        if (tag in FOLLOWED) copy("I", tag, format(format, arguments))
    }

    override fun warn(tag: LTag, message: String) {
        delegate.warn(tag, message)
        copy("W", tag, message)
    }

    override fun warn(tag: LTag, format: String, vararg arguments: Any?) {
        delegate.warn(tag, format, *arguments)
        copy("W", tag, format(format, arguments))
    }

    override fun error(tag: LTag, message: String) {
        delegate.error(tag, message)
        copy("E", tag, message)
    }

    override fun error(tag: LTag, message: String, throwable: Throwable) {
        delegate.error(tag, message, throwable)
        copy("E", tag, "$message: $throwable")
    }

    override fun error(tag: LTag, format: String, vararg arguments: Any?) {
        delegate.error(tag, format, *arguments)
        copy("E", tag, format(format, arguments))
    }

    override fun error(message: String) {
        delegate.error(message)
        copy("E", LTag.CORE, message)
    }

    override fun error(message: String, throwable: Throwable) {
        delegate.error(message, throwable)
        copy("E", LTag.CORE, "$message: $throwable")
    }

    override fun error(format: String, vararg arguments: Any?) {
        delegate.error(format, *arguments)
        copy("E", LTag.CORE, format(format, arguments))
    }

    private fun format(format: String, arguments: Array<out Any?>): String = runCatching { String.format(format, *arguments) }.getOrDefault(format)

    /** One line per entry, kept short: the test log goes to the phone in one message. */
    private fun copy(level: String, tag: LTag, message: String) {
        EngineTestLog.append(context, "$level/${tag.tag}: ${message.replace('\n', ' ').take(MAX_LINE)}")
    }

    private companion object {

        const val MAX_LINE = 400
        val FOLLOWED = setOf(LTag.APS, LTag.WORKER)
    }
}

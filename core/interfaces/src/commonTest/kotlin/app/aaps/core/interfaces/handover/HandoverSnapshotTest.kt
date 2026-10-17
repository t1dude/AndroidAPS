package app.aaps.core.interfaces.handover

import kotlin.test.Test
import kotlin.test.assertEquals

/** The `name<TAB>value` lines of the handover snapshot: settings come back exactly as they went. */
class HandoverSnapshotTest {

    @Test
    fun valuesComeBackTheSame() {
        val values = mapOf(
            "units" to "mmol",
            "with_tab" to "a\tb",
            "with_newline" to "line 1\nline 2",
            "with_backslash" to "c:\\path\\n",
            "empty" to ""
        )
        assertEquals(values, HandoverSnapshot.decodeLines(HandoverSnapshot.encodeLines(values)))
    }

    @Test
    fun aLineWithoutNameIsLeftOut() {
        assertEquals(mapOf("a" to "1"), HandoverSnapshot.decodeLines("a\t1\n\tno name\nno tab\n"))
    }
}

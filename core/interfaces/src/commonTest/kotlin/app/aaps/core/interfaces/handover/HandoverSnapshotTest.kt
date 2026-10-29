package app.aaps.core.interfaces.handover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun thePumpIdentityComesBackTheSame() {
        val identity = HandoverSnapshot.PumpIdentity(type = "Medtrum Nano", serial = "fc41637c", since = 1_793_290_348_000L)
        val manifest = HandoverSnapshot.decodeLines(HandoverSnapshot.encodeLines(mapOf("created_at" to "1") + identity.toManifest()))
        assertEquals(identity, HandoverSnapshot.PumpIdentity.of(manifest))
    }

    /** A phone that has no pump registered yet, or a snapshot from a build before the identity went along. */
    @Test
    fun withoutAPumpIdentityThereIsNone() {
        assertNull(HandoverSnapshot.PumpIdentity.of(mapOf("created_at" to "1")))
        assertNull(HandoverSnapshot.PumpIdentity.of(mapOf(HandoverSnapshot.KEY_PUMP_TYPE to "Medtrum Nano", HandoverSnapshot.KEY_PUMP_SERIAL to "", HandoverSnapshot.KEY_PUMP_SINCE to "1")))
    }
}

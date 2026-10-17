package app.aaps.pump.medtrum.handover

import app.aaps.pump.medtrum.keys.MedtrumBooleanNonKey
import app.aaps.pump.medtrum.keys.MedtrumIntNonKey
import app.aaps.pump.medtrum.keys.MedtrumLongNonKey
import app.aaps.pump.medtrum.keys.MedtrumStringNonKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MedtrumSessionTest {

    private val session = MedtrumSession(
        exportedAt = 1_700_000_000_000L,
        values = mapOf(
            MedtrumStringNonKey.SnInput.key to "a1b2c3d4",
            MedtrumLongNonKey.SessionToken.key to "123456789",
            MedtrumIntNonKey.SyncedSequenceNumber.key to "42",
            MedtrumStringNonKey.ActiveAlarms.key to "with\ttab, new\nline and back\\slash"
        )
    )

    @Test
    fun theFileReadsBackTheSame() {
        val parsed = MedtrumSession.parse(session.toText())!!
        assertThat(parsed).isEqualTo(session)
        assertThat(parsed.serial).isEqualTo(0xa1b2c3d4L)
    }

    @Test
    fun theMovedAwayFlagStaysOnItsOwnDevice() {
        assertThat(MedtrumSession.keys).doesNotContain(MedtrumBooleanNonKey.SessionMovedAway)
        assertThat(MedtrumSession.keys).doesNotContain(MedtrumStringNonKey.ModeBeforeMoveOut)
        assertThat(MedtrumSession.keys).contains(MedtrumLongNonKey.SessionToken)
        assertThat(MedtrumSession.keys).contains(MedtrumIntNonKey.CurrentSequenceNumber)
    }

    @Test
    fun anotherFileIsRefused() {
        assertThat(MedtrumSession.parse("something else\t1\nkey\tvalue")).isNull()
        assertThat(MedtrumSession.parse("")).isNull()
        assertThat(MedtrumSession(0L, emptyMap()).serial).isEqualTo(0L)
    }
}

package app.aaps.pump.medtrum.handover

import app.aaps.pump.medtrum.comm.enums.BasalType
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

    private val extras = HandoverExtras(
        tdds = listOf(
            HandoverTdd(timestamp = 1_699_920_000_000L, basalAmount = 14.2, bolusAmount = 17.9, totalAmount = 32.1, carbs = 150.0),
            HandoverTdd(timestamp = 1_699_833_600_000L, basalAmount = 13.0, bolusAmount = 15.5, totalAmount = 28.5, carbs = 120.0)
        ),
        temporaryBasal = HandoverTemporaryBasal(1_700_000_000_000L, 120 * 60_000L, 0.0, isAbsolute = true, type = "NORMAL", pumpId = 1_700_000_000_000L),
        basalStatus = MedtrumBasalStatus(BasalType.ABSOLUTE_TEMP, 0.0, sequence = 284, patchId = 192L, startTime = 1_700_000_000_000L, readAt = 1_700_000_300_000L),
        movedOutAt = 1_700_000_100_000L
    )

    @Test
    fun theExtrasTravelWithTheSession() {
        val parsed = MedtrumSession.parse(session.withExtras(extras).toText())!!
        // In date order, whatever order they were sent in
        assertThat(parsed.extras).isEqualTo(extras.copy(tdds = extras.tdds.sortedBy { it.timestamp }))
        assertThat(parsed.serial).isEqualTo(0xa1b2c3d4L)
    }

    @Test
    fun newExtrasReplaceTheOldOnes() {
        val updated = session.withExtras(extras).withExtras(extras.copy(tdds = extras.tdds.take(1), temporaryBasal = null))
        assertThat(updated.extras.tdds).hasSize(1)
        assertThat(updated.extras.temporaryBasal).isNull()
        assertThat(updated.extras.basalStatus).isEqualTo(extras.basalStatus)
    }

    @Test
    fun aSessionWithoutExtrasHasNone() {
        assertThat(session.extras).isEqualTo(HandoverExtras())
        // An entry that cannot be read is left out, the others stay
        val broken = session.copy(values = session.values + extras.toValues() + ("handover_temporary_basal" to "not,a,basal"))
        assertThat(broken.extras.temporaryBasal).isNull()
        assertThat(broken.extras.tdds).hasSize(2)
    }

    @Test
    fun anotherFileIsRefused() {
        assertThat(MedtrumSession.parse("something else\t1\nkey\tvalue")).isNull()
        assertThat(MedtrumSession.parse("")).isNull()
        assertThat(MedtrumSession(0L, emptyMap()).serial).isEqualTo(0L)
    }
}

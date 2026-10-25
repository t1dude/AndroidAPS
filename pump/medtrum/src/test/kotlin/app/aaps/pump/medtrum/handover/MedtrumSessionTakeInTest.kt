package app.aaps.pump.medtrum.handover

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.handover.HandoverSnapshotWriter
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.TemporaryBasalStorage
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.utils.Translator
import app.aaps.pump.medtrum.MedtrumPlugin
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.MedtrumTestBase
import app.aaps.pump.medtrum.R
import app.aaps.pump.medtrum.comm.enums.BasalType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.math.abs

/**
 * The take in step of a handover (`MedtrumSessionTransfer.applyExtras`): the phone brings its records up to
 * what the other device knew. Checked on the phone at 20:38 on 2026-10-17 from its log; this keeps it so.
 */
class MedtrumSessionTakeInTest : MedtrumTestBase() {

    @Mock lateinit var fileListProvider: FileListProvider
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var medtrumPlugin: MedtrumPlugin
    @Mock lateinit var loop: Loop
    @Mock lateinit var translator: Translator
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var snapshotWriter: HandoverSnapshotWriter

    /** Kept in memory, so the duration handed to the driver can be seen arriving in its update. */
    private val storage = object : TemporaryBasalStorage {
        val stored = mutableListOf<PumpSync.PumpState.TemporaryBasal>()
        override fun add(temporaryBasal: PumpSync.PumpState.TemporaryBasal) {
            stored += temporaryBasal
        }

        override fun findTemporaryBasal(time: Long, rate: Double): PumpSync.PumpState.TemporaryBasal? =
            stored.firstOrNull { abs(it.timestamp - time) < 60_000L && abs(it.rate - rate) < 0.01 }?.also { stored.remove(it) }
    }

    private lateinit var pump: MedtrumPump
    private lateinit var transfer: MedtrumSessionTransfer

    private val start = 1_792_257_092_000L
    private val thirtyMinutes = 30 * 60_000L

    @BeforeEach
    fun prepare() {
        // The two lines of the take in message
        whenever(rh.gs(R.string.handover_test_basal_applied)).thenReturn("basal applied at %1${'$'}s")
        whenever(rh.gs(R.string.handover_test_tdd_recalculated)).thenReturn("daily doses from %1${'$'}s")
        pump = MedtrumPump(aapsLogger, rh, preferences, dateUtil, pumpSync, storage, bolusProgressData)
        transfer = MedtrumSessionTransfer(
            context, aapsLogger, rh, preferences, dateUtil, fileListProvider, commandQueue, pump, medtrumPlugin, loop,
            profileFunction, translator, rxBus, notificationManager, tddCalculator, pumpSync, storage, persistenceLayer, snapshotWriter
        )
    }

    private fun expectedTemporaryBasal(pumpId: Long?) =
        PumpSync.PumpState.TemporaryBasal(start, thirtyMinutes, 0.35, true, PumpSync.TemporaryBasalType.NORMAL, id = 1L, pumpId = pumpId)

    private fun cameAlong(type: BasalType, startTime: Long = start) = HandoverExtras(
        temporaryBasal = HandoverTemporaryBasal(start, thirtyMinutes, 0.35, isAbsolute = true, type = "NORMAL", pumpId = start),
        basalStatus = MedtrumBasalStatus(type, if (type.isTempBasal()) 0.35 else 0.9, sequence = 311, patchId = 192L, startTime = startTime, readAt = start + 14 * 60_000L)
    )

    @Test
    fun `a temp basal the phone does not have goes in with the duration that came along`() = runTest {
        whenever(pumpSync.expectedPumpState()).thenReturn(PumpSync.PumpState(null, null, null, null, ""))

        transfer.applyExtras(cameAlong(BasalType.ABSOLUTE_TEMP))

        // 30 minutes, keyed by its start on the patch - not the patch's whole life, which is what the driver
        // takes when it does not know the duration
        verify(pumpSync).syncTemporaryBasalWithPumpId(eq(start), any(), eq(thirtyMinutes), eq(true), eq(PumpSync.TemporaryBasalType.NORMAL), eq(start), any(), any())
        assertThat(storage.stored).isEmpty()
    }

    @Test
    fun `a temp basal the phone already has is not added again`() = runTest {
        whenever(pumpSync.expectedPumpState()).thenReturn(PumpSync.PumpState(expectedTemporaryBasal(pumpId = start), null, null, null, ""))

        transfer.applyExtras(cameAlong(BasalType.ABSOLUTE_TEMP))

        verify(pumpSync, never()).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), any(), any(), any(), any())
        // Nothing left behind for a later temp basal to pick up by mistake
        assertThat(storage.stored).isEmpty()
    }

    @Test
    fun `a temp basal that ended on the watch is ended on the phone`() = runTest {
        val end = start + 20 * 60_000L
        whenever(pumpSync.expectedPumpState()).thenReturn(PumpSync.PumpState(expectedTemporaryBasal(pumpId = start), null, null, null, ""))

        transfer.applyExtras(cameAlong(BasalType.STANDARD, startTime = end))

        verify(pumpSync).syncStopTemporaryBasalWithPumpId(eq(end + 250), eq(end + 250), any(), any(), any())
    }

    @Test
    fun `the daily doses are calculated again from the day the session left`() = runTest {
        val movedOutAt = start - 60 * 60_000L

        transfer.applyExtras(HandoverExtras(movedOutAt = movedOutAt))

        verify(persistenceLayer).clearCachedTddData(dateUtil.beginOfDay(movedOutAt))
        assertThat(transfer.applyExtras(HandoverExtras(movedOutAt = movedOutAt))).startsWith("daily doses from")
    }

    @Test
    fun `a session without extras changes nothing`() = runTest {
        assertThat(transfer.applyExtras(HandoverExtras())).isNull()

        verify(pumpSync, never()).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), any(), any(), any(), any())
        verify(persistenceLayer, never()).clearCachedTddData(any())
    }
}

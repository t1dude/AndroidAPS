package app.aaps.pump.medtrum.handover

import android.content.Context
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.pump.medtrum.MedtrumPlugin
import app.aaps.pump.medtrum.MedtrumTestBase
import app.aaps.pump.medtrum.ble.MedtrumBleTransportImpl
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.keys.MedtrumBooleanNonKey
import app.aaps.pump.medtrum.keys.MedtrumStringNonKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Handover step 0: while the watch has the patch session, this device never talks to the patch. Only one
 * device may: two that both send commands could give insulin twice.
 *
 * The transport is the lowest layer every connect goes through (the service, the command queue, the keep
 * alive, automation and SMS through the queue), so it refuses there, before anything touches Bluetooth.
 */
class MovedAwaySessionTest : MedtrumTestBase() {

    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var protectionCheck: ProtectionCheck
    @Mock lateinit var blePreCheck: BlePreCheck

    private val bluetoothContext: Context = mock()
    private lateinit var transport: MedtrumBleTransportImpl
    private lateinit var plugin: MedtrumPlugin

    @BeforeEach
    fun prepare() {
        transport = MedtrumBleTransportImpl(aapsLogger, bluetoothContext, preferences, rxBus)
        plugin = MedtrumPlugin(
            aapsLogger, rh, preferences, commandQueue, rxBus, context, dateUtil, medtrumPump, temporaryBasalStorage,
            pumpEnactResultProvider, protectionCheck, blePreCheck, notificationManager, lazy { mock<MedtrumSessionTransfer>() }
        )
        whenever(preferences.get(MedtrumStringNonKey.SnInput)).thenReturn("AB12345")
        medtrumPump.pumpState = MedtrumPumpState.ACTIVE
    }

    private fun sessionMovedAway(movedAway: Boolean) {
        whenever(preferences.get(MedtrumBooleanNonKey.SessionMovedAway)).thenReturn(movedAway)
    }

    @Test
    fun theTransportDoesNotConnectToThePatch() {
        sessionMovedAway(true)

        assertThat(transport.connect("test", 0xAB12345L)).isFalse()
        // Refused before the permission check and the Bluetooth adapter
        verifyNoInteractions(bluetoothContext)
    }

    @Test
    fun theTransportDoesNotConnectToAnAddressEither() {
        sessionMovedAway(true)

        assertThat(transport.gatt.connect("00:11:22:33:44:55")).isFalse()
        verifyNoInteractions(bluetoothContext)
    }

    @Test
    fun thePluginIsNotInitialized() {
        sessionMovedAway(false)
        assertThat(plugin.isInitialized()).isTrue()

        sessionMovedAway(true)
        // Not initialized: every command of the plugin refuses, except deactivate, which skips the check on
        // purpose (a stopped patch must be deactivated). It connects through the service, and the transport
        // refuses it (above).
        assertThat(plugin.isInitialized()).isFalse()
    }
}

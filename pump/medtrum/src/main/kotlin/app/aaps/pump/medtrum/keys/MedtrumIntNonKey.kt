package app.aaps.pump.medtrum.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState

enum class MedtrumIntNonKey(
    override val key: String,
    override val defaultValue: Int,
    override val exportable: Boolean = true
) : IntNonPreferenceKey {

    PumpState("pump_state", defaultValue = MedtrumPumpState.NONE.state.toInt()),
    CurrentSequenceNumber("current_sequence_number", defaultValue = 0),
    SyncedSequenceNumber("synced_sequence_number", defaultValue = 0),
    DeviceType("device_type", defaultValue = 0),
    PumpTimezoneOffset("pump_time_zone_offset", defaultValue = 0),

    /**
     * How far this phone had read the patch history when it moved its session to the watch (handover). At
     * take in the phone reads the history again from there, so it gets every record the watch made, from
     * the patch itself. -1 when no session is away. About this phone, not the patch.
     */
    SyncedSequenceAtMoveOut("medtrum_synced_sequence_at_move_out", defaultValue = -1, exportable = false),
}

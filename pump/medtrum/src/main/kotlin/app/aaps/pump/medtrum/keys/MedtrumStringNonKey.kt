package app.aaps.pump.medtrum.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

enum class MedtrumStringNonKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    SnInput("sn_input", "0"),
    ActualBasalProfile("actual_basal_profile", "0"),
    ActiveAlarms("active_alarms", ""),
    SwVersion("sw_version", ""),

    /**
     * The running mode this phone had when it moved its patch session out (handover test), offered again
     * when it takes a session back in. Empty when there is nothing to restore. About this phone, not the patch.
     */
    ModeBeforeMoveOut("medtrum_mode_before_move_out", "", exportable = false),
}

package app.aaps.pump.medtrum.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey

enum class MedtrumBooleanNonKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val exportable: Boolean = true
) : BooleanNonPreferenceKey {

    PatchPrimed("patch_primed_flag", defaultValue = false),

    /**
     * The patch session was moved to another device (handover test). While set, this phone does not
     * connect to the patch: only one device may talk to it.
     */
    SessionMovedAway("medtrum_session_moved_away", defaultValue = false, exportable = false),
}

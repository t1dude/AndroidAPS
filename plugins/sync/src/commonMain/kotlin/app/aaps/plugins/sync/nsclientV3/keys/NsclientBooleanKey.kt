package app.aaps.plugins.sync.nsclientV3.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey

enum class NsclientBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val exportable: Boolean = true
) : BooleanNonPreferenceKey {

    NsPaused("ns_client_paused", false),

    /**
     * A full sync was asked for and has not finished yet. Kept on disk so a restart in the middle of a
     * full sync goes on with it as a full sync. Not exportable: it is the state of this install only.
     */
    FullSyncPending("ns_client_full_sync_pending", false, exportable = false)
}

package app.aaps.wear.complications.circle

import app.aaps.wear.complications.ComplicationAction
import app.aaps.wear.complications.CwfAmbientBgComplication
import app.aaps.wear.complications.cwf.CwfFaceComplication

/**
 * The glucose reading drawn by the runtime inside the circle while the watch dozes.
 *
 * In always-on our process is frozen, so the circle picture would sit there with an age that has
 * stopped counting. The face hides the picture then and shows this instead: the value, and a
 * "minutes ago" that the runtime keeps counting by itself - see [CwfAmbientBgComplication].
 *
 * Its slot covers the circle and is invisible while the watch is awake, yet still takes the taps
 * there. So awake it does what the circle does and opens the BG graph; dozing it does nothing, so
 * the first tap wakes the watch instead of opening AAPS - see `readoutTapAction` for that history.
 * [GlucoseCircleUpdater] asks for new data on every mode change so the action follows the mode.
 */
class GlucoseCircleAmbientComplication : CwfAmbientBgComplication() {

    override fun getComplicationAction(): ComplicationAction =
        if (CwfFaceComplication.isAmbient(this)) ComplicationAction.NONE else ComplicationAction.BG_GRAPH

    override fun getProviderCanonicalName(): String = GlucoseCircleAmbientComplication::class.java.canonicalName!!
}

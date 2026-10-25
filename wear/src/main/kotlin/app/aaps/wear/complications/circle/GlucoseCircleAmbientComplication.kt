package app.aaps.wear.complications.circle

import app.aaps.wear.complications.ComplicationAction
import app.aaps.wear.complications.CwfAmbientBgComplication
import app.aaps.wear.complications.cwf.CwfFaceComplication

/**
 * The glucose value shown inside the circle in ambient mode.
 *
 * The wear app does not run in ambient mode, so the age in the picture would stop. The face hides the
 * picture then and shows this text, whose age the watch keeps counting.
 *
 * The slot covers the circle, so it also gets the taps while the watch is awake: it then opens the BG
 * graph. In ambient mode it does nothing, so the first tap wakes the watch.
 */
class GlucoseCircleAmbientComplication : CwfAmbientBgComplication() {

    override fun getComplicationAction(): ComplicationAction =
        if (CwfFaceComplication.isAmbient(this)) ComplicationAction.NONE else ComplicationAction.BG_GRAPH

    override fun getProviderCanonicalName(): String = GlucoseCircleAmbientComplication::class.java.canonicalName!!
}

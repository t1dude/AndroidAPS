package app.aaps.wear.complications

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.R

/**
 * Insulin left in the pump, as the phone sends it.
 *
 * The phone sends the units, not the pump's size, so the range is a guess: 200 U, which most pumps
 * and pods hold, or 300 U when more than 200 U is left. With no reading (no profile on the phone, or
 * an empty value) the text is "--" and the range is empty.
 */
class ReservoirComplication : ModernBaseComplicationProviderService() {

    companion object {

        private const val USUAL_MAX = 200f
        private const val LARGE_MAX = 300f

        internal fun rangeMax(units: Float): Float = if (units > USUAL_MAX) LARGE_MAX else USUAL_MAX
    }

    override fun buildComplicationData(
        type: ComplicationType,
        data: app.aaps.wear.data.ComplicationData,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        val status = data.statusData
        val units = status.reservoir.toFloat().coerceAtLeast(0f)
        val text = status.reservoirString.ifEmpty { "--" }
        val description = PlainComplicationText.Builder(text = getString(R.string.complication_reservoir_description, text)).build()
        val icon = MonochromaticImage.Builder(image = Icon.createWithResource(this, R.drawable.ic_reservoir)).build()

        return when (type) {
            ComplicationType.RANGED_VALUE -> {
                val max = rangeMax(units)
                RangedValueComplicationData.Builder(value = units.coerceAtMost(max), min = 0f, max = max, contentDescription = description)
                    .setText(PlainComplicationText.Builder(text = text).build())
                    .setMonochromaticImage(icon)
                    .setTapAction(complicationPendingIntent)
                    .build()
            }

            ComplicationType.SHORT_TEXT   ->
                ShortTextComplicationData.Builder(text = PlainComplicationText.Builder(text = text).build(), contentDescription = description)
                    .setMonochromaticImage(icon)
                    .setTapAction(complicationPendingIntent)
                    .build()

            else                          -> {
                aapsLogger.warn(LTag.WEAR, "Unexpected complication type $type")
                null
            }
        }
    }
}

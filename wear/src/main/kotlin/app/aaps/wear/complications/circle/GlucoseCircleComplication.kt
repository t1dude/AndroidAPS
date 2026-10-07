package app.aaps.wear.complications.circle

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.R
import app.aaps.wear.complications.ComplicationAction
import app.aaps.wear.complications.ModernBaseComplicationProviderService
import app.aaps.wear.data.ComplicationData as ComplicationStore
import app.aaps.wear.interaction.utils.Constants

/**
 * The overview's glucose circle as an image, for the glucose circle Watch Face Format face.
 *
 * Watch Face Format cannot draw an arc that follows data, so the circle is drawn here and handed to
 * the face as a picture, the same way [app.aaps.wear.complications.BgGraphComplication] hands over
 * the graph. A tap opens the BG graph - the same graph the BG graph tile shows; a watch face cannot
 * open a tile itself.
 *
 * The age line inside the picture only changes when the picture is redrawn, so
 * [GlucoseCircleUpdater] asks for a new one at the moment the minute count changes.
 */
class GlucoseCircleComplication : ModernBaseComplicationProviderService() {

    companion object {

        /**
         * The circle slot's width as a share of the face, from `src/circle/template/watchface.xml`.
         * The picture is drawn at the size the slot shows it, so it is neither blurred nor wasted.
         */
        private const val SLOT_FRACTION = 236f / 450f

        /**
         * Softer than the pure green, yellow and red the rest of the watch uses, to sit calmly on the
         * face's dark background. Still told apart by lightness, not by hue alone.
         */
        internal const val IN_RANGE_COLOR = 0xFF5BD68A.toInt()
        internal const val HIGH_COLOR = 0xFFF2C14E.toInt()
        internal const val LOW_COLOR = 0xFFFF7A6B.toInt()

        internal fun circleColor(sgvLevel: Long): Int = when (sgvLevel) {
            1L   -> HIGH_COLOR
            -1L  -> LOW_COLOR
            else -> IN_RANGE_COLOR
        }

        /** When a face last asked for the picture; 0 until one has. Read by [GlucoseCircleUpdater]. */
        @Volatile var lastRequestMs = 0L
            private set

        /**
         * When the age shown for a reading taken at [timeStamp] next changes, seen at [now].
         * Pure, so the schedule can be checked without a watch.
         */
        internal fun nextAgeChangeMs(timeStamp: Long, now: Long): Long {
            val minutes = ((now - timeStamp) / Constants.MINUTE_IN_MS).coerceAtLeast(0)
            return timeStamp + (minutes + 1) * Constants.MINUTE_IN_MS
        }
    }

    override fun buildComplicationData(
        type: ComplicationType,
        data: ComplicationStore,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        lastRequestMs = System.currentTimeMillis()
        if (type != ComplicationType.SMALL_IMAGE) {
            aapsLogger.warn(LTag.WEAR, "GlucoseCircleComplication unexpected type: $type")
            return null
        }
        val input = input(data)
        val sizePx = (resources.displayMetrics.widthPixels * SLOT_FRACTION).toInt()
        val description = listOf(input.bgText, input.deltaText, input.timeAgoText).filter { it.isNotEmpty() }.joinToString(", ")
        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(
                image = Icon.createWithBitmap(GlucoseCircleRenderer.render(sizePx, input)),
                type = SmallImageType.PHOTO
            ).build(),
            contentDescription = PlainComplicationText.Builder(text = description).build()
        )
            .setTapAction(complicationPendingIntent)
            .build()
    }

    private fun input(data: ComplicationStore): GlucoseCircleInput {
        val bg = data.bgData
        val hasBg = bg.timeStamp != 0L
        val age = System.currentTimeMillis() - bg.timeStamp
        return GlucoseCircleInput(
            bgText = bg.sgvString,
            bgColor = circleColor(bg.sgvLevel),
            strikeThrough = hasBg && age > Constants.STALE_MS,
            trendArrow = bg.trendArrow(),
            hasBg = hasBg,
            deltaText = bg.delta,
            timeAgoText = if (!hasBg) ""
            else if (age < Constants.HOUR_IN_MS) getString(R.string.glucose_circle_min_ago, (age / Constants.MINUTE_IN_MS).toInt().coerceAtLeast(0))
            else displayFormat.shortTimeSince(bg.timeStamp)
        )
    }

    override fun getComplicationAction(): ComplicationAction = ComplicationAction.BG_GRAPH

    override fun getProviderCanonicalName(): String = GlucoseCircleComplication::class.java.canonicalName!!
}

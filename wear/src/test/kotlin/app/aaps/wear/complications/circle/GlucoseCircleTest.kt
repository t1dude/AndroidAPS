package app.aaps.wear.complications.circle

import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The parts of the glucose circle face that need no watch: when the picture is redrawn, which way
 * the trend arc points, and which colour the ring gets.
 */
class GlucoseCircleTest {

    private fun bg(slopeArrow: String, deltaMgdl: Double? = null) =
        EventData.SingleBg(dataset = 0, timeStamp = 0L, slopeArrow = slopeArrow, sgv = 120.0, high = 180.0, low = 70.0, deltaMgdl = deltaMgdl)

    // ---- when the age changes ---------------------------------------------------------------

    @Test
    fun `a fresh reading changes its age one minute after it was taken`() {
        assertThat(GlucoseCircleComplication.nextAgeChangeMs(timeStamp = 1_000_000, now = 1_000_500)).isEqualTo(1_060_000)
    }

    @Test
    fun `the next change follows the reading's own minute grid, not the clock's`() {
        // 2 min 30 s old: shows 2 min, changes to 3 min at exactly three minutes
        assertThat(GlucoseCircleComplication.nextAgeChangeMs(timeStamp = 1_000_000, now = 1_150_000)).isEqualTo(1_180_000)
    }

    @Test
    fun `on the boundary the next change is a full minute away`() {
        assertThat(GlucoseCircleComplication.nextAgeChangeMs(timeStamp = 1_000_000, now = 1_120_000)).isEqualTo(1_180_000)
    }

    @Test
    fun `a reading from the future is treated as brand new`() {
        // The phone's clock can run a little ahead of the watch's
        assertThat(GlucoseCircleComplication.nextAgeChangeMs(timeStamp = 1_000_000, now = 990_000)).isEqualTo(1_060_000)
    }

    // ---- which way the arc points -----------------------------------------------------------

    @Test
    fun `the wire symbol maps back to its trend`() {
        assertThat(bg(TrendArrow.FLAT.symbol).trendArrow()).isEqualTo(TrendArrow.FLAT)
        assertThat(bg(TrendArrow.FORTY_FIVE_DOWN.symbol).trendArrow()).isEqualTo(TrendArrow.FORTY_FIVE_DOWN)
        assertThat(bg(TrendArrow.DOUBLE_UP.symbol).trendArrow()).isEqualTo(TrendArrow.DOUBLE_UP)
    }

    @Test
    fun `both triple arrows travel as X, and the delta tells them apart`() {
        assertThat(bg("X", deltaMgdl = 25.0).trendArrow()).isEqualTo(TrendArrow.TRIPLE_UP)
        assertThat(bg("X", deltaMgdl = -25.0).trendArrow()).isEqualTo(TrendArrow.TRIPLE_DOWN)
        assertThat(bg("X").trendArrow()).isEqualTo(TrendArrow.NONE)
    }

    @Test
    fun `an unknown symbol draws no arc`() {
        assertThat(bg("--").trendArrow()).isEqualTo(TrendArrow.NONE)
        assertThat(TrendArrow.NONE.toArcIndicator()).isNull()
    }

    @Test
    fun `up is at the top of the ring and down at the bottom`() {
        assertThat(TrendArrow.SINGLE_UP.toArcIndicator()?.centerAngle).isEqualTo(-90f)
        assertThat(TrendArrow.SINGLE_DOWN.toArcIndicator()?.centerAngle).isEqualTo(90f)
        assertThat(TrendArrow.TRIPLE_DOWN.toArcIndicator()?.triangleCount).isEqualTo(3)
    }

    // ---- colour -----------------------------------------------------------------------------

    @Test
    fun `high, low and in range get the soft colours`() {
        assertThat(GlucoseCircleComplication.circleColor(1L)).isEqualTo(GlucoseCircleComplication.HIGH_COLOR)
        assertThat(GlucoseCircleComplication.circleColor(-1L)).isEqualTo(GlucoseCircleComplication.LOW_COLOR)
        assertThat(GlucoseCircleComplication.circleColor(0L)).isEqualTo(GlucoseCircleComplication.IN_RANGE_COLOR)
    }

    @Test
    fun `an unknown level is drawn as in range`() {
        assertThat(GlucoseCircleComplication.circleColor(7L)).isEqualTo(GlucoseCircleComplication.IN_RANGE_COLOR)
    }
}

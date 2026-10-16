package app.aaps.wear.complications

import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.wear.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class ReservoirComplicationTest {

    private fun sut(): ReservoirComplication =
        Robolectric.buildService(ReservoirComplication::class.java).get().also { it.aapsLogger = AAPSLoggerTest() }

    @Test
    fun `preview builds a ranged-value complication for the sample reservoir`() {
        val data = sut().getPreviewData(ComplicationType.RANGED_VALUE) as RangedValueComplicationData
        assertThat(data.value).isEqualTo(120f)
        assertThat(data.max).isEqualTo(200f)
    }

    @Test
    fun `preview builds a short-text complication for the sample reservoir`() {
        assertThat(sut().getPreviewData(ComplicationType.SHORT_TEXT)).isInstanceOf(ShortTextComplicationData::class.java)
    }

    @Test
    fun `an unsupported complication type yields null`() {
        assertThat(sut().getPreviewData(ComplicationType.LONG_TEXT)).isNull()
    }

    @Test
    fun `the range is 200 U, or 300 U when more is left`() {
        assertThat(ReservoirComplication.rangeMax(0f)).isEqualTo(200f)
        assertThat(ReservoirComplication.rangeMax(200f)).isEqualTo(200f)
        assertThat(ReservoirComplication.rangeMax(260f)).isEqualTo(300f)
    }
}

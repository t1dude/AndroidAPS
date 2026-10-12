package app.aaps.ui.compose.overview

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Pins the stored format of the chip row on the Circle home screen. */
class CircleChipsSerializerTest {

    @Test
    fun `empty text gives every chip shown in the default order`() {
        val settings = CircleChipsSerializer.fromString("")

        assertThat(settings.map { it.chip }).containsExactlyElementsIn(CircleChip.entries).inOrder()
        assertThat(settings.all { it.visible }).isTrue()
    }

    @Test
    fun `order and hidden chips survive a round trip`() {
        val text = "profile,!cob,iob,sens,!tt,tbr"

        val settings = CircleChipsSerializer.fromString(text)

        assertThat(settings.map { it.chip }).containsExactly(
            CircleChip.PROFILE, CircleChip.COB, CircleChip.IOB, CircleChip.SENSITIVITY, CircleChip.TEMP_TARGET, CircleChip.TBR
        ).inOrder()
        assertThat(settings.filterNot { it.visible }.map { it.chip }).containsExactly(CircleChip.COB, CircleChip.TEMP_TARGET)
        assertThat(CircleChipsSerializer.toString(settings)).isEqualTo(text)
    }

    @Test
    fun `unknown ids are dropped and missing chips are added at the end, shown`() {
        val settings = CircleChipsSerializer.fromString("tbr,unknown,!iob")

        assertThat(settings.take(2)).containsExactly(
            CircleChipSetting(CircleChip.TBR, visible = true),
            CircleChipSetting(CircleChip.IOB, visible = false)
        ).inOrder()
        assertThat(settings.drop(2).map { it.chip }).containsExactly(
            CircleChip.COB, CircleChip.SENSITIVITY, CircleChip.TEMP_TARGET, CircleChip.PROFILE
        ).inOrder()
        assertThat(settings.drop(2).all { it.visible }).isTrue()
    }
}

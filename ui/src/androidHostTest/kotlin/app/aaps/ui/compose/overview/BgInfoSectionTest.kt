package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.ui.UiStringIds
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric composable test for [BgInfoSection].
 *
 * Pins what the user actually reads off the BG circle: the placeholder when there is no reading,
 * the value / delta / time-ago lines, the opt-out of the time-ago line, and the accessibility
 * description a screen reader speaks (built from translated pieces in `bgSpokenDescription`, so it
 * is the part a refactor can silently reword).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BgInfoSectionTest {

    @get:Rule
    val compose = createComposeRule()

    // What MainApp does at startup: the spoken description names its strings, and a TextRef.Named
    // resolves through this registry - without it those parts render as their raw names.
    @Before
    fun setUp() {
        TextRefIdRegistry.register("ui") { name -> UiStringIds.idOf(name) }
    }

    private fun bgInfo(
        bgText: String = "120",
        bgRange: BgRange = BgRange.IN_RANGE,
        isOutdated: Boolean = false,
        trendArrow: TrendArrow? = TrendArrow.FLAT,
        trendDescription: String = "Flat",
        deltaText: String? = "+2"
    ) = BgInfoData(
        bgValue = 120.0,
        bgText = bgText,
        bgRange = bgRange,
        isOutdated = isOutdated,
        timestamp = 1_700_000_000_000L,
        trendArrow = trendArrow,
        trendDescription = trendDescription,
        delta = 2.0,
        deltaText = deltaText,
        shortAvgDelta = null,
        shortAvgDeltaText = null,
        longAvgDelta = null,
        longAvgDeltaText = null
    )

    @Test
    fun showsPlaceholderWhenNoReading() {
        compose.setContent {
            MaterialTheme {
                BgInfoSection(bgInfo = null, timeAgoText = "2 min")
            }
        }

        compose.onNodeWithText("---").assertIsDisplayed()
        // The placeholder branch returns early, so the time-ago line must not appear with it.
        compose.onNodeWithText("2 min").assertDoesNotExist()
    }

    @Test
    fun showsValueDeltaAndTimeAgo() {
        compose.setContent {
            MaterialTheme {
                BgInfoSection(bgInfo = bgInfo(), timeAgoText = "2 min")
            }
        }

        compose.onNodeWithText("120").assertIsDisplayed()
        compose.onNodeWithText("+2").assertIsDisplayed()
        compose.onNodeWithText("2 min").assertIsDisplayed()
    }

    @Test
    fun hidesTimeAgoWhenShowTimeAgoIsFalse() {
        compose.setContent {
            MaterialTheme {
                BgInfoSection(bgInfo = bgInfo(), timeAgoText = "2 min", showTimeAgo = false)
            }
        }

        compose.onNodeWithText("120").assertIsDisplayed()
        compose.onNodeWithText("2 min").assertDoesNotExist()
    }

    @Test
    fun hidesDeltaLineWhenThereIsNoDelta() {
        compose.setContent {
            MaterialTheme {
                BgInfoSection(bgInfo = bgInfo(deltaText = null), timeAgoText = "2 min")
            }
        }

        compose.onNodeWithText("120").assertIsDisplayed()
        compose.onNodeWithText("+2").assertDoesNotExist()
    }

    /**
     * The time-ago text comes from `DateUtil.minAgo` and already says "ago". The description used to
     * add another, so TalkBack read "1 min ago ago" on the main screen.
     */
    @Test
    fun accessibilityDescriptionListsValueTrendDeltaAndAge() {
        compose.setContent {
            MaterialTheme {
                BgInfoSection(bgInfo = bgInfo(), timeAgoText = "2 min ago")
            }
        }

        compose.onNodeWithContentDescription("Glucose: 120, Flat, delta +2, 2 min ago").assertExists()
    }

    @Test
    fun accessibilityDescriptionSaysOutdated() {
        compose.setContent {
            MaterialTheme {
                BgInfoSection(bgInfo = bgInfo(isOutdated = true), timeAgoText = "12 min ago")
            }
        }

        compose.onNodeWithContentDescription("Glucose: 120, Flat, delta +2, 12 min ago, old reading").assertExists()
    }

    /** High and low are only the colour of the circle, so they must be said. */
    @Test
    fun accessibilityDescriptionSaysHighAndLow() {
        compose.setContent {
            MaterialTheme {
                Column {
                    BgInfoSection(bgInfo = bgInfo(bgText = "250", bgRange = BgRange.HIGH), timeAgoText = "")
                    BgInfoSection(bgInfo = bgInfo(bgText = "60", bgRange = BgRange.LOW), timeAgoText = "")
                }
            }
        }

        compose.onNodeWithContentDescription("Glucose: 250, high, Flat, delta +2").assertExists()
        compose.onNodeWithContentDescription("Glucose: 60, low, Flat, delta +2").assertExists()
    }

    @Test
    fun accessibilityDescriptionOmitsDeltaAndAgeWhenAbsent() {
        compose.setContent {
            MaterialTheme {
                BgInfoSection(bgInfo = bgInfo(deltaText = null), timeAgoText = "")
            }
        }

        compose.onNodeWithContentDescription("Glucose: 120, Flat").assertExists()
    }

    @Test
    fun rendersWithoutTrendArrow() {
        compose.setContent {
            MaterialTheme {
                BgInfoSection(
                    bgInfo = bgInfo(trendArrow = null, trendDescription = "Unknown", bgRange = BgRange.LOW),
                    timeAgoText = "1 min ago"
                )
            }
        }

        compose.onNodeWithText("120").assertIsDisplayed()
        compose.onNodeWithContentDescription("Glucose: 120, low, Unknown, delta +2, 1 min ago").assertExists()
    }

    @Test
    fun rendersEveryTrendArrowVariant() {
        // The arc indicator maps every TrendArrow to a position + triangle count. The drawing itself
        // is not assertable, but a missing branch would throw, so this pins that all of them draw.
        val arrows = TrendArrow.entries
        compose.setContent {
            MaterialTheme {
                Column {
                    arrows.forEach { arrow ->
                        BgInfoSection(
                            bgInfo = bgInfo(trendArrow = arrow, bgRange = BgRange.HIGH),
                            timeAgoText = "now",
                            showTimeAgo = false
                        )
                    }
                }
            }
        }

        compose.onAllNodesWithText("120").assertCountEquals(arrows.size)
    }

    /** Height of the three text lines and the room inside the ring, for a small circle and a large font. */
    private fun measureSmallCircle(textReferenceSize: Dp?): Pair<Dp, Dp> {
        val size = AapsSpacing.homeCircleMinSize
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                MaterialTheme {
                    BgInfoSection(
                        bgInfo = bgInfo(bgText = "10.5", deltaText = "-1.0"),
                        timeAgoText = "7m ago",
                        size = size,
                        textReferenceSize = textReferenceSize
                    )
                }
            }
        }
        // In px: getBoundsInRoot() would convert with the scaled density of the text. The unmerged tree: the
        // circle merges its texts into one screen reader node, and that node has the size of the whole circle.
        val top = compose.onNodeWithText("-1.0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val bottom = compose.onNodeWithText("7m ago", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom
        val textHeight = with(compose.density) { (bottom - top).toDp() }
        return textHeight to (size - AapsSpacing.bgRingStrokeWidth * 2)
    }

    @Test
    fun textFitsInsideSmallCircleWhenScaled() {
        val (textHeight, room) = measureSmallCircle(textReferenceSize = AapsSpacing.homeCircleSize)
        assertThat(textHeight.value).isAtMost(room.value)
    }

    @Test
    fun textDoesNotFitInsideSmallCircleWithoutScaling() {
        // Shows the problem the scaling fixes (seen on a Galaxy Z Flip 7)
        val (textHeight, room) = measureSmallCircle(textReferenceSize = null)
        assertThat(textHeight.value).isGreaterThan(room.value)
    }
}

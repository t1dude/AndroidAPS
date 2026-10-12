package app.aaps.core.ui.compose

import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsSpacing.extraLarge
import app.aaps.core.ui.compose.AapsSpacing.extraSmall
import app.aaps.core.ui.compose.AapsSpacing.large
import app.aaps.core.ui.compose.AapsSpacing.medium
import app.aaps.core.ui.compose.AapsSpacing.small
import app.aaps.core.ui.compose.AapsSpacing.xxLarge

/**
 * Centralized spacing and dimension constants for AAPS Compose UI.
 *
 * **Generic spacing scale** — use for padding, margins, arrangement spacing:
 * - [extraSmall] (2.dp), [small] (4.dp), [medium] (8.dp),
 *   [large] (12.dp), [extraLarge] (16.dp), [xxLarge] (24.dp)
 *
 * **Domain-specific dimensions** — named constants for specific UI elements.
 *
 * This is a plain object (no CompositionLocal) because spacing values are
 * static and theme-independent.
 *
 * **Usage:**
 * ```kotlin
 * Modifier.padding(AapsSpacing.medium)  // 8.dp
 * Modifier.size(AapsSpacing.bgCircleSize)  // 126.dp
 * ```
 */
object AapsSpacing {

    // Generic spacing scale
    val extraSmall = 2.dp
    val small = 4.dp
    val medium = 8.dp
    val large = 12.dp
    val extraLarge = 16.dp
    val xxLarge = 24.dp

    // BG circle dimensions
    val bgCircleSize = 126.dp
    val bgRingStrokeWidth = 8.dp

    // Trend arrow drawn next to a BG value
    val trendArrowSize = 20.dp

    // Chip dimensions
    val chipCornerRadius = 8.dp
    val chipHeight = 35.dp
    val chipIconSize = 24.dp
    val chipProgressHeight = 3.dp

    // Circle home screen
    val homeCircleSize = 156.dp
    val homeCircleMinSize = 104.dp
    val homePillHeight = 40.dp
    val homePillWidth = 104.dp
    val homePillIconSize = 20.dp
    /** Free space at each side of the chip row: room for the edit pencil, kept on both sides to stay centred. */
    val homeChipRowSideSpace = 36.dp
    val homeToolbarHeight = 60.dp
    val homeSearchBarHeight = 40.dp

    /** From this width the Circle home screen puts the circle panel and the graphs side by side. */
    val homeSideBySideMinWidth = 600.dp

    /** Width of the circle column when side by side, the width of a phone. Never more than half the screen. */
    val homeSideBySideCircleWidth = 412.dp
    val homeToolbarIconSize = 30.dp

    /** The row of chips under the circle is drawn at this part of the normal size. */
    const val homeChipScale = 0.8f
}

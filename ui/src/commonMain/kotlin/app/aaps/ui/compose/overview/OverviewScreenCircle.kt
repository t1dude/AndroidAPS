package app.aaps.ui.compose.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.ActiveSceneState
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.graph.TbrState
import app.aaps.core.keys.StringNonKey
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.icons.IcLoopClosed
import app.aaps.core.ui.compose.icons.IcPluginInsulin
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.main.TempTargetChipState
import app.aaps.ui.compose.overview.aapsClient.AapsClientStatusCard
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.chips.CobChip
import app.aaps.ui.compose.overview.chips.IobChip
import app.aaps.ui.compose.overview.chips.ProfileChip
import app.aaps.ui.compose.overview.chips.TbrChip
import app.aaps.ui.compose.overview.chips.TempTargetChip
import app.aaps.ui.compose.overview.chips.toIcon
import app.aaps.ui.compose.overview.graphs.DEFAULT_GRAPH_ZOOM_MINUTES
import app.aaps.ui.compose.overview.graphs.GraphEditButton
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.graphs.GraphsSection
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import app.aaps.ui.compose.scenes.ActiveSceneBanner

/**
 * The "Circle" home screen design (Settings > Appearance > Home Screen Design), used on every screen shape.
 *
 * The glucose circle sits in the middle with the softer colors of the glucose circle watchface.
 * Pump and pod/cannula pills are on its left, loop and sensor pills on its right. A row of small
 * chips is below it, then the same graphs as the default design, with the hour labels only under
 * the last graph.
 */
@Composable
fun OverviewScreenCircle(
    profileName: String,
    isProfileModified: Boolean,
    profileProgress: Float,
    profileSceneManaged: Boolean = false,
    tempTargetText: String,
    tempTargetState: TempTargetChipState,
    tempTargetProgress: Float,
    tempTargetReason: TT.Reason?,
    tempTargetSceneManaged: Boolean = false,
    runningMode: RM.Mode?,
    runningModeText: String,
    tbrState: TbrState,
    isSimpleMode: Boolean,
    graphViewModel: GraphViewModel,
    chipsViewModel: ChipsViewModel,
    statusViewModel: StatusViewModel,
    onNavigate: (NavigationRequest) -> Unit,
    onTbrChipClick: () -> Unit,
    onIobChipClick: () -> Unit,
    paddingValues: PaddingValues,
    activeSceneState: ActiveSceneState? = null,
    sceneExpired: Boolean = false,
    activeSceneChainTargetName: String? = null,
    onEndScene: () -> Unit = {},
    onDismissScene: () -> Unit = {},
    endSceneEnabled: Boolean = true,
    commandsAllowed: Boolean = true,
    formatDuration: (Long) -> String = { ms -> "${(ms / 60000L).toInt()}m" },
    /** The "time to eat" bar, under the scene bar. */
    eatReminderBanner: @Composable () -> Unit = {},
    /** Height of the bars drawn over the content in preview mode (a short window, e.g. split screen); added to the end space. */
    overlaidBarsHeight: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val config = LocalConfig.current
    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    val sensitivityUiState by chipsViewModel.sensitivityUiState.collectAsStateWithLifecycle()
    val iobUiState by chipsViewModel.iobUiState.collectAsStateWithLifecycle()
    val cobUiState by chipsViewModel.cobUiState.collectAsStateWithLifecycle()
    val statusState by statusViewModel.uiState.collectAsStateWithLifecycle()

    // Each part is a small composable, so the same parts can be laid out in one column (phone) or side
    // by side (unfolded foldable, tablet, phone turned sideways).
    val sceneBanner: @Composable () -> Unit = {
        ActiveSceneBanner(
            activeState = activeSceneState,
            expired = sceneExpired,
            chainTargetName = activeSceneChainTargetName,
            onEndClick = onEndScene,
            onDismiss = onDismissScene,
            endEnabled = endSceneEnabled,
            formatDuration = formatDuration
        )
        eatReminderBanner()
    }
    val circlePanel: @Composable () -> Unit = {
        // Circle, pills and the chip row, grouped on one panel
        HomePanel {
            // The circle gets what is left between the two pill columns, up to its normal size. It is not
            // made larger on tablets, and it shrinks on a narrow column so the pills are never cut off.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val ringRoom = AapsSpacing.bgRingStrokeWidth
                val besideCircle = AapsSpacing.medium * 2 + AapsSpacing.homePillWidth * 2 + ringRoom * 2 + AapsSpacing.small * 4
                val circleSize = (maxWidth - besideCircle).coerceIn(AapsSpacing.homeCircleMinSize, AapsSpacing.homeCircleSize)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: pump with reservoir, and time left on the pod or cannula
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val insulin = statusState.insulinStatus
                        HomePill(
                            icon = IcPluginInsulin, // insulin vial
                            tint = insulin?.levelStatus.toSoftColor(),
                            value = insulin?.level ?: "-",
                            label = insulin?.label ?: stringResource(CoreUiStrings.insulin_label),
                            onClick = statusState.pumpPluginClass?.let { { onNavigate(NavigationRequest.Plugin(it)) } }
                        )
                        val cannula = statusState.cannulaStatus
                        val pumpTimeLeft = statusState.pumpTimeLeft
                        HomePill(
                            icon = statusState.pumpIcon ?: cannula?.icon,
                            tint = pumpTimeLeft?.status.toSoftColor(),
                            value = pumpTimeLeft?.text ?: "-",
                            label = cannula?.label ?: stringResource(CoreUiStrings.cannula),
                            onClick = statusState.pumpPluginClass?.let { { onNavigate(NavigationRequest.Plugin(it)) } }
                        )
                    }

                    BgInfoSection(
                        bgInfo = bgInfoState.bgInfo,
                        timeAgoText = bgInfoState.timeAgoText,
                        size = circleSize,
                        softColors = true,
                        // The text is made for the full-size circle and shrinks with it
                        textReferenceSize = AapsSpacing.homeCircleSize,
                        // Tapping the circle opens the CGM (BG source) page. The trend triangles are drawn
                        // outside the ring (up to about one ring width), so that room is kept free around the
                        // circle on every side, inside the round clip: they are neither cut off nor drawn over
                        // the chips and pills.
                        modifier = Modifier
                            .clip(CircleShape)
                            .then(
                                statusState.bgSourcePluginClass?.let { pluginClass ->
                                    Modifier.clickable { onNavigate(NavigationRequest.Plugin(pluginClass)) }
                                } ?: Modifier
                            )
                            .padding(ringRoom)
                    )

                    // Right: loop with minutes since the last run, and time left on the sensor
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val minutesAgo = statusState.loopMinutesAgo
                        HomePill(
                            icon = runningMode?.toIcon() ?: IcLoopClosed,
                            tint = statusState.loopStatus.toSoftColor(normalIsGreen = true),
                            value = minutesAgo?.let { stringResource(CoreUiStrings.format_mins, it) } ?: "-",
                            label = runningModeText,
                            onClick = statusState.apsPluginClass?.let { { onNavigate(NavigationRequest.Plugin(it)) } },
                            onLongClick = if (commandsAllowed && runningModeText.isNotEmpty()) {
                                { onNavigate(NavigationRequest.Element(ElementType.RUNNING_MODE)) }
                            } else null
                        )
                        val sensorTimeLeft = statusState.sensorTimeLeft
                        HomePill(
                            icon = statusState.sensorIcon ?: statusState.sensorStatus?.icon,
                            tint = sensorTimeLeft?.status.toSoftColor(),
                            value = sensorTimeLeft?.text ?: "-",
                            label = statusState.sensorStatus?.label ?: stringResource(CoreUiStrings.sensor_label),
                            onClick = statusState.bgSourcePluginClass?.let { { onNavigate(NavigationRequest.Plugin(it)) } }
                        )
                    }
                }
            }

            // Small chips, in the order the user chose. They go on to a second line when they do not fit.
            // The pencil at the end opens the sheet to show, hide and reorder them.
            val preferences = LocalPreferences.current
            val chipsText by preferences.observe(StringNonKey.OverviewCircleChips).collectAsState()
            val chipSettings = remember(chipsText) { CircleChipsSerializer.fromString(chipsText) }
            var editingChips by remember { mutableStateOf(false) }
            // The chips are centred on the full width: the pencil lies on top at the right end, and the
            // chips keep the same free space on both sides so they never run under it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AapsSpacing.medium),
                contentAlignment = Alignment.Center
            ) {
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AapsSpacing.homeChipRowSideSpace),
                    contentAlignment = Alignment.Center
                ) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density * AapsSpacing.homeChipScale, density.fontScale)
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)
                        ) {
                            chipSettings.filter { it.visible }.forEach { setting ->
                                when (setting.chip) {
                                    CircleChip.IOB         -> IobChip(state = iobUiState, onClick = onIobChipClick)
                                    CircleChip.COB         -> CobChip(state = cobUiState)
                                    CircleChip.SENSITIVITY -> SensitivityChipBlock(state = sensitivityUiState)
                                    CircleChip.TEMP_TARGET -> if (tempTargetText.isNotEmpty()) {
                                        TempTargetChip(
                                            targetText = tempTargetText,
                                            state = tempTargetState,
                                            progress = tempTargetProgress,
                                            reason = tempTargetReason,
                                            onClick = { onNavigate(NavigationRequest.Element(ElementType.TEMP_TARGET_MANAGEMENT)) },
                                            sceneManaged = tempTargetSceneManaged,
                                            enabled = commandsAllowed,
                                            // The chip fills the width it gets; in a wrapping row it must only take what it needs
                                            modifier = Modifier.width(IntrinsicSize.Max)
                                        )
                                    }

                                    CircleChip.TBR         -> TbrChip(state = tbrState, onClick = onTbrChipClick)
                                    CircleChip.PROFILE     -> ProfileChip(
                                        profileName = profileName.ifEmpty { stringResource(CoreUiStrings.no_profile_set) },
                                        isModified = isProfileModified,
                                        progress = profileProgress,
                                        onClick = { onNavigate(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT)) },
                                        sceneManaged = profileSceneManaged,
                                        isNoProfile = profileName.isEmpty(),
                                        modifier = Modifier.width(IntrinsicSize.Max)
                                    )
                                }
                            }
                        }
                    }
                }
                GraphEditButton(
                    onClick = { editingChips = true },
                    graphName = stringResource(UiStrings.circle_chips_title),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = AapsSpacing.small)
                )
            }
            if (editingChips) {
                CircleChipsBottomSheet(
                    settings = chipSettings,
                    onChange = { preferences.put(StringNonKey.OverviewCircleChips, CircleChipsSerializer.toString(it)) },
                    onDismiss = { editingChips = false }
                )
            }
        }
    }
    val clientCard: @Composable () -> Unit = {
        if (config.AAPSCLIENT) {
            val nsClientStatus by graphViewModel.nsClientStatusFlow.collectAsStateWithLifecycle()
            val flavorTint = when {
                config.AAPSCLIENT3 -> AapsTheme.generalColors.flavorClient3Tint
                config.AAPSCLIENT2 -> AapsTheme.generalColors.flavorClient2Tint
                else               -> AapsTheme.generalColors.flavorClient1Tint
            }
            AapsClientStatusCard(
                statusData = nsClientStatus,
                flavorTint = flavorTint
            )
        }
    }
    val graphs: @Composable (visibleMinutes: Double) -> Unit = { visibleMinutes ->
        // The graphs lie on a panel as wide as the top one. It ends above "Add graph", which keeps it
        // clear of the quick launch toolbar.
        GraphsSection(
            graphViewModel = graphViewModel,
            isSimpleMode = isSimpleMode,
            iobText = iobUiState.text,
            cobText = cobUiState.text,
            timeLabelsOnLastGraphOnly = true,
            showTreatmentBelt = false,
            iobGraphBasalFraction = CIRCLE_IOB_GRAPH_BASAL_FRACTION,
            showIobGraphLabel = false,
            horizontalPadding = AapsSpacing.small,
            circleStyle = true,
            graphsBackground = Modifier
                .padding(top = AapsSpacing.small)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large),
            defaultVisibleMinutes = visibleMinutes,
            overlaidBarsHeight = overlaidBarsHeight
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        if (maxWidth >= AapsSpacing.homeSideBySideMinWidth) {
            // Wide screen: circle panel on the left, graphs on the right, each column scrolls on its own
            val circleColumnWidth = minOf(AapsSpacing.homeSideBySideCircleWidth, maxWidth / 2)
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(circleColumnWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    sceneBanner()
                    circlePanel()
                    clientCard()
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    graphs(WIDE_VISIBLE_MINUTES)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                sceneBanner()
                circlePanel()
                clientCard()
                graphs(DEFAULT_GRAPH_ZOOM_MINUTES)
            }
        }
    }
}

/**
 * A Material 3 card that groups the circle, the pills and the chip row. A soft lavender light sits
 * behind the glucose circle, like the sunburst on the glucose circle watchface.
 */
@Composable
private fun HomePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val glowColor = AapsTheme.generalColors.circleAccent.copy(alpha = 0.16f)
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AapsSpacing.small)
    ) {
        Column(
            modifier = Modifier.drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glowColor, Color.Transparent),
                        center = Offset(size.width / 2, size.height * 0.4f),
                        radius = size.width * 0.5f
                    )
                )
            },
            content = content
        )
    }
}

/** On a wide screen the graphs open showing 12 hours instead of 6. */
private const val WIDE_VISIBLE_MINUTES = 720.0

/** In the IOB graph basal gets the top 30 %, IOB, bolus and SMB the other 70 %. */
private const val CIRCLE_IOB_GRAPH_BASAL_FRACTION = 0.3

/**
 * A pill with an icon and a value. [label] is not shown, only read out by a screen reader.
 * The icon and the value take [tint]; the background is a light wash of it.
 */
@Composable
private fun HomePill(
    icon: ImageVector?,
    tint: Color,
    value: String,
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(percent = 50)
    Surface(
        shape = shape,
        color = tint.copy(alpha = 0.14f),
        modifier = modifier
            .widthIn(max = AapsSpacing.homePillWidth)
            .fillMaxWidth()
            .height(AapsSpacing.homePillHeight)
            .clip(shape)
            .then(
                if (onClick != null || onLongClick != null) Modifier.combinedClickable(
                    onClick = onClick ?: {},
                    onLongClick = onLongClick
                ) else Modifier
            )
            .semantics(mergeDescendants = true) { contentDescription = label }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = AapsSpacing.medium)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(AapsSpacing.homePillIconSize)
                )
                Spacer(Modifier.width(AapsSpacing.medium))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Status to the soft watchface palette. A normal or unknown state uses the lavender accent, so only
 * things that need attention stand out. The loop pill passes [normalIsGreen] to show green when all is well.
 */
@Composable
private fun StatusLevel?.toSoftColor(normalIsGreen: Boolean = false): Color = when (this) {
    StatusLevel.WARNING  -> AapsTheme.generalColors.circleWarning
    StatusLevel.CRITICAL -> AapsTheme.generalColors.circleCritical
    StatusLevel.NORMAL   -> if (normalIsGreen) AapsTheme.generalColors.circleNormal else AapsTheme.generalColors.circleAccent
    else                 -> AapsTheme.generalColors.circleAccent
}

package app.aaps.plugins.sync.wear.compose

import app.aaps.core.ui.compose.stringResource
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.sync.SyncStrings
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.util.lerp
import androidx.core.net.toUri
import kotlin.math.absoluteValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.keys.KeysStrings
import app.aaps.core.keys.PushedWatchfaceId
import app.aaps.core.keys.StringKey
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LocalSnackbarHostState
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.plugins.sync.R

@Composable
internal fun WearScreen(
    viewModel: WearViewModel,
    setToolbarConfig: (ToolbarConfig) -> Unit,
    onNavigateBack: () -> Unit,
    onSettings: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHostState.current

    LaunchedEffect(Unit) {
        viewModel.toastEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Back handler for sub-screens
    BackHandler(enabled = uiState.showInfos) { viewModel.hideCwfInfos() }
    BackHandler(enabled = uiState.showImportList) { viewModel.hideImportList() }

    // Only title needs pre-resolving (plain String used in LaunchedEffect suspend block)
    val wearTitle = stringResource(CoreUiStrings.wear)
    val importTitle = stringResource(SyncStrings.wear_import_custom_watchface_title)

    // Determine current sub-screen
    val subScreen = when {
        uiState.showImportList -> SubScreen.IMPORT_LIST
        uiState.showInfos      -> SubScreen.INFOS
        else                   -> SubScreen.MAIN
    }

    // Toolbar config
    LaunchedEffect(subScreen, uiState.cwfInfosState?.title) {
        setToolbarConfig(
            ToolbarConfig(
                title = when (subScreen) {
                    SubScreen.IMPORT_LIST -> importTitle
                    SubScreen.INFOS       -> uiState.cwfInfosState?.title ?: ""
                    SubScreen.MAIN        -> wearTitle
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (subScreen) {
                            SubScreen.IMPORT_LIST -> viewModel.hideImportList()
                            SubScreen.INFOS       -> viewModel.hideCwfInfos()
                            SubScreen.MAIN        -> onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreUiStrings.back)
                        )
                    }
                },
                actions = {
                    if (subScreen == SubScreen.MAIN && onSettings != null) {
                        IconButton(onClick = onSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(CoreUiStrings.nav_plugin_preferences)
                            )
                        }
                    }
                }
            )
        )
    }

    val moreWatchfacesUrl = stringResource(SyncStrings.wear_link_to_more_cwf_doc)

    AnimatedContent(
        targetState = subScreen,
        label = "wear_screen"
    ) { screen ->
        when (screen) {
            SubScreen.IMPORT_LIST -> {
                CwfImportContent(
                    items = uiState.importItems,
                    onItemClick = { item -> viewModel.selectWatchface(item.cwfFile) },
                    modifier = modifier
                )
            }

            SubScreen.INFOS       -> {
                val infosState = uiState.cwfInfosState
                if (infosState != null) {
                    CwfInfosContent(
                        state = infosState,
                        modifier = modifier
                    )
                }
            }

            SubScreen.MAIN        -> {
                WearMainContent(
                    uiState = uiState,
                    onResendData = { viewModel.resendData() },
                    onOpenSettings = { viewModel.openSettingsOnWear() },
                    onLoadWatchface = { viewModel.loadWatchfaceFiles() },
                    onInfosWatchface = { viewModel.showCwfInfos() },
                    onExportTemplate = { viewModel.exportCustomWatchface() },
                    onMoreWatchfaces = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, moreWatchfacesUrl.toUri()))
                    },
                    onSelectPushedWatchface = { viewModel.selectPushedWatchface(it) },
                    onDismissCustomWatchfaceNotShown = { viewModel.dismissCustomWatchfaceNotShown() },
                    modifier = modifier
                )
            }
        }
    }
}

private enum class SubScreen { MAIN, INFOS, IMPORT_LIST }

/**
 * @see WearMainContentPreview
 * @see WearMainContentDisconnectedPreview
 */
@Composable
internal fun WearMainContent(
    uiState: WearUiState,
    onResendData: () -> Unit,
    onOpenSettings: () -> Unit,
    onLoadWatchface: () -> Unit,
    onInfosWatchface: () -> Unit,
    onExportTemplate: () -> Unit,
    onMoreWatchfaces: () -> Unit,
    onSelectPushedWatchface: (String) -> Unit,
    onDismissCustomWatchfaceNotShown: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The face the wearer tapped, waiting for their confirmation; null while no dialog is open.
    // Confirmed first because the watch replaces its installed face at once.
    var pendingWatchface by remember { mutableStateOf<String?>(null) }
    pendingWatchface?.let { face ->
        val current = uiState.selectedWatchface
        // Leaving a face with complication slots loses what was edited on it in the watch face
        // editor: the runtime drops a face's user configuration when a face of another package name
        // takes the slot. The custom face has nothing to lose, so it gets no warning.
        val message =
            if (current != PushedWatchfaceId.CWF) SyncStrings.wear_pushed_watchface_confirm_message_from_complications
            else SyncStrings.wear_pushed_watchface_confirm_message
        OkCancelDialog(
            title = stringResource(SyncStrings.wear_pushed_watchface_confirm_title),
            message = stringResource(message, pushedWatchfaceLabel(face), pushedWatchfaceLabel(current)),
            onConfirm = {
                onSelectPushedWatchface(face)
                pendingWatchface = null
            },
            onDismiss = { pendingWatchface = null }
        )
    }

    // A zip was just sent while the complications face is the one on the wrist: it is stored on
    // the watch, but nothing shows it until the custom face is selected. Said once, at that moment.
    uiState.customWatchfaceNotShown?.let { name ->
        OkDialog(
            title = pushedWatchfaceLabel(PushedWatchfaceId.CWF),
            message = stringResource(SyncStrings.wear_custom_watchface_not_shown_on, pushedWatchfaceLabel(uiState.selectedWatchface), name),
            onDismiss = onDismissCustomWatchfaceNotShown
        )
    }

    // The face cards. The pager starts on the face that is on the watch and follows it after a
    // change, so the outlined card is the centred one when the screen opens.
    val pushedFaces = PushedWatchfaceId.ALL
    val pagerState = rememberPagerState(initialPage = pushedFaces.indexOf(uiState.selectedWatchface).coerceAtLeast(0)) { pushedFaces.size }
    LaunchedEffect(uiState.selectedWatchface) {
        val page = pushedFaces.indexOf(uiState.selectedWatchface)
        if (page >= 0 && page != pagerState.currentPage) pagerState.animateScrollToPage(page)
    }
    val pushedFacesShown = uiState.isDeviceConnected && uiState.watchFacePushSupported

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AapsSpacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        // Connection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AapsSpacing.large),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)
            ) {
                Text(
                    text = uiState.connectedDevice,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                ButtonRow(
                    button1 = ButtonDef(Icons.Default.Refresh, stringResource(SyncStrings.resend_all_data), onResendData),
                    button2 = ButtonDef(Icons.Default.Settings, stringResource(SyncStrings.open_settings_on_wear), onOpenSettings)
                )
            }
        }

        // Pushed Watchface Card: only on a watch that reported Watch Face Push (Wear OS 6+). Below
        // that the pushed faces cannot exist, and a choice that does nothing would only mislead.
        if (pushedFacesShown) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AapsSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
                ) {
                    Text(
                        text = stringResource(StringKey.WearPushedWatchface.title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge)
                    )

                    // Watch Face Push gives the app one slot, so this is a choice between the
                    // embedded faces, not a set of switches: one card per face, the one on the
                    // watch outlined, a button on the others. The button asks first, see
                    // pendingWatchface. While the watch still holds the other face - the seconds
                    // an install takes, or longer after a reinstall until the preferences reach
                    // it - the chosen card says so, quietly: it is progress, not a fault.
                    val selectedId = uiState.selectedWatchface
                    val installing = uiState.installedWatchface != null && uiState.installedWatchface != selectedId
                    WatchfaceCarousel(state = pagerState, faces = pushedFaces) { page ->
                        val face = pushedFaces[page]
                        val selected = face == selectedId
                        WatchfaceCard(
                            face = face,
                            customImage = uiState.watchfaceImage,
                            selected = selected,
                            hint = if (selected && installing) stringResource(SyncStrings.wear_pushed_watchface_installing) else null,
                            onUse = { pendingWatchface = face }
                        )
                    }
                }
            }
        }

        // Custom Watchface Card (visible only when connected): a watch below Wear OS 6 runs the
        // code-based face, a watch like the Galaxy Watch 5 runs it beside the pushed faces, and a
        // zip can be loaded before switching. With the face cards above it belongs to the custom
        // face's card, so it shows while that card is the centred one.
        val customCardShown = uiState.isDeviceConnected && (!pushedFacesShown || pushedFaces[pagerState.currentPage] == PushedWatchfaceId.CWF)
        if (customCardShown) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AapsSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
                ) {
                    Text(
                        text = stringResource(SyncStrings.wear_custom_watchface, uiState.watchfaceName),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = AapsSpacing.small)
                    )

                    // Row 1: Load + Info
                    ButtonRow(
                        button1 = ButtonDef(Icons.Default.Upload, stringResource(SyncStrings.wear_load_watchface), onLoadWatchface),
                        button2 = if (uiState.hasCustomWatchface)
                            ButtonDef(Icons.Default.Info, stringResource(SyncStrings.wear_infos_watchface), onInfosWatchface)
                        else null
                    )

                    // Row 2: More Watchfaces + Export
                    ButtonRow(
                        button1 = ButtonDef(Icons.Default.Public, stringResource(InterfacesStrings.wear_more_watchfaces), onMoreWatchfaces),
                        button2 = ButtonDef(Icons.Default.Download, stringResource(SyncStrings.wear_export_watchface), onExportTemplate)
                    )

                    // Watchface preview image
                    uiState.watchfaceImage?.let { image ->
                        Spacer(modifier = Modifier.height(AapsSpacing.small))
                        Image(
                            bitmap = image,
                            contentDescription = uiState.watchfaceName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AapsSpacing.extraLarge),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                }
            }
        }
    }
}

/** The label of an embedded face, from the same strings the key's list entries use */
@Composable
private fun pushedWatchfaceLabel(face: String): String =
    stringResource(
        when (face) {
            PushedWatchfaceId.CWF    -> KeysStrings.wear_pushed_watchface_cwf
            PushedWatchfaceId.CIRCLE -> KeysStrings.wear_pushed_watchface_circle
            else                     -> KeysStrings.wear_pushed_watchface_wfs
        }
    )

/** One line on what a face is for, under its name on the card */
@Composable
private fun pushedWatchfaceSummary(face: String): String =
    stringResource(
        when (face) {
            PushedWatchfaceId.CWF    -> SyncStrings.wear_pushed_watchface_cwf_summary
            PushedWatchfaceId.CIRCLE -> SyncStrings.wear_pushed_watchface_circle_summary
            else                     -> SyncStrings.wear_pushed_watchface_wfs_summary
        }
    )

/**
 * Metrics of the face cards. The same numbers as the management carousel in :ui, which this
 * module does not depend on; the peek is smaller so the picture of a face can be larger.
 */
private object WatchfaceCarouselDefaults {

    /** Largest size of the face picture; on a narrow phone it is the card's width instead */
    val ImageSize = 200.dp

    /** Height of a card without its picture and texts: the button, the three gaps and the padding */
    val ButtonAndGapsHeight = 92.dp

    /** Horizontal peek so the neighbouring cards stay partially visible */
    val ContentPadding = 64.dp

    /** Gap between adjacent cards */
    val PageSpacing = 16.dp

    /** Scale and alpha of a fully off-centre card; the centred card renders at 1f */
    const val MIN_SCALE = 0.85f
    const val MIN_ALPHA = 0.5f
}

/** The face cards side by side, the centred one full size, and the page dots under them */
@Composable
private fun WatchfaceCarousel(
    state: PagerState,
    faces: List<String>,
    card: @Composable (page: Int) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // The pager needs a fixed height. It is the height of the tallest card, measured with the
        // picture's real size on this phone and the texts at the wearer's font size: a fixed
        // number squeezed the button on a phone with large text and left a gap on one with small.
        val pageWidth = maxWidth - WatchfaceCarouselDefaults.ContentPadding * 2
        val contentWidth = pageWidth - AapsSpacing.large * 2
        val imageSize = min(WatchfaceCarouselDefaults.ImageSize, contentWidth)
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val titleStyle = MaterialTheme.typography.titleMedium
        val summaryStyle = MaterialTheme.typography.bodySmall
        val textConstraints = Constraints(maxWidth = with(density) { contentWidth.roundToPx() })
        val textHeightPx = faces.maxOf { face ->
            textMeasurer.measure(pushedWatchfaceLabel(face), titleStyle, constraints = textConstraints).size.height +
                textMeasurer.measure(pushedWatchfaceSummary(face), summaryStyle, constraints = textConstraints).size.height
        }
        val cardHeight = imageSize + with(density) { textHeightPx.toDp() } + WatchfaceCarouselDefaults.ButtonAndGapsHeight
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalPager(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight),
                contentPadding = PaddingValues(horizontal = WatchfaceCarouselDefaults.ContentPadding),
                pageSpacing = WatchfaceCarouselDefaults.PageSpacing
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val pageOffset = ((state.currentPage - page) + state.currentPageOffsetFraction).absoluteValue
                            val fraction = 1f - pageOffset.coerceIn(0f, 1f)
                            val scale = lerp(WatchfaceCarouselDefaults.MIN_SCALE, 1f, fraction)
                            scaleX = scale
                            scaleY = scale
                            alpha = lerp(WatchfaceCarouselDefaults.MIN_ALPHA, 1f, fraction)
                        }
                ) {
                    card(page)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AapsSpacing.medium),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(state.pageCount) { page ->
                    val isSelected = page == state.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = AapsSpacing.small)
                            .width(if (isSelected) 24.dp else 8.dp)
                            .height(8.dp)
                            .background(
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                    )
                }
            }
        }
    }
}

/**
 * One face: its picture, name and summary. The face on the watch is outlined and says so; the
 * others get the button that installs them. [hint] is a quiet note for a state that passes by
 * itself. [customImage] is the wearer's own zip preview, shown on the custom face's card when one
 * is loaded.
 */
@Composable
private fun WatchfaceCard(
    face: String,
    customImage: ImageBitmap?,
    selected: Boolean,
    hint: String?,
    onUse: () -> Unit
) {
    val label = pushedWatchfaceLabel(face)
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AapsSpacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
        ) {
            val imageModifier = Modifier
                .widthIn(max = WatchfaceCarouselDefaults.ImageSize)
                .fillMaxWidth()
                .aspectRatio(1f)
            if (face == PushedWatchfaceId.CWF && customImage != null) {
                Image(bitmap = customImage, contentDescription = label, modifier = imageModifier, contentScale = ContentScale.Fit)
            } else {
                val preview = when (face) {
                    PushedWatchfaceId.CWF    -> R.drawable.cwf_watchface_preview
                    PushedWatchfaceId.CIRCLE -> R.drawable.circle_watchface_preview
                    else                     -> R.drawable.wfs_watchface_preview
                }
                Image(painter = painterResource(preview), contentDescription = label, modifier = imageModifier, contentScale = ContentScale.Fit)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = pushedWatchfaceSummary(face),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            // The button and the "on the watch" line sit at the bottom whatever the summary's length
            Spacer(modifier = Modifier.weight(1f))
            if (selected) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = hint ?: stringResource(SyncStrings.wear_pushed_watchface_active),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Button(onClick = onUse) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(SyncStrings.wear_pushed_watchface_use))
                }
            }
        }
    }
}

private data class ButtonDef(val icon: ImageVector, val text: String, val onClick: () -> Unit)

@Composable
private fun ButtonRow(
    button1: ButtonDef,
    button2: ButtonDef?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        OutlinedButton(
            onClick = button1.onClick,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Icon(button1.icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = button1.text, textAlign = TextAlign.Center)
        }
        if (button2 != null) {
            OutlinedButton(
                onClick = button2.onClick,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Icon(button2.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = button2.text, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * @see CwfInfosContentPreview
 */
@Composable
internal fun CwfInfosContent(
    state: CwfInfosState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AapsSpacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        // Watchface image
        state.watchfaceImage?.let { image ->
            Image(
                bitmap = image,
                contentDescription = state.title,
                modifier = Modifier
                    .size(300.dp)
                    .align(Alignment.CenterHorizontally),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(AapsSpacing.medium))
        }

        // Metadata
        Text(text = state.fileName, style = MaterialTheme.typography.bodyMedium)
        Text(text = state.author, style = MaterialTheme.typography.bodyMedium)
        Text(text = state.createdAt, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = state.version,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.isVersionOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        if (state.comment.isNotBlank()) {
            Text(text = state.comment, style = MaterialTheme.typography.bodyMedium)
        }

        // Preferences section
        if (state.preferences.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = AapsSpacing.small))
            Text(
                text = state.prefTitle,
                style = MaterialTheme.typography.titleSmall
            )
            // Plain rows rather than ListItem: ListItem enforces its own 56dp minimum height, which
            // left these lines spread far wider apart than the metadata lines just above them.
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                state.preferences.forEach { pref ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = pref.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (pref.isEnabled) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = stringResource(if (pref.isEnabled) SyncStrings.enabled else SyncStrings.disabled),
                            tint = if (pref.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // View elements section
        if (state.viewElements.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = AapsSpacing.small))
            Text(
                text = stringResource(SyncStrings.cwf_infos_view_title),
                style = MaterialTheme.typography.titleSmall
            )
            // Same reason as the preference rows above - see there.
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                state.viewElements.forEach { viewItem ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = viewItem.key,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(text = viewItem.comment, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

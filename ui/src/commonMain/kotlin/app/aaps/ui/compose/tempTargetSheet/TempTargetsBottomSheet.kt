package app.aaps.ui.compose.tempTargetSheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.aaps.core.data.model.TTPreset
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.TonalIcon
import app.aaps.core.ui.compose.consumeOverscroll
import app.aaps.core.ui.compose.formatMinutesAsDuration
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.ttReasonColor
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.overview.chips.toIcon

/**
 * The temp target presets as a plain list, like the Scenes sheet. Tapping one closes the sheet and
 * hands the preset to [onPresetClick], which asks for confirmation before it is set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TempTargetsBottomSheet(
    presets: List<TTPreset>,
    onPresetClick: (TTPreset) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val profileUtil = LocalProfileUtil.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .consumeOverscroll()
                .verticalScroll(rememberScrollState())
                .padding(bottom = AapsSpacing.xxLarge)
        ) {
            Text(
                text = stringResource(UiStrings.temp_target_list),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AapsSpacing.xxLarge, vertical = AapsSpacing.large)
            )
            presets.forEach { preset ->
                val color = preset.reason.ttReasonColor(AapsTheme.generalColors)
                ListItem(
                    headlineContent = { Text(text = preset.name ?: preset.displayName ?: "?", color = color) },
                    supportingContent = {
                        Text(
                            text = stringResource(
                                UiStrings.temp_target_list_summary,
                                profileUtil.fromMgdlToStringWithUnits(preset.targetValue),
                                formatMinutesAsDuration((preset.duration / 60_000L).toInt())
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = { TonalIcon(icon = preset.reason.toIcon(), color = color) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.clickable {
                        onDismiss()
                        onPresetClick(preset)
                    }
                )
            }
        }
    }
}

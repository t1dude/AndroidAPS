package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.TonalIcon
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The chips that can be shown in the row under the glucose circle. [id] is what is stored. */
enum class CircleChip(val id: String, val label: TextRef, val elementType: ElementType) {

    IOB("iob", CoreUiStrings.iob, ElementType.INSULIN),
    COB("cob", CoreUiStrings.cob, ElementType.COB),
    SENSITIVITY("sens", CoreUiStrings.sensitivity, ElementType.SENSITIVITY),
    TEMP_TARGET("tt", CoreUiStrings.temporary_target, ElementType.TEMP_TARGET_MANAGEMENT),
    TBR("tbr", CoreUiStrings.temp_basal, ElementType.TEMP_BASAL),
    PROFILE("profile", CoreUiStrings.profile, ElementType.PROFILE_MANAGEMENT)
}

/** One chip with its place in the row and whether it is shown. */
data class CircleChipSetting(val chip: CircleChip, val visible: Boolean)

/**
 * Stored form: chip ids in order, separated by commas, a hidden chip starts with "!".
 * Example: "iob,cob,!sens,tt,tbr,profile". Empty or broken text gives all chips in the default order.
 * A chip that is missing from the text (added in a newer version) is put at the end, shown.
 */
object CircleChipsSerializer {

    private const val HIDDEN = "!"

    fun fromString(text: String): List<CircleChipSetting> {
        val parsed = text.split(",").mapNotNull { part ->
            val hidden = part.startsWith(HIDDEN)
            val id = part.removePrefix(HIDDEN).trim()
            CircleChip.entries.find { it.id == id }?.let { CircleChipSetting(it, !hidden) }
        }.distinctBy { it.chip }
        val missing = CircleChip.entries.filter { chip -> parsed.none { it.chip == chip } }
        return parsed + missing.map { CircleChipSetting(it, visible = true) }
    }

    fun toString(settings: List<CircleChipSetting>): String =
        settings.joinToString(",") { (if (it.visible) "" else HIDDEN) + it.chip.id }
}

/**
 * Bottom sheet to show, hide and reorder the chips under the glucose circle.
 * Drag a row by its handle to move it. Every change is handed to [onChange] at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircleChipsBottomSheet(
    settings: List<CircleChipSetting>,
    onChange: (List<CircleChipSetting>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Local copy, so a drag moves smoothly before the stored value comes back
    var items by remember { mutableStateOf(settings) }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
        onChange(items)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(bottom = AapsSpacing.xxLarge)) {
            Text(
                text = stringResource(UiStrings.circle_chips_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AapsSpacing.xxLarge, vertical = AapsSpacing.large)
            )
            LazyColumn(state = lazyListState, modifier = Modifier.fillMaxWidth()) {
                items(items, key = { it.chip.id }) { setting ->
                    ReorderableItem(reorderableState, key = setting.chip.id) { isDragging ->
                        val elevation = if (isDragging) 8.dp else 0.dp
                        Surface(shadowElevation = elevation, tonalElevation = elevation) {
                            ListItem(
                                headlineContent = { Text(stringResource(setting.chip.label)) },
                                leadingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                                            Icon(
                                                Icons.Default.DragHandle,
                                                contentDescription = stringResource(CoreUiStrings.reorder),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(AapsSpacing.small))
                                        TonalIcon(icon = setting.chip.elementType.icon(), color = setting.chip.elementType.color())
                                    }
                                },
                                trailingContent = {
                                    Switch(
                                        checked = setting.visible,
                                        onCheckedChange = { checked ->
                                            items = items.map { if (it.chip == setting.chip) it.copy(visible = checked) else it }
                                            onChange(items)
                                        }
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                            )
                        }
                    }
                }
            }
        }
    }
}

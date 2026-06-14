package eu.kanade.presentation.vault.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.vault.VaultMangaScreenModel
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun VaultLabelChips(
    labels: List<VaultLabel>,
    onClickLabel: (VaultLabel) -> Unit,
    onClickAdd: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEach { label ->
            VaultLabelChip(
                label = label,
                onClick = { onClickLabel(label) },
            )
        }
        SuggestionChip(
            onClick = onClickAdd,
            label = {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(MR.strings.vault_label_add),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
}

@Composable
private fun VaultLabelChip(
    label: VaultLabel,
    onClick: () -> Unit,
) {
    val colors = if (label.isSensitive) {
        SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    } else {
        SuggestionChipDefaults.suggestionChipColors()
    }
    SuggestionChip(
        onClick = onClick,
        label = {
            Text(
                text = label.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = colors,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultLabelActionSheet(
    label: VaultLabel,
    onToggleSensitivity: () -> Unit,
    onRemove: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier.padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label.name,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            VaultLabelSheetItem(
                label = stringResource(
                    if (label.isSensitive) {
                        MR.strings.vault_label_mark_not_sensitive
                    } else {
                        MR.strings.vault_label_mark_sensitive
                    },
                ),
                onClick = onToggleSensitivity,
            )
            VaultLabelSheetItem(
                label = stringResource(MR.strings.vault_label_remove_from_manga),
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun VaultLabelSheetItem(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun VaultAddLabelDialog(
    state: VaultMangaScreenModel.State,
    onAssignLabel: (VaultLabel) -> Unit,
    onCreateLabel: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var newLabel by remember { mutableStateOf("") }
    val assigned = state.mangaLabels.map { it.identity.value }.toSet()
    val newLabelName = newLabel.trim()
    val unassignedLabels = searchUnassignedVaultLabels(
        labels = state.vaultLabels,
        assignedLabelIdentities = assigned,
        query = newLabelName,
    )
    val duplicateLabelName = newLabelName.isNotBlank() &&
        state.vaultLabels.any { normalizeLabelName(it.name) == normalizeLabelName(newLabelName) }
    val canCreateLabel = newLabelName.isNotBlank() && !duplicateLabelName

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.vault_label_add)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetadataTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    label = stringResource(MR.strings.vault_label_search_or_new),
                    singleLine = true,
                )
                if (duplicateLabelName) {
                    Text(
                        text = stringResource(MR.strings.vault_metadata_label_duplicate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (unassignedLabels.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column {
                            unassignedLabels.forEach { label ->
                                VaultAddLabelItem(
                                    label = label,
                                    onClick = { onAssignLabel(label) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canCreateLabel,
                onClick = { onCreateLabel(newLabelName) },
            ) {
                Text(stringResource(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun VaultAddLabelItem(
    label: VaultLabel,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (label.isSensitive) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = stringResource(MR.strings.vault_label_sensitive),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

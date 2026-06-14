package eu.kanade.presentation.vault.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.vault.VaultMangaScreenModel
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun VaultMetadataEditDialog(
    state: VaultMangaScreenModel.State,
    onDismissRequest: () -> Unit,
    onSave: (VaultMangaScreenModel.VaultMetadataEdit) -> Unit,
) {
    val manga = state.manga ?: return
    var title by remember(manga.id) { mutableStateOf(manga.metadata.title) }
    var author by remember(manga.id) { mutableStateOf(manga.metadata.author.orEmpty()) }
    var artist by remember(manga.id) { mutableStateOf(manga.metadata.artist.orEmpty()) }
    var description by remember(manga.id) { mutableStateOf(manga.metadata.description.orEmpty()) }
    var status by remember(manga.id) { mutableStateOf(manga.metadata.status) }
    val labels = remember(manga.id, state.vaultLabels, state.mangaLabels) { state.labelEdits() }
    val titleError = title.isBlank()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.vault_metadata_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetadataTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = stringResource(MR.strings.title),
                    isError = titleError,
                    supportingText = if (titleError) {
                        stringResource(MR.strings.local_manga_metadata_title_required)
                    } else {
                        null
                    },
                    singleLine = true,
                )
                MetadataTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = stringResource(MR.strings.author),
                    singleLine = true,
                )
                MetadataTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = stringResource(MR.strings.artist),
                    singleLine = true,
                )
                MetadataTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(MR.strings.description),
                    minLines = 3,
                )
                VaultStatusDropdown(
                    status = status,
                    onStatusChange = { status = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.isPublishingMetadata && !titleError,
                onClick = {
                    onSave(
                        VaultMangaScreenModel.VaultMetadataEdit(
                            title = title,
                            author = author,
                            artist = artist,
                            description = description,
                            status = status,
                            labels = labels,
                        ),
                    )
                },
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

internal fun searchUnassignedVaultLabels(
    labels: List<VaultLabel>,
    assignedLabelIdentities: Set<String>,
    query: String,
): List<VaultLabel> {
    val labelQuery = normalizeLabelName(query)
    if (labelQuery.isBlank()) return emptyList()

    return labels
        .filterNot { it.identity.value in assignedLabelIdentities }
        .filter { normalizeLabelName(it.name).contains(labelQuery) }
        .sortedWith(
            compareBy<VaultLabel> { normalizeLabelName(it.name) != labelQuery }
                .thenBy { it.sortKey },
        )
        .take(MAX_LABEL_AUTOCOMPLETE_RESULT_COUNT)
}

internal fun normalizeLabelName(name: String): String = name.trim().lowercase()

private const val MAX_LABEL_AUTOCOMPLETE_RESULT_COUNT = 1

private fun VaultMangaScreenModel.State.labelEdits(): List<VaultMangaScreenModel.VaultLabelEdit> {
    val assigned = mangaLabels.map { it.identity.value }.toSet()
    return vaultLabels.map {
        VaultMangaScreenModel.VaultLabelEdit(
            identity = it.identity.value,
            name = it.name,
            isSensitive = it.isSensitive,
            assigned = it.identity.value in assigned,
        )
    }
}

@Composable
internal fun MetadataTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultStatusDropdown(
    status: VaultMangaStatus,
    onStatusChange: (VaultMangaStatus) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember { VaultMetadataStatusOption.entries }
    val selected = options.firstOrNull { it.status == status } ?: VaultMetadataStatusOption.Unknown

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(selected.label),
            onValueChange = {},
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
            readOnly = true,
            label = { Text(stringResource(MR.strings.status)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    leadingIcon = if (option == selected) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        onStatusChange(option.status)
                        expanded = false
                    },
                )
            }
        }
    }
}

private enum class VaultMetadataStatusOption(
    val status: VaultMangaStatus,
    val label: StringResource,
) {
    Unknown(VaultMangaStatus.UNKNOWN, MR.strings.unknown),
    Ongoing(VaultMangaStatus.ONGOING, MR.strings.ongoing),
    Completed(VaultMangaStatus.COMPLETED, MR.strings.completed),
    Licensed(VaultMangaStatus.LICENSED, MR.strings.licensed),
    PublishingFinished(VaultMangaStatus.PUBLISHING_FINISHED, MR.strings.publishing_finished),
    Cancelled(VaultMangaStatus.CANCELLED, MR.strings.cancelled),
    OnHiatus(VaultMangaStatus.ON_HIATUS, MR.strings.on_hiatus),
}

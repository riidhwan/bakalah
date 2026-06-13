package eu.kanade.presentation.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.ui.vault.VaultMangaScreenModel
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun VaultMangaScreen(
    state: VaultMangaScreenModel.State,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onClickCache: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickEvict: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickRetry: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickRead: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onChapterThumbnailVisible: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickDelete: () -> Unit,
    onClickSaveMetadata: (VaultMangaScreenModel.VaultMetadataEdit) -> Unit,
    onClickAssignLabel: (VaultLabel) -> Unit,
    onClickCreateLabel: (String) -> Unit,
    onClickRemoveLabel: (VaultLabel) -> Unit,
    onClickToggleLabelSensitivity: (VaultLabel) -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showMetadataEdit by remember { mutableStateOf(false) }

    LaunchedEffect(state.metadataPublishSuccessCount) {
        if (state.metadataPublishSuccessCount > 0) {
            showMetadataEdit = false
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = null,
                navigateUp = navigateUp,
                actions = {
                    AppBarActions(
                        listOf(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.vault_action_edit_metadata),
                                onClick = {
                                    if (!state.isPublishingMetadata) {
                                        showMetadataEdit = true
                                    }
                                },
                            ),
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.vault_action_delete_manga),
                                onClick = {
                                    if (!state.isDeleting) {
                                        showDeleteConfirmation = true
                                    }
                                },
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        when {
            state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
            state.manga == null -> EmptyScreen(
                stringRes = MR.strings.vault_manga_not_found,
                modifier = Modifier.padding(contentPadding),
            )
            state.chapters.isEmpty() -> EmptyScreen(
                stringRes = MR.strings.vault_empty_chapters,
                modifier = Modifier.padding(contentPadding),
            )
            else -> VaultChapterList(
                state = state,
                contentPadding = contentPadding,
                onClickCache = onClickCache,
                onClickEvict = onClickEvict,
                onClickRetry = onClickRetry,
                onClickRead = onClickRead,
                onChapterThumbnailVisible = onChapterThumbnailVisible,
                onClickAssignLabel = onClickAssignLabel,
                onClickCreateLabel = onClickCreateLabel,
                onClickRemoveLabel = onClickRemoveLabel,
                onClickToggleLabelSensitivity = onClickToggleLabelSensitivity,
            )
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(MR.strings.vault_delete_manga_confirm_title)) },
            text = { Text(stringResource(MR.strings.vault_delete_manga_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onClickDelete()
                    },
                ) {
                    Text(stringResource(MR.strings.vault_action_delete_manga))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    if (showMetadataEdit && state.manga != null) {
        VaultMetadataEditDialog(
            state = state,
            onDismissRequest = { showMetadataEdit = false },
            onSave = {
                showMetadataEdit = false
                onClickSaveMetadata(it)
            },
        )
    }
}

@Composable
private fun VaultChapterList(
    state: VaultMangaScreenModel.State,
    contentPadding: PaddingValues,
    onClickCache: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickEvict: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickRetry: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickRead: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onChapterThumbnailVisible: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickAssignLabel: (VaultLabel) -> Unit,
    onClickCreateLabel: (String) -> Unit,
    onClickRemoveLabel: (VaultLabel) -> Unit,
    onClickToggleLabelSensitivity: (VaultLabel) -> Unit,
) {
    FastScrollLazyColumn(
        contentPadding = contentPadding,
    ) {
        item(key = "summary", contentType = "summary") {
            VaultMangaHeader(
                state = state,
                onPrimaryAction = {
                    state.primaryActionChapter()?.let { chapter ->
                        if (chapter.state == VaultCacheState.CACHED) {
                            onClickRead(chapter)
                        } else {
                            onClickCache(chapter)
                        }
                    }
                },
                onClickAssignLabel = onClickAssignLabel,
                onClickCreateLabel = onClickCreateLabel,
                onClickRemoveLabel = onClickRemoveLabel,
                onClickToggleLabelSensitivity = onClickToggleLabelSensitivity,
                modifier = Modifier.animateItem(),
            )
        }
        items(
            items = state.chapters,
            key = { "vault-chapter-${it.chapter.id}" },
            contentType = { "vault-chapter" },
        ) { item ->
            VaultChapterListItem(
                item = item,
                onClickCache = { onClickCache(item) },
                onClickEvict = { onClickEvict(item) },
                onClickRetry = { onClickRetry(item) },
                onClickRead = { onClickRead(item) },
                onChapterThumbnailVisible = { onChapterThumbnailVisible(item) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun VaultMangaHeader(
    state: VaultMangaScreenModel.State,
    onPrimaryAction: () -> Unit,
    onClickAssignLabel: (VaultLabel) -> Unit,
    onClickCreateLabel: (String) -> Unit,
    onClickRemoveLabel: (VaultLabel) -> Unit,
    onClickToggleLabelSensitivity: (VaultLabel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val manga = state.manga ?: return
    var selectedLabel by remember { mutableStateOf<VaultLabel?>(null) }
    var showAddLabel by remember { mutableStateOf(false) }
    val cachedCount = state.chapters.count { it.state == VaultCacheState.CACHED }
    val primaryAction = state.primaryActionChapter()
    val primaryActionText = when {
        primaryAction == null -> null
        primaryAction.state == VaultCacheState.CACHED -> stringResource(MR.strings.vault_action_read_cached)
        cachedCount > 0 -> stringResource(MR.strings.vault_action_read_cached)
        else -> stringResource(MR.strings.vault_action_cache_first_chapter)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            VaultCover(
                coverUri = state.coverUri,
                modifier = Modifier
                    .width(88.dp)
                    .aspectRatio(2f / 3f),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = manga.metadata.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                manga.metadata.author?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                VaultLabelChips(
                    labels = state.mangaLabels,
                    onClickLabel = { selectedLabel = it },
                    onClickAdd = { showAddLabel = true },
                )
                VaultMangaStats(
                    chapterCount = state.chapters.size,
                    cachedCount = cachedCount,
                    vaultStorageUsageBytes = state.vaultStorageUsageBytes,
                )
                primaryActionText?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Button(
                        onClick = onPrimaryAction,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(it)
                    }
                }
            }
        }
    }

    selectedLabel?.let { label ->
        VaultLabelActionSheet(
            label = label,
            onToggleSensitivity = {
                selectedLabel = null
                onClickToggleLabelSensitivity(label)
            },
            onRemove = {
                selectedLabel = null
                onClickRemoveLabel(label)
            },
            onDismissRequest = { selectedLabel = null },
        )
    }

    if (showAddLabel) {
        VaultAddLabelDialog(
            state = state,
            onAssignLabel = {
                showAddLabel = false
                onClickAssignLabel(it)
            },
            onCreateLabel = {
                showAddLabel = false
                onClickCreateLabel(it)
            },
            onDismissRequest = { showAddLabel = false },
        )
    }
}

@Composable
private fun VaultLabelChips(
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
private fun VaultLabelActionSheet(
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
private fun VaultAddLabelDialog(
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

@Composable
private fun VaultMetadataEditDialog(
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

private fun normalizeLabelName(name: String): String = name.trim().lowercase()

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
private fun MetadataTextField(
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

@Composable
private fun VaultCover(
    coverUri: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box {
            if (coverUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverUri)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(32.dp),
                )
            }
        }
    }
}

@Composable
private fun VaultMangaStats(
    chapterCount: Int,
    cachedCount: Int,
    vaultStorageUsageBytes: Long,
) {
    Text(
        text = stringResource(
            MR.strings.vault_manga_detail_stats,
            chapterCount,
            cachedCount,
            formatBytes(vaultStorageUsageBytes),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VaultChapterListItem(
    item: VaultMangaScreenModel.VaultChapterItem,
    onClickCache: () -> Unit,
    onClickEvict: () -> Unit,
    onClickRetry: () -> Unit,
    onClickRead: () -> Unit,
    onChapterThumbnailVisible: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(item.chapter.id, item.needsThumbnailLoad) {
        if (item.needsThumbnailLoad) {
            onChapterThumbnailVisible()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClickRead)
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VaultChapterThumbnail(
                thumbnailUri = item.thumbnailUri,
                modifier = Modifier.size(56.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.chapter.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            MR.strings.vault_chapter_availability,
                            formatBytes(item.chapter.content.sizeBytes),
                            item.state.availabilityLabel(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ChapterStateAction(
                state = item.state,
                onClickCache = onClickCache,
                onClickRetry = onClickRetry,
            )
            if (item.state == VaultCacheState.CACHED) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(MR.strings.action_menu_overflow_description),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.vault_action_evict_from_device)) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onClickEvict()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultChapterThumbnail(
    thumbnailUri: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.extraSmall),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box {
            if (thumbnailUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(thumbnailUri)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun ChapterStateAction(
    state: VaultCacheState,
    onClickCache: () -> Unit,
    onClickRetry: () -> Unit,
) {
    when (state) {
        VaultCacheState.VAULT_ONLY -> IconButton(onClick = onClickCache) {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = stringResource(MR.strings.vault_action_cache),
            )
        }
        VaultCacheState.FAILED,
        VaultCacheState.INTEGRITY_FAULT,
        -> IconButton(onClick = onClickRetry) {
            Icon(
                imageVector = if (state == VaultCacheState.INTEGRITY_FAULT) {
                    Icons.Outlined.WarningAmber
                } else {
                    Icons.Outlined.ErrorOutline
                },
                tint = MaterialTheme.colorScheme.error,
                contentDescription = stringResource(MR.strings.action_retry),
            )
        }
        VaultCacheState.QUEUED,
        VaultCacheState.CACHING,
        VaultCacheState.PUBLISHING,
        -> StateIcon(
            icon = Icons.Outlined.HourglassEmpty,
            contentDescription = state.label(),
        )
        VaultCacheState.CACHED -> Unit
    }
}

@Composable
private fun StateIcon(
    icon: ImageVector,
    contentDescription: String,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .size(24.dp),
    )
}

@Composable
private fun VaultCacheState.label(): String {
    val res = when (this) {
        VaultCacheState.VAULT_ONLY -> MR.strings.vault_state_vault_only
        VaultCacheState.QUEUED -> MR.strings.vault_state_queued
        VaultCacheState.CACHING -> MR.strings.vault_state_caching
        VaultCacheState.PUBLISHING -> MR.strings.vault_state_publishing
        VaultCacheState.CACHED -> MR.strings.vault_state_cached
        VaultCacheState.FAILED -> MR.strings.vault_state_failed
        VaultCacheState.INTEGRITY_FAULT -> MR.strings.vault_state_integrity_fault
    }
    return stringResource(res)
}

@Composable
private fun VaultCacheState.availabilityLabel(): String {
    val res = when (this) {
        VaultCacheState.VAULT_ONLY -> MR.strings.vault_state_in_vault
        VaultCacheState.CACHED -> MR.strings.vault_state_on_device
        VaultCacheState.QUEUED -> MR.strings.vault_state_queued
        VaultCacheState.CACHING -> MR.strings.vault_state_caching
        VaultCacheState.PUBLISHING -> MR.strings.vault_state_publishing
        VaultCacheState.FAILED -> MR.strings.vault_state_failed
        VaultCacheState.INTEGRITY_FAULT -> MR.strings.vault_state_integrity_fault
    }
    return stringResource(res)
}

private fun VaultMangaScreenModel.State.primaryActionChapter(): VaultMangaScreenModel.VaultChapterItem? {
    return chapters.firstOrNull { it.state == VaultCacheState.CACHED }
        ?: chapters.firstOrNull { it.state == VaultCacheState.VAULT_ONLY }
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

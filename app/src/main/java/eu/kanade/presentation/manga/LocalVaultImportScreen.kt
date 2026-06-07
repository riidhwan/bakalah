package eu.kanade.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.manga.LocalVaultImportScreenModel
import tachiyomi.domain.vault.model.LocalVaultImportChapterPlan
import tachiyomi.domain.vault.model.LocalVaultImportDuplicateState
import tachiyomi.domain.vault.model.LocalVaultImportTarget
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import java.text.DecimalFormat

@Composable
fun LocalVaultImportScreen(
    state: LocalVaultImportScreenModel.State,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onOpenSettings: () -> Unit,
    onTargetSelected: (LocalVaultImportScreenModel.TargetSelection) -> Unit,
    onChapterSelected: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onOpenVault: () -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.vault_import_title),
                subtitle = state.mangaTitle
                    .takeIf { it.isNotBlank() }
                    ?.let { stringResource(MR.strings.vault_import_subtitle_from_local, it) },
                navigateUp = navigateUp.takeUnless { state.isImporting },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (!state.isLoading && state.plan != null) {
                ImportBottomBar(
                    state = state,
                    onImport = onImport,
                    onOpenVault = onOpenVault,
                    onDone = onDone,
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        when {
            state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
            state.plan == null && state.error != null -> ImportBlockingError(
                error = state.error,
                contentPadding = contentPadding,
                onOpenSettings = onOpenSettings,
                onRetry = onRetry,
            )
            else -> ImportContent(
                state = state,
                contentPadding = contentPadding,
                onOpenSettings = onOpenSettings,
                onTargetSelected = onTargetSelected,
                onChapterSelected = onChapterSelected,
                onSelectAll = onSelectAll,
                onSelectNone = onSelectNone,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun ImportBlockingError(
    error: LocalVaultImportScreenModel.ImportError,
    contentPadding: PaddingValues,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = error.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error == LocalVaultImportScreenModel.ImportError.INCOMPLETE_CONFIGURATION) {
                Button(onClick = onOpenSettings) {
                    Text(stringResource(MR.strings.label_settings))
                }
            } else {
                Button(onClick = onRetry) {
                    Text(stringResource(MR.strings.action_retry))
                }
            }
        }
    }
}

@Composable
private fun ImportContent(
    state: LocalVaultImportScreenModel.State,
    contentPadding: PaddingValues,
    onOpenSettings: () -> Unit,
    onTargetSelected: (LocalVaultImportScreenModel.TargetSelection) -> Unit,
    onChapterSelected: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onRetry: () -> Unit,
) {
    val importableChapters = state.plan
        ?.chapters
        .orEmpty()
        .filter { it.duplicateState != LocalVaultImportDuplicateState.EXACT }
    val skippedChapters = state.plan
        ?.chapters
        .orEmpty()
        .filter { it.duplicateState == LocalVaultImportDuplicateState.EXACT }

    LazyColumn(
        contentPadding = contentPadding,
    ) {
        item(key = "summary", contentType = "summary") {
            ImportSummary(
                state = state,
                onOpenSettings = onOpenSettings,
                onTargetSelected = onTargetSelected,
                onRetry = onRetry,
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "chapter-header", contentType = "chapter-header") {
            ChapterSectionHeader(
                state = state,
                onSelectAll = onSelectAll,
                onSelectNone = onSelectNone,
                modifier = Modifier.animateItem(),
            )
        }

        if (importableChapters.isNotEmpty()) {
            item(key = "importable-header", contentType = "section-header") {
                ChapterGroupHeader(
                    text = stringResource(MR.strings.vault_import_importable),
                    modifier = Modifier.animateItem(),
                )
            }
        }
        items(
            items = importableChapters,
            key = { "import-chapter-${it.chapter.selectionId}" },
            contentType = { "import-chapter" },
        ) { item ->
            ChapterImportItem(
                item = item,
                checked = item.chapter.selectionId in state.selectedChapterIds,
                enabled = !state.isImporting && state.success == null,
                onCheckedChange = { checked -> onChapterSelected(item.chapter.selectionId, checked) },
                modifier = Modifier.animateItem(),
            )
        }

        if (skippedChapters.isNotEmpty()) {
            item(key = "skipped-header", contentType = "section-header") {
                ChapterGroupHeader(
                    text = stringResource(MR.strings.vault_import_skipped),
                    modifier = Modifier.animateItem(),
                )
            }
        }
        items(
            items = skippedChapters,
            key = { "skipped-chapter-${it.chapter.selectionId}" },
            contentType = { "skipped-chapter" },
        ) { item ->
            SkippedChapterItem(
                item = item,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun ImportSummary(
    state: LocalVaultImportScreenModel.State,
    onOpenSettings: () -> Unit,
    onTargetSelected: (LocalVaultImportScreenModel.TargetSelection) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TargetSelector(
            selectedTarget = state.selectedTarget,
            planTarget = state.plan?.target,
            targets = state.availableTargets,
            enabled = !state.isImporting && state.success == null,
            onTargetSelected = onTargetSelected,
        )

        if (state.success == null) {
            PlannedChanges(state)
        }

        state.error?.let { error ->
            Text(
                text = error.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (error == LocalVaultImportScreenModel.ImportError.INCOMPLETE_CONFIGURATION) {
                Button(onClick = onOpenSettings) {
                    Text(stringResource(MR.strings.label_settings))
                }
            }
            if (error.isRetryable) {
                TextButton(onClick = onRetry, enabled = !state.isImporting) {
                    Text(stringResource(MR.strings.action_retry))
                }
            }
        }

        state.success?.let { success ->
            ImportSuccessSummary(success)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSelector(
    selectedTarget: LocalVaultImportScreenModel.TargetSelection?,
    planTarget: LocalVaultImportTarget?,
    targets: List<VaultManga>,
    enabled: Boolean,
    onTargetSelected: (LocalVaultImportScreenModel.TargetSelection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = true },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(MR.strings.vault_import_target),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = selectedTarget.label(targets),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    planTarget?.reasonLabel()?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
            }
        }

        if (expanded) {
            ModalBottomSheet(onDismissRequest = { expanded = false }) {
                Column(
                    modifier = Modifier.padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(MR.strings.vault_import_choose_target),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TargetSheetItem(
                        label = stringResource(MR.strings.vault_import_target_create_new),
                        selected = selectedTarget == LocalVaultImportScreenModel.TargetSelection.CreateNew,
                        onClick = {
                            expanded = false
                            onTargetSelected(LocalVaultImportScreenModel.TargetSelection.CreateNew)
                        },
                    )
                    targets.forEach { target ->
                        TargetSheetItem(
                            label = target.metadata.title,
                            selected = selectedTarget == LocalVaultImportScreenModel.TargetSelection.Existing(
                                target.id,
                            ),
                            onClick = {
                                expanded = false
                                onTargetSelected(LocalVaultImportScreenModel.TargetSelection.Existing(target.id))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetSheetItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(Icons.Outlined.Check, contentDescription = null)
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlannedChanges(state: LocalVaultImportScreenModel.State) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(MR.strings.vault_import_planned_changes),
            style = MaterialTheme.typography.titleSmall,
        )
        if (state.selectedImportableCount == 0) {
            Text(
                text = stringResource(MR.strings.vault_import_no_selected_chapters),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.selectedCbzConversionCount > 0) {
            Text(
                text = stringResource(
                    MR.strings.vault_import_cbz_conversion_plan,
                    state.selectedCbzConversionCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val uploadAsIsCount = state.selectedImportableCount - state.selectedCbzConversionCount
        if (uploadAsIsCount > 0) {
            Text(
                text = stringResource(MR.strings.vault_import_upload_as_is_plan, uploadAsIsCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportSuccessSummary(success: LocalVaultImportScreenModel.ImportSuccess) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(MR.strings.vault_import_complete),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                MR.strings.vault_import_success,
                success.importedChapterCount,
                success.skippedExactDuplicateCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChapterSectionHeader(
    state: LocalVaultImportScreenModel.State,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(MR.strings.vault_import_chapters),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                MR.strings.vault_import_selected_count,
                state.selectedImportableCount,
                state.recognizedChapterCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onSelectAll,
                enabled = !state.isImporting && state.success == null,
            ) {
                Text(stringResource(MR.strings.action_select_all))
            }
            TextButton(
                onClick = onSelectNone,
                enabled = !state.isImporting && state.success == null,
            ) {
                Text(stringResource(MR.strings.vault_import_select_none))
            }
        }
    }
}

@Composable
private fun ChapterGroupHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ImportBottomBar(
    state: LocalVaultImportScreenModel.State,
    onImport: () -> Unit,
    onOpenVault: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.isImporting) {
                Text(
                    text = stringResource(MR.strings.vault_import_phase_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(MR.strings.vault_importing))
                }
            } else if (state.success != null) {
                Button(
                    onClick = onOpenVault,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (state.success.vaultMangaId != null) {
                                MR.strings.vault_import_action_open_in_vault
                            } else {
                                MR.strings.vault_import_action_open_vault
                            },
                        ),
                    )
                }
                TextButton(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(MR.strings.action_done))
                }
            } else {
                Text(
                    text = stringResource(
                        MR.strings.vault_import_selected_source_size,
                        state.selectedImportableCount,
                        formatImportBytes(state.selectedSourceSizeBytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onImport,
                    enabled = state.selectedTarget != null &&
                        state.selectedImportableCount > 0 &&
                        !state.isImporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(MR.strings.vault_action_import_to_vault))
                }
            }
        }
    }
}

@Composable
private fun ChapterImportItem(
    item: LocalVaultImportChapterPlan,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.chapter.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.duplicateState == LocalVaultImportDuplicateState.POSSIBLE) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = item.importableSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
}

@Composable
private fun SkippedChapterItem(
    item: LocalVaultImportChapterPlan,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 72.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.chapter.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    MR.strings.vault_import_skipped_chapter_summary,
                    formatImportBytes(item.chapter.sizeBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
}

@Composable
private fun LocalVaultImportChapterPlan.importableSummary(): String {
    return buildList {
        add(formatImportBytes(chapter.sizeBytes))
        duplicateState.label()?.let { add(it) }
        if (chapter.requiresLocalCbzConversion) {
            add(stringResource(MR.strings.vault_import_converts_to_cbz))
        }
    }.joinToString(" · ")
}

@Composable
private fun LocalVaultImportScreenModel.TargetSelection?.label(targets: List<VaultManga>): String {
    return when (this) {
        null -> stringResource(MR.strings.vault_import_target_required)
        LocalVaultImportScreenModel.TargetSelection.CreateNew ->
            stringResource(MR.strings.vault_import_target_create_new)
        is LocalVaultImportScreenModel.TargetSelection.Existing ->
            targets.firstOrNull { it.id == mangaId }?.metadata?.title
                ?: stringResource(MR.strings.vault_import_target_user_selected)
    }
}

@Composable
private fun LocalVaultImportTarget.reasonLabel(): String? {
    return when (this) {
        LocalVaultImportTarget.CreateNew -> stringResource(MR.strings.vault_import_target_create_new)
        is LocalVaultImportTarget.Choose -> stringResource(MR.strings.vault_import_target_required)
        is LocalVaultImportTarget.Existing -> when (reason) {
            LocalVaultImportTarget.Reason.IMPORT_TARGET_HINT ->
                stringResource(MR.strings.vault_import_target_hint)
            LocalVaultImportTarget.Reason.EXACT_TITLE_MATCH ->
                stringResource(MR.strings.vault_import_target_exact_title)
            LocalVaultImportTarget.Reason.USER_SELECTED ->
                stringResource(MR.strings.vault_import_target_user_selected)
        }
    }
}

@Composable
private fun LocalVaultImportDuplicateState.label(): String? {
    return when (this) {
        LocalVaultImportDuplicateState.NONE -> null
        LocalVaultImportDuplicateState.EXACT -> stringResource(MR.strings.vault_import_already_in_vault)
        LocalVaultImportDuplicateState.POSSIBLE -> stringResource(MR.strings.vault_import_possible_duplicate)
    }
}

@Composable
private fun LocalVaultImportScreenModel.ImportError.label(): String {
    val res = when (this) {
        LocalVaultImportScreenModel.ImportError.INCOMPLETE_CONFIGURATION ->
            MR.strings.vault_import_error_incomplete_configuration
        LocalVaultImportScreenModel.ImportError.LOCAL_MANGA_NOT_FOUND ->
            MR.strings.vault_import_error_missing_local_manga
        LocalVaultImportScreenModel.ImportError.TARGET_REQUIRED ->
            MR.strings.vault_import_target_required
        LocalVaultImportScreenModel.ImportError.NOTHING_SELECTED ->
            MR.strings.vault_import_error_nothing_selected
        LocalVaultImportScreenModel.ImportError.MANIFEST_UNAVAILABLE ->
            MR.strings.vault_import_error_manifest_unavailable
        LocalVaultImportScreenModel.ImportError.IDENTITY_CHANGED ->
            MR.strings.vault_import_error_identity_changed
        LocalVaultImportScreenModel.ImportError.UPLOAD_FAILED ->
            MR.strings.vault_import_error_upload_failed
        LocalVaultImportScreenModel.ImportError.LOAD_FAILED ->
            MR.strings.vault_import_error_load_failed
    }
    return stringResource(res)
}

private val LocalVaultImportScreenModel.ImportError.isRetryable: Boolean
    get() = when (this) {
        LocalVaultImportScreenModel.ImportError.MANIFEST_UNAVAILABLE,
        LocalVaultImportScreenModel.ImportError.IDENTITY_CHANGED,
        LocalVaultImportScreenModel.ImportError.UPLOAD_FAILED,
        LocalVaultImportScreenModel.ImportError.LOAD_FAILED,
        -> true
        LocalVaultImportScreenModel.ImportError.INCOMPLETE_CONFIGURATION,
        LocalVaultImportScreenModel.ImportError.LOCAL_MANGA_NOT_FOUND,
        LocalVaultImportScreenModel.ImportError.TARGET_REQUIRED,
        LocalVaultImportScreenModel.ImportError.NOTHING_SELECTED,
        -> false
    }

private fun formatImportBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }
    val pattern = if (value >= 10 || unitIndex == 0) "0" else "0.0"
    return "${DecimalFormat(pattern).format(value)} ${units[unitIndex]}"
}

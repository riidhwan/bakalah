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
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.ui.manga.LocalVaultImportScreenModel
import tachiyomi.domain.vault.model.LocalVaultImportChapterPlan
import tachiyomi.domain.vault.model.LocalVaultImportDuplicateState
import tachiyomi.domain.vault.model.LocalVaultImportTarget
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

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
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.vault_import_title),
                subtitle = state.mangaTitle.takeIf { it.isNotBlank() },
                navigateUp = navigateUp.takeUnless { state.isImporting },
                scrollBehavior = scrollBehavior,
            )
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
                onImport = onImport,
                onRetry = onRetry,
                onOpenVault = onOpenVault,
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
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onOpenVault: () -> Unit,
) {
    LazyColumn(
        contentPadding = contentPadding,
    ) {
        item(key = "summary", contentType = "summary") {
            ImportSummary(
                state = state,
                onOpenSettings = onOpenSettings,
                onTargetSelected = onTargetSelected,
                onSelectAll = onSelectAll,
                onSelectNone = onSelectNone,
                onImport = onImport,
                onRetry = onRetry,
                onOpenVault = onOpenVault,
                modifier = Modifier.animateItem(),
            )
        }

        items(
            items = state.plan?.chapters.orEmpty(),
            key = { "import-chapter-${it.chapter.selectionId}" },
            contentType = { "import-chapter" },
        ) { item ->
            ChapterImportItem(
                item = item,
                checked = item.chapter.selectionId in state.selectedChapterIds,
                enabled = !state.isImporting &&
                    item.duplicateState != LocalVaultImportDuplicateState.EXACT &&
                    state.success == null,
                onCheckedChange = { checked -> onChapterSelected(item.chapter.selectionId, checked) },
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
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onOpenVault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TargetSelector(
            selectedTarget = state.selectedTarget,
            planTarget = state.plan?.target,
            targets = state.availableTargets,
            enabled = !state.isImporting && state.success == null,
            onTargetSelected = onTargetSelected,
        )

        Text(
            text = stringResource(
                MR.strings.vault_import_selected_count,
                state.selectedImportableCount,
                state.recognizedChapterCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
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

        if (state.selectedCbzConversionCount > 0 && state.success == null) {
            Text(
                text = stringResource(
                    MR.strings.vault_import_cbz_conversion_notice,
                    state.selectedCbzConversionCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
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
            Text(
                text = stringResource(
                    MR.strings.vault_import_success,
                    success.importedChapterCount,
                    success.skippedExactDuplicateCount,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = onOpenVault) {
                Text(
                    stringResource(
                        if (success.vaultMangaId != null) {
                            MR.strings.vault_import_action_open_in_vault
                        } else {
                            MR.strings.vault_import_action_open_vault
                        },
                    ),
                )
            }
        } ?: Button(
            onClick = onImport,
            enabled = state.selectedTarget != null &&
                state.selectedImportableCount > 0 &&
                !state.isImporting,
        ) {
            if (state.isImporting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(MR.strings.vault_importing))
            } else {
                Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(MR.strings.vault_action_import_to_vault))
            }
        }
    }
}

@Composable
private fun TargetSelector(
    selectedTarget: LocalVaultImportScreenModel.TargetSelection?,
    planTarget: LocalVaultImportTarget?,
    targets: List<VaultManga>,
    enabled: Boolean,
    onTargetSelected: (LocalVaultImportScreenModel.TargetSelection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(MR.strings.vault_import_target),
            style = MaterialTheme.typography.titleSmall,
        )
        AssistChip(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            label = {
                Text(
                    selectedTarget.label(targets),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        planTarget?.reasonLabel()?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.vault_import_target_create_new)) },
                onClick = {
                    expanded = false
                    onTargetSelected(LocalVaultImportScreenModel.TargetSelection.CreateNew)
                },
            )
            targets.forEach { target ->
                DropdownMenuItem(
                    text = { Text(target.metadata.title) },
                    onClick = {
                        expanded = false
                        onTargetSelected(LocalVaultImportScreenModel.TargetSelection.Existing(target.id))
                    },
                )
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.duplicateState.label()?.let { label ->
                    AssistChip(
                        onClick = {},
                        leadingIcon = if (item.duplicateState == LocalVaultImportDuplicateState.POSSIBLE) {
                            { Icon(Icons.Outlined.WarningAmber, contentDescription = null) }
                        } else {
                            null
                        },
                        label = { Text(label) },
                    )
                }
                Text(
                    text = "${item.chapter.sizeBytes} bytes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.chapter.requiresLocalCbzConversion) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(MR.strings.vault_import_converts_to_cbz)) },
                    )
                }
            }
        }
    }
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

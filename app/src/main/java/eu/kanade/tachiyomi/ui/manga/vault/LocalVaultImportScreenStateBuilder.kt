package eu.kanade.tachiyomi.ui.manga.vault

import androidx.compose.runtime.Immutable
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMetadata

class LocalVaultImportScreenStateBuilder {

    fun build(
        workflow: LocalVaultWorkflow,
        expectedSourceIdentity: String,
        vaultManga: List<VaultManga>,
        vaultChapters: List<VaultChapter>,
        hint: ImportTargetHint?,
        pendingTargetOverride: LocalVaultImportTargetSelection?,
        chapters: List<Chapter>,
        localChapterDuplicateKeys: Map<String, String>,
        isImportRunning: Boolean,
    ): LocalVaultImportState {
        val persistedTarget = hint
            ?.takeIf { it.sourceIdentity == null || it.sourceIdentity == expectedSourceIdentity }
            ?.let { targetHint -> vaultManga.firstOrNull { it.id == targetHint.vaultMangaId } }
        val effectiveTargetId = when (pendingTargetOverride) {
            null -> persistedTarget?.id
            is LocalVaultImportTargetSelection.CreateNew -> null
            is LocalVaultImportTargetSelection.Existing -> pendingTargetOverride.mangaId
        }
        val duplicateChapterSelectionIds = effectiveTargetId
            ?.let {
                findDuplicateChapterSelectionIds(
                    workflow = workflow,
                    targetMangaId = it,
                    vaultChapters = vaultChapters,
                    chapters = chapters,
                    localChapterDuplicateKeys = localChapterDuplicateKeys,
                )
            }
            .orEmpty()

        return LocalVaultImportState(
            targetState = when {
                persistedTarget != null -> LocalVaultImportTargetState.Linked(
                    mangaId = persistedTarget.id,
                    title = persistedTarget.metadata.title,
                )
                hint != null -> LocalVaultImportTargetState.Stale
                else -> LocalVaultImportTargetState.Unlinked
            },
            workflow = workflow,
            availableTargets = vaultManga,
            pendingTarget = pendingTargetOverride,
            targetMangaIdForDuplicates = effectiveTargetId,
            duplicateChapterSelectionIds = duplicateChapterSelectionIds,
            isImportRunning = isImportRunning,
        )
    }

    private fun findDuplicateChapterSelectionIds(
        workflow: LocalVaultWorkflow,
        targetMangaId: Long,
        vaultChapters: List<VaultChapter>,
        chapters: List<Chapter>,
        localChapterDuplicateKeys: Map<String, String>,
    ): Set<String> {
        val targetChapters = vaultChapters.filter { it.mangaId == targetMangaId }
        return when (workflow) {
            LocalVaultWorkflow.LibraryCapture -> findLibraryCaptureDuplicateIds(
                chapters = chapters,
                targetChapters = targetChapters,
            )
            LocalVaultWorkflow.LocalImport -> findLocalImportDuplicateIds(
                targetChapters = targetChapters,
                localChapterDuplicateKeys = localChapterDuplicateKeys,
            )
        }
    }

    private fun findLibraryCaptureDuplicateIds(
        chapters: List<Chapter>,
        targetChapters: List<VaultChapter>,
    ): Set<String> {
        val vaultDuplicateKeys = targetChapters
            .asSequence()
            .map { VaultMetadata.normalizeTitle(it.title) }
            .filter { it.isNotBlank() }
            .toSet()
        if (vaultDuplicateKeys.isEmpty()) return emptySet()

        return chapters
            .filter { VaultMetadata.normalizeTitle(it.name) in vaultDuplicateKeys }
            .map { it.url }
            .toSet()
    }

    private fun findLocalImportDuplicateIds(
        targetChapters: List<VaultChapter>,
        localChapterDuplicateKeys: Map<String, String>,
    ): Set<String> {
        val vaultDuplicateKeys = targetChapters
            .asSequence()
            .map { localVaultImportDuplicateFileKey(it.content.path.substringAfterLast('/')) }
            .filter { it.isNotBlank() }
            .toSet()
        if (vaultDuplicateKeys.isEmpty()) return emptySet()

        return localChapterDuplicateKeys
            .filterValues { it in vaultDuplicateKeys }
            .keys
    }
}

internal fun localVaultImportSourceIdentity(
    workflow: LocalVaultWorkflow,
    sourceId: Long,
    mangaUrl: String,
): String {
    return when (workflow) {
        LocalVaultWorkflow.LocalImport -> mangaUrl
        LocalVaultWorkflow.LibraryCapture -> "$sourceId:$mangaUrl"
    }
}

internal fun localVaultImportDuplicateFileKey(fileName: String): String {
    val trimmed = fileName.trim()
    return trimmed
        .substringBeforeLast('.', missingDelimiterValue = trimmed)
        .lowercase()
}

@Immutable
data class LocalVaultImportState(
    val targetState: LocalVaultImportTargetState,
    val workflow: LocalVaultWorkflow = LocalVaultWorkflow.LocalImport,
    val availableTargets: List<VaultManga> = emptyList(),
    val pendingTarget: LocalVaultImportTargetSelection? = null,
    val targetMangaIdForDuplicates: Long? = null,
    val duplicateChapterSelectionIds: Set<String> = emptySet(),
    val isImportRunning: Boolean = false,
)

enum class LocalVaultWorkflow {
    LocalImport,
    LibraryCapture,
}

internal data class LocalVaultImportInputs(
    val vaultManga: List<VaultManga>,
    val vaultChapters: List<VaultChapter>,
    val hint: ImportTargetHint?,
)

@Immutable
sealed interface LocalVaultImportTargetState {
    data object SetupContentVault : LocalVaultImportTargetState
    data object Unlinked : LocalVaultImportTargetState
    data object Stale : LocalVaultImportTargetState
    data class Linked(
        val mangaId: Long,
        val title: String,
    ) : LocalVaultImportTargetState
}

@Immutable
sealed interface LocalVaultImportTargetSelection {
    data class CreateNew(val title: String) : LocalVaultImportTargetSelection
    data class Existing(val mangaId: Long) : LocalVaultImportTargetSelection
}

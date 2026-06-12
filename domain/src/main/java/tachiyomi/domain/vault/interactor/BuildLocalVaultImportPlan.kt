package tachiyomi.domain.vault.interactor

import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.LocalVaultImportChapter
import tachiyomi.domain.vault.model.LocalVaultImportChapterPlan
import tachiyomi.domain.vault.model.LocalVaultImportDuplicateState
import tachiyomi.domain.vault.model.LocalVaultImportManga
import tachiyomi.domain.vault.model.LocalVaultImportPlan
import tachiyomi.domain.vault.model.LocalVaultImportTarget
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMetadata

class BuildLocalVaultImportPlan {

    fun build(
        localManga: LocalVaultImportManga,
        localChapters: List<LocalVaultImportChapter>,
        vaultManga: List<VaultManga>,
        existingChaptersByMangaId: Map<Long, List<VaultChapter>>,
        hint: ImportTargetHint?,
    ): LocalVaultImportPlan {
        val target = resolveTarget(localManga, vaultManga, hint)
        val existingChapters = when (target) {
            is LocalVaultImportTarget.Existing -> existingChaptersByMangaId[target.manga.id].orEmpty()
            LocalVaultImportTarget.CreateNew,
            is LocalVaultImportTarget.Choose,
            -> emptyList()
        }

        return LocalVaultImportPlan(
            target = target,
            chapters = buildChapterPlans(localChapters, existingChapters),
        )
    }

    fun buildForTarget(
        target: LocalVaultImportTarget,
        localChapters: List<LocalVaultImportChapter>,
        existingChapters: List<VaultChapter>,
    ): LocalVaultImportPlan {
        return LocalVaultImportPlan(
            target = target,
            chapters = buildChapterPlans(localChapters, existingChapters),
        )
    }

    private fun buildChapterPlans(
        localChapters: List<LocalVaultImportChapter>,
        existingChapters: List<VaultChapter>,
    ): List<LocalVaultImportChapterPlan> {
        return localChapters.map { chapter ->
            val duplicateState = chapter.duplicateState(existingChapters)
            LocalVaultImportChapterPlan(
                chapter = chapter,
                duplicateState = duplicateState,
                selectedByDefault = duplicateState == LocalVaultImportDuplicateState.NONE,
            )
        }
    }

    private fun resolveTarget(
        localManga: LocalVaultImportManga,
        vaultManga: List<VaultManga>,
        hint: ImportTargetHint?,
    ): LocalVaultImportTarget {
        hint
            ?.takeIf { it.sourceIdentity == null || it.sourceIdentity == localManga.localMangaIdentity }
            ?.let { targetHint -> vaultManga.firstOrNull { it.id == targetHint.vaultMangaId } }
            ?.let { return LocalVaultImportTarget.Existing(it, LocalVaultImportTarget.Reason.IMPORT_TARGET_HINT) }

        val normalizedTitle = VaultMetadata.normalizeTitle(localManga.title)
        val matches = vaultManga.filter { it.metadata.normalizedTitle == normalizedTitle }
        return when (matches.size) {
            0 -> LocalVaultImportTarget.CreateNew
            1 -> LocalVaultImportTarget.Existing(matches.single(), LocalVaultImportTarget.Reason.EXACT_TITLE_MATCH)
            else -> LocalVaultImportTarget.Choose(matches)
        }
    }

    private fun LocalVaultImportChapter.duplicateState(
        existingChapters: List<VaultChapter>,
    ): LocalVaultImportDuplicateState {
        val localFileKey = sourceFileName.duplicateFileKey()
        val possibleDuplicate = localFileKey.isNotBlank() &&
            existingChapters.any { it.content.path.substringAfterLast('/').duplicateFileKey() == localFileKey }
        return if (possibleDuplicate) {
            LocalVaultImportDuplicateState.POSSIBLE
        } else {
            LocalVaultImportDuplicateState.NONE
        }
    }

    private fun String.duplicateFileKey(): String {
        val trimmed = trim()
        return trimmed
            .substringBeforeLast('.', missingDelimiterValue = trimmed)
            .lowercase()
    }
}

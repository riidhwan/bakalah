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

class BuildLocalVaultImportPlan {
    private val sharedPlanner = BuildAddToVaultPlan()

    fun build(
        localManga: LocalVaultImportManga,
        localChapters: List<LocalVaultImportChapter>,
        vaultManga: List<VaultManga>,
        existingChaptersByMangaId: Map<Long, List<VaultChapter>>,
        hint: ImportTargetHint?,
    ): LocalVaultImportPlan {
        return sharedPlanner.build(
            sourceTitle = localManga.title,
            sourceIdentity = localManga.localMangaIdentity,
            chapters = localChapters,
            vaultManga = vaultManga,
            existingChaptersByMangaId = existingChaptersByMangaId,
            hint = hint,
            isDuplicate = { chapter, existingChapters -> chapter.isDuplicateOf(existingChapters) },
        ).toLocalVaultImportPlan()
    }

    fun buildForTarget(
        target: LocalVaultImportTarget,
        localChapters: List<LocalVaultImportChapter>,
        existingChapters: List<VaultChapter>,
    ): LocalVaultImportPlan {
        return sharedPlanner.buildForTarget(
            target = target.toAddToVaultTarget(),
            chapters = localChapters,
            existingChapters = existingChapters,
            isDuplicate = { chapter, targetChapters -> chapter.isDuplicateOf(targetChapters) },
        ).toLocalVaultImportPlan()
    }

    private fun AddToVaultPlan<LocalVaultImportChapter>.toLocalVaultImportPlan(): LocalVaultImportPlan {
        return LocalVaultImportPlan(
            target = target.toLocalVaultImportTarget(),
            chapters = chapters.map { chapterPlan ->
                LocalVaultImportChapterPlan(
                    chapter = chapterPlan.chapter,
                    duplicateState = if (chapterPlan.duplicate) {
                        LocalVaultImportDuplicateState.POSSIBLE
                    } else {
                        LocalVaultImportDuplicateState.NONE
                    },
                    selectedByDefault = chapterPlan.selectedByDefault,
                )
            },
        )
    }

    private fun AddToVaultTarget.toLocalVaultImportTarget(): LocalVaultImportTarget {
        return when (this) {
            is AddToVaultTarget.Existing -> LocalVaultImportTarget.Existing(
                manga = manga,
                reason = reason.toLocalVaultImportReason(),
            )
            AddToVaultTarget.CreateNew -> LocalVaultImportTarget.CreateNew
            is AddToVaultTarget.Choose -> LocalVaultImportTarget.Choose(candidates)
        }
    }

    private fun LocalVaultImportTarget.toAddToVaultTarget(): AddToVaultTarget {
        return when (this) {
            is LocalVaultImportTarget.Existing -> AddToVaultTarget.Existing(
                manga = manga,
                reason = reason.toAddToVaultReason(),
            )
            LocalVaultImportTarget.CreateNew -> AddToVaultTarget.CreateNew
            is LocalVaultImportTarget.Choose -> AddToVaultTarget.Choose(candidates)
        }
    }

    private fun AddToVaultTarget.Reason.toLocalVaultImportReason(): LocalVaultImportTarget.Reason {
        return when (this) {
            AddToVaultTarget.Reason.IMPORT_TARGET_HINT -> LocalVaultImportTarget.Reason.IMPORT_TARGET_HINT
            AddToVaultTarget.Reason.EXACT_TITLE_MATCH -> LocalVaultImportTarget.Reason.EXACT_TITLE_MATCH
            AddToVaultTarget.Reason.USER_SELECTED -> LocalVaultImportTarget.Reason.USER_SELECTED
        }
    }

    private fun LocalVaultImportTarget.Reason.toAddToVaultReason(): AddToVaultTarget.Reason {
        return when (this) {
            LocalVaultImportTarget.Reason.IMPORT_TARGET_HINT -> AddToVaultTarget.Reason.IMPORT_TARGET_HINT
            LocalVaultImportTarget.Reason.EXACT_TITLE_MATCH -> AddToVaultTarget.Reason.EXACT_TITLE_MATCH
            LocalVaultImportTarget.Reason.USER_SELECTED -> AddToVaultTarget.Reason.USER_SELECTED
        }
    }

    private fun LocalVaultImportChapter.isDuplicateOf(
        existingChapters: List<VaultChapter>,
    ): Boolean {
        val localFileKey = sourceFileName.duplicateFileKey()
        return localFileKey.isNotBlank() &&
            existingChapters.any { it.content.path.substringAfterLast('/').duplicateFileKey() == localFileKey }
    }

    private fun String.duplicateFileKey(): String {
        val trimmed = trim()
        return trimmed
            .substringBeforeLast('.', missingDelimiterValue = trimmed)
            .lowercase()
    }
}

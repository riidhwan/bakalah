package tachiyomi.domain.vault.interactor

import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.LibraryVaultCaptureChapter
import tachiyomi.domain.vault.model.LibraryVaultCaptureChapterPlan
import tachiyomi.domain.vault.model.LibraryVaultCaptureDuplicateState
import tachiyomi.domain.vault.model.LibraryVaultCaptureManga
import tachiyomi.domain.vault.model.LibraryVaultCapturePlan
import tachiyomi.domain.vault.model.LibraryVaultCaptureTarget
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMetadata

class BuildLibraryVaultCapturePlan {
    private val sharedPlanner = BuildAddToVaultPlan()

    fun build(
        libraryManga: LibraryVaultCaptureManga,
        libraryChapters: List<LibraryVaultCaptureChapter>,
        vaultManga: List<VaultManga>,
        existingChaptersByMangaId: Map<Long, List<VaultChapter>>,
        hint: ImportTargetHint?,
    ): LibraryVaultCapturePlan {
        return sharedPlanner.build(
            sourceTitle = libraryManga.title,
            sourceIdentity = libraryManga.sourceIdentity,
            chapters = libraryChapters,
            vaultManga = vaultManga,
            existingChaptersByMangaId = existingChaptersByMangaId,
            hint = hint,
            isDuplicate = { chapter, existingChapters -> chapter.isDuplicateOf(existingChapters) },
        ).toLibraryVaultCapturePlan()
    }

    fun buildForTarget(
        target: LibraryVaultCaptureTarget,
        libraryChapters: List<LibraryVaultCaptureChapter>,
        existingChapters: List<VaultChapter>,
    ): LibraryVaultCapturePlan {
        return sharedPlanner.buildForTarget(
            target = target.toAddToVaultTarget(),
            chapters = libraryChapters,
            existingChapters = existingChapters,
            isDuplicate = { chapter, targetChapters -> chapter.isDuplicateOf(targetChapters) },
        ).toLibraryVaultCapturePlan()
    }

    private fun AddToVaultPlan<LibraryVaultCaptureChapter>.toLibraryVaultCapturePlan(): LibraryVaultCapturePlan {
        return LibraryVaultCapturePlan(
            target = target.toLibraryVaultCaptureTarget(),
            chapters = chapters.map { chapterPlan ->
                LibraryVaultCaptureChapterPlan(
                    chapter = chapterPlan.chapter,
                    duplicateState = if (chapterPlan.duplicate) {
                        LibraryVaultCaptureDuplicateState.POSSIBLE
                    } else {
                        LibraryVaultCaptureDuplicateState.NONE
                    },
                    selectedByDefault = chapterPlan.selectedByDefault,
                )
            },
        )
    }

    private fun AddToVaultTarget.toLibraryVaultCaptureTarget(): LibraryVaultCaptureTarget {
        return when (this) {
            is AddToVaultTarget.Existing -> LibraryVaultCaptureTarget.Existing(
                manga = manga,
                reason = reason.toLibraryVaultCaptureReason(),
            )
            AddToVaultTarget.CreateNew -> LibraryVaultCaptureTarget.CreateNew
            is AddToVaultTarget.Choose -> LibraryVaultCaptureTarget.Choose(candidates)
        }
    }

    private fun LibraryVaultCaptureTarget.toAddToVaultTarget(): AddToVaultTarget {
        return when (this) {
            is LibraryVaultCaptureTarget.Existing -> AddToVaultTarget.Existing(
                manga = manga,
                reason = reason.toAddToVaultReason(),
            )
            LibraryVaultCaptureTarget.CreateNew -> AddToVaultTarget.CreateNew
            is LibraryVaultCaptureTarget.Choose -> AddToVaultTarget.Choose(candidates)
        }
    }

    private fun AddToVaultTarget.Reason.toLibraryVaultCaptureReason(): LibraryVaultCaptureTarget.Reason {
        return when (this) {
            AddToVaultTarget.Reason.IMPORT_TARGET_HINT -> LibraryVaultCaptureTarget.Reason.IMPORT_TARGET_HINT
            AddToVaultTarget.Reason.EXACT_TITLE_MATCH -> LibraryVaultCaptureTarget.Reason.EXACT_TITLE_MATCH
            AddToVaultTarget.Reason.USER_SELECTED -> LibraryVaultCaptureTarget.Reason.USER_SELECTED
        }
    }

    private fun LibraryVaultCaptureTarget.Reason.toAddToVaultReason(): AddToVaultTarget.Reason {
        return when (this) {
            LibraryVaultCaptureTarget.Reason.IMPORT_TARGET_HINT -> AddToVaultTarget.Reason.IMPORT_TARGET_HINT
            LibraryVaultCaptureTarget.Reason.EXACT_TITLE_MATCH -> AddToVaultTarget.Reason.EXACT_TITLE_MATCH
            LibraryVaultCaptureTarget.Reason.USER_SELECTED -> AddToVaultTarget.Reason.USER_SELECTED
        }
    }

    private fun LibraryVaultCaptureChapter.isDuplicateOf(existingChapters: List<VaultChapter>): Boolean {
        val titleKey = title.duplicateTitleKey()
        return titleKey.isNotBlank() && existingChapters.any { it.title.duplicateTitleKey() == titleKey }
    }
}

fun String.duplicateTitleKey(): String = VaultMetadata.normalizeTitle(this)

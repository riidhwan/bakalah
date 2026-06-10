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

    fun build(
        libraryManga: LibraryVaultCaptureManga,
        libraryChapters: List<LibraryVaultCaptureChapter>,
        vaultManga: List<VaultManga>,
        existingChaptersByMangaId: Map<Long, List<VaultChapter>>,
        hint: ImportTargetHint?,
    ): LibraryVaultCapturePlan {
        val target = resolveTarget(libraryManga, vaultManga, hint)
        val existingChapters = when (target) {
            is LibraryVaultCaptureTarget.Existing -> existingChaptersByMangaId[target.manga.id].orEmpty()
            LibraryVaultCaptureTarget.CreateNew,
            is LibraryVaultCaptureTarget.Choose,
            -> emptyList()
        }
        return LibraryVaultCapturePlan(
            target = target,
            chapters = buildChapterPlans(libraryChapters, existingChapters),
        )
    }

    fun buildForTarget(
        target: LibraryVaultCaptureTarget,
        libraryChapters: List<LibraryVaultCaptureChapter>,
        existingChapters: List<VaultChapter>,
    ): LibraryVaultCapturePlan {
        return LibraryVaultCapturePlan(
            target = target,
            chapters = buildChapterPlans(libraryChapters, existingChapters),
        )
    }

    private fun buildChapterPlans(
        libraryChapters: List<LibraryVaultCaptureChapter>,
        existingChapters: List<VaultChapter>,
    ): List<LibraryVaultCaptureChapterPlan> {
        val existingTitleKeys = existingChapters
            .map { it.title.duplicateTitleKey() }
            .filter { it.isNotBlank() }
            .toSet()
        return libraryChapters.map { chapter ->
            val duplicateState = if (chapter.title.duplicateTitleKey() in existingTitleKeys) {
                LibraryVaultCaptureDuplicateState.POSSIBLE
            } else {
                LibraryVaultCaptureDuplicateState.NONE
            }
            LibraryVaultCaptureChapterPlan(
                chapter = chapter,
                duplicateState = duplicateState,
                selectedByDefault = duplicateState == LibraryVaultCaptureDuplicateState.NONE,
            )
        }
    }

    private fun resolveTarget(
        libraryManga: LibraryVaultCaptureManga,
        vaultManga: List<VaultManga>,
        hint: ImportTargetHint?,
    ): LibraryVaultCaptureTarget {
        hint
            ?.takeIf { it.sourceIdentity == null || it.sourceIdentity == libraryManga.sourceIdentity }
            ?.let { targetHint -> vaultManga.firstOrNull { it.id == targetHint.vaultMangaId } }
            ?.let { return LibraryVaultCaptureTarget.Existing(it, LibraryVaultCaptureTarget.Reason.IMPORT_TARGET_HINT) }

        val normalizedTitle = VaultMetadata.normalizeTitle(libraryManga.title)
        val matches = vaultManga.filter { it.metadata.normalizedTitle == normalizedTitle }
        return when (matches.size) {
            0 -> LibraryVaultCaptureTarget.CreateNew
            1 -> LibraryVaultCaptureTarget.Existing(
                matches.single(),
                LibraryVaultCaptureTarget.Reason.EXACT_TITLE_MATCH,
            )
            else -> LibraryVaultCaptureTarget.Choose(matches)
        }
    }
}

fun String.duplicateTitleKey(): String = VaultMetadata.normalizeTitle(this)

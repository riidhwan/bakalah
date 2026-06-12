package tachiyomi.domain.vault.interactor

import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMetadata

internal class BuildAddToVaultPlan {

    fun <Chapter> build(
        sourceTitle: String,
        sourceIdentity: String?,
        chapters: List<Chapter>,
        vaultManga: List<VaultManga>,
        existingChaptersByMangaId: Map<Long, List<VaultChapter>>,
        hint: ImportTargetHint?,
        isDuplicate: (Chapter, List<VaultChapter>) -> Boolean,
    ): AddToVaultPlan<Chapter> {
        val target = resolveTarget(
            sourceTitle = sourceTitle,
            sourceIdentity = sourceIdentity,
            vaultManga = vaultManga,
            hint = hint,
        )
        val existingChapters = when (target) {
            is AddToVaultTarget.Existing -> existingChaptersByMangaId[target.manga.id].orEmpty()
            AddToVaultTarget.CreateNew,
            is AddToVaultTarget.Choose,
            -> emptyList()
        }
        return buildForTarget(
            target = target,
            chapters = chapters,
            existingChapters = existingChapters,
            isDuplicate = isDuplicate,
        )
    }

    fun <Chapter> buildForTarget(
        target: AddToVaultTarget,
        chapters: List<Chapter>,
        existingChapters: List<VaultChapter>,
        isDuplicate: (Chapter, List<VaultChapter>) -> Boolean,
    ): AddToVaultPlan<Chapter> {
        return AddToVaultPlan(
            target = target,
            chapters = chapters.map { chapter ->
                val duplicate = isDuplicate(chapter, existingChapters)
                AddToVaultChapterPlan(
                    chapter = chapter,
                    duplicate = duplicate,
                    selectedByDefault = !duplicate,
                )
            },
        )
    }

    private fun resolveTarget(
        sourceTitle: String,
        sourceIdentity: String?,
        vaultManga: List<VaultManga>,
        hint: ImportTargetHint?,
    ): AddToVaultTarget {
        hint
            ?.takeIf { it.sourceIdentity == null || it.sourceIdentity == sourceIdentity }
            ?.let { targetHint -> vaultManga.firstOrNull { it.id == targetHint.vaultMangaId } }
            ?.let { return AddToVaultTarget.Existing(it, AddToVaultTarget.Reason.IMPORT_TARGET_HINT) }

        val normalizedTitle = VaultMetadata.normalizeTitle(sourceTitle)
        val matches = vaultManga.filter { it.metadata.normalizedTitle == normalizedTitle }
        return when (matches.size) {
            0 -> AddToVaultTarget.CreateNew
            1 -> AddToVaultTarget.Existing(matches.single(), AddToVaultTarget.Reason.EXACT_TITLE_MATCH)
            else -> AddToVaultTarget.Choose(matches)
        }
    }
}

internal data class AddToVaultPlan<Chapter>(
    val target: AddToVaultTarget,
    val chapters: List<AddToVaultChapterPlan<Chapter>>,
)

internal data class AddToVaultChapterPlan<Chapter>(
    val chapter: Chapter,
    val duplicate: Boolean,
    val selectedByDefault: Boolean,
)

internal sealed interface AddToVaultTarget {
    data class Existing(
        val manga: VaultManga,
        val reason: Reason,
    ) : AddToVaultTarget

    data object CreateNew : AddToVaultTarget

    data class Choose(
        val candidates: List<VaultManga>,
    ) : AddToVaultTarget

    enum class Reason {
        IMPORT_TARGET_HINT,
        EXACT_TITLE_MATCH,
        USER_SELECTED,
    }
}

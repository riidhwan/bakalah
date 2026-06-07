package tachiyomi.domain.vault.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.LocalVaultImportDuplicateState
import tachiyomi.domain.vault.model.LocalVaultImportManga
import tachiyomi.domain.vault.model.LocalVaultImportTarget
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaCollectionState
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRevision

@Execution(ExecutionMode.CONCURRENT)
class BuildLocalVaultImportPlanTest {

    private val builder = BuildLocalVaultImportPlan()

    @Test
    fun `import target hint wins before title matching`() {
        val hinted = vaultManga(id = 2, title = "Different")
        val titleMatch = vaultManga(id = 3, title = "One Piece")

        val plan = builder.build(
            localManga = localManga("One Piece"),
            localChapters = emptyList(),
            vaultManga = listOf(hinted, titleMatch),
            existingChaptersByMangaId = emptyMap(),
            hint = ImportTargetHint(
                localMangaId = 10,
                localMangaIdentity = "local/one-piece",
                vaultMangaId = hinted.id,
                updatedAt = 1,
            ),
        )

        plan.target shouldBe LocalVaultImportTarget.Existing(
            manga = hinted,
            reason = LocalVaultImportTarget.Reason.IMPORT_TARGET_HINT,
        )
    }

    @Test
    fun `single normalized title match imports into existing manga`() {
        val match = vaultManga(id = 2, title = "One Piece")

        val plan = builder.build(
            localManga = localManga("  one   PIECE "),
            localChapters = emptyList(),
            vaultManga = listOf(match),
            existingChaptersByMangaId = emptyMap(),
            hint = null,
        )

        plan.target shouldBe LocalVaultImportTarget.Existing(
            manga = match,
            reason = LocalVaultImportTarget.Reason.EXACT_TITLE_MATCH,
        )
    }

    @Test
    fun `multiple normalized title matches require explicit choice`() {
        val first = vaultManga(id = 2, title = "One Piece")
        val second = vaultManga(id = 3, title = "one  piece")

        val plan = builder.build(
            localManga = localManga("One Piece"),
            localChapters = emptyList(),
            vaultManga = listOf(first, second),
            existingChaptersByMangaId = emptyMap(),
            hint = null,
        )

        plan.target shouldBe LocalVaultImportTarget.Choose(listOf(first, second))
    }

    @Test
    fun `exact duplicate chapters are skipped by default and possible duplicates are flagged`() {
        val target = vaultManga(id = 2, title = "One Piece")
        val plan = builder.build(
            localManga = localManga("One Piece"),
            localChapters = listOf(
                localChapter(selectionId = "same-checksum", title = "Chapter 1", chapterNumber = 1.0, checksum = "a"),
                localChapter(selectionId = "same-number", title = "Different", chapterNumber = 2.0, checksum = "b"),
                localChapter(selectionId = "new", title = "Chapter 3", chapterNumber = 3.0, checksum = "c"),
            ),
            vaultManga = listOf(target),
            existingChaptersByMangaId = mapOf(
                target.id to listOf(
                    vaultChapter(title = "Chapter 1", chapterNumber = 1.0, checksum = "a"),
                    vaultChapter(title = "Chapter 2", chapterNumber = 2.0, checksum = "other"),
                ),
            ),
            hint = null,
        )

        plan.chapters.map { it.duplicateState } shouldBe listOf(
            LocalVaultImportDuplicateState.EXACT,
            LocalVaultImportDuplicateState.POSSIBLE,
            LocalVaultImportDuplicateState.NONE,
        )
        plan.chapters.map { it.selectedByDefault } shouldBe listOf(false, true, true)
    }

    @Test
    fun `target-specific plan uses selected target chapters for duplicate state`() {
        val selectedTarget = vaultManga(id = 3, title = "One Piece Color")
        val plan = builder.buildForTarget(
            target = LocalVaultImportTarget.Existing(
                manga = selectedTarget,
                reason = LocalVaultImportTarget.Reason.USER_SELECTED,
            ),
            localChapters = listOf(
                localChapter(selectionId = "new-for-target", title = "Chapter 1", chapterNumber = 1.0, checksum = "a"),
                localChapter(selectionId = "same-for-target", title = "Chapter 2", chapterNumber = 2.0, checksum = "b"),
            ),
            existingChapters = listOf(
                vaultChapter(title = "Chapter 2", chapterNumber = 2.0, checksum = "b"),
            ),
        )

        plan.target shouldBe LocalVaultImportTarget.Existing(
            manga = selectedTarget,
            reason = LocalVaultImportTarget.Reason.USER_SELECTED,
        )
        plan.chapters.map { it.duplicateState } shouldBe listOf(
            LocalVaultImportDuplicateState.NONE,
            LocalVaultImportDuplicateState.EXACT,
        )
        plan.chapters.map { it.selectedByDefault } shouldBe listOf(true, false)
    }

    private fun localManga(title: String) = LocalVaultImportManga(
        localMangaId = 10,
        localMangaIdentity = "local/$title",
        title = title,
        metadata = VaultMetadata(
            title = title,
            author = null,
            artist = null,
            description = null,
            status = VaultMangaStatus.UNKNOWN,
        ),
    )

    private fun localChapter(
        selectionId: String,
        title: String,
        chapterNumber: Double,
        checksum: String,
    ) = tachiyomi.domain.vault.model.LocalVaultImportChapter(
        selectionId = selectionId,
        title = title,
        chapterNumber = chapterNumber,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = 0,
        contentFormat = VaultChapterContentFormat.ARCHIVE,
        sizeBytes = 10,
        checksumSha256 = checksum,
        dateUpload = 20,
    )

    private fun vaultManga(id: Long, title: String) = VaultManga(
        id = id,
        vaultId = 1,
        identity = VaultIdentity("manga-$id"),
        metadata = VaultMetadata(
            title = title,
            author = null,
            artist = null,
            description = null,
            status = VaultMangaStatus.UNKNOWN,
        ),
        sortKey = VaultMetadata.normalizeTitle(title),
        collectionState = VaultMangaCollectionState.ACTIVE,
        trashedAt = null,
        coverId = null,
        revision = VaultRevision("rev-$id", 1),
        createdAt = 1,
        updatedAt = 1,
    )

    private fun vaultChapter(
        title: String,
        chapterNumber: Double,
        checksum: String,
    ) = VaultChapter(
        id = -1,
        mangaId = 2,
        identity = VaultIdentity("chapter-$checksum"),
        title = title,
        chapterNumber = chapterNumber,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = 0,
        content = VaultChapterContent(
            path = "content/$checksum.cbz",
            format = VaultChapterContentFormat.ARCHIVE,
            sizeBytes = 10,
            checksumSha256 = checksum,
        ),
        revision = VaultRevision("chapter-rev", 1),
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )
}

package tachiyomi.domain.vault.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.LibraryVaultCaptureChapter
import tachiyomi.domain.vault.model.LibraryVaultCaptureDuplicateState
import tachiyomi.domain.vault.model.LibraryVaultCaptureManga
import tachiyomi.domain.vault.model.LibraryVaultCaptureTarget
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRevision

@Execution(ExecutionMode.CONCURRENT)
class BuildLibraryVaultCapturePlanTest {

    private val builder = BuildLibraryVaultCapturePlan()

    @Test
    fun `source identity matching hint wins before title matching`() {
        val hinted = vaultManga(id = 2, title = "Different")
        val titleMatch = vaultManga(id = 3, title = "One Piece")

        val plan = builder.build(
            libraryManga = libraryManga("One Piece"),
            libraryChapters = emptyList(),
            vaultManga = listOf(hinted, titleMatch),
            existingChaptersByMangaId = emptyMap(),
            hint = ImportTargetHint(
                localMangaId = 10,
                localMangaIdentity = "one-piece",
                sourceIdentity = "1:one-piece",
                vaultMangaId = hinted.id,
                updatedAt = 1,
            ),
        )

        plan.target shouldBe LibraryVaultCaptureTarget.Existing(
            manga = hinted,
            reason = LibraryVaultCaptureTarget.Reason.IMPORT_TARGET_HINT,
        )
    }

    @Test
    fun `source identity mismatch makes hint stale and falls back to title matching`() {
        val hinted = vaultManga(id = 2, title = "Different")
        val titleMatch = vaultManga(id = 3, title = "One Piece")

        val plan = builder.build(
            libraryManga = libraryManga("One Piece"),
            libraryChapters = emptyList(),
            vaultManga = listOf(hinted, titleMatch),
            existingChaptersByMangaId = emptyMap(),
            hint = ImportTargetHint(
                localMangaId = 10,
                localMangaIdentity = "old-url",
                sourceIdentity = "1:old-url",
                vaultMangaId = hinted.id,
                updatedAt = 1,
            ),
        )

        plan.target shouldBe LibraryVaultCaptureTarget.Existing(
            manga = titleMatch,
            reason = LibraryVaultCaptureTarget.Reason.EXACT_TITLE_MATCH,
        )
    }

    @Test
    fun `normalized title duplicate chapters are flagged and deselected by default`() {
        val target = vaultManga(id = 2, title = "One Piece")
        val plan = builder.build(
            libraryManga = libraryManga("One Piece"),
            libraryChapters = listOf(
                libraryChapter(title = " Chapter   1 "),
                libraryChapter(title = "Chapter 2"),
            ),
            vaultManga = listOf(target),
            existingChaptersByMangaId = mapOf(
                target.id to listOf(
                    vaultChapter(title = "chapter 1"),
                ),
            ),
            hint = null,
        )

        plan.chapters.map { it.duplicateState } shouldBe listOf(
            LibraryVaultCaptureDuplicateState.POSSIBLE,
            LibraryVaultCaptureDuplicateState.NONE,
        )
        plan.chapters.map { it.selectedByDefault } shouldBe listOf(false, true)
    }

    private fun libraryManga(title: String) = LibraryVaultCaptureManga(
        mangaId = 10,
        sourceId = 1,
        sourceIdentity = "1:one-piece",
        title = title,
        metadata = VaultMetadata(
            title = title,
            author = null,
            artist = null,
            description = null,
            status = VaultMangaStatus.UNKNOWN,
        ),
    )

    private fun libraryChapter(title: String) = LibraryVaultCaptureChapter(
        selectionId = "chapter/$title",
        title = title,
        chapterNumber = 1.0,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = 0,
        dateUpload = 1,
        sourceChapterUrl = "chapter/$title",
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
        coverId = null,
        revision = VaultRevision("rev-$id", 1),
        createdAt = 1,
        updatedAt = 1,
    )

    private fun vaultChapter(title: String) = VaultChapter(
        id = -1,
        mangaId = 2,
        identity = VaultIdentity("chapter-$title"),
        title = title,
        chapterNumber = 1.0,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = 0,
        content = VaultChapterContent(
            path = "content/manga/chapter/$title.cbz",
            format = VaultChapterContentFormat.CBZ,
            sizeBytes = 10,
            checksumSha256 = title,
        ),
        revision = VaultRevision("chapter-rev", 1),
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )
}

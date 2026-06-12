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
                localMangaIdentity = "local/One Piece",
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
    fun `source identity mismatch makes import target hint stale and falls back to title matching`() {
        val hinted = vaultManga(id = 2, title = "Different")
        val titleMatch = vaultManga(id = 3, title = "One Piece")

        val plan = builder.build(
            localManga = localManga("One Piece"),
            localChapters = emptyList(),
            vaultManga = listOf(hinted, titleMatch),
            existingChaptersByMangaId = emptyMap(),
            hint = ImportTargetHint(
                localMangaId = 10,
                localMangaIdentity = "local/old",
                sourceIdentity = "local/old",
                vaultMangaId = hinted.id,
                updatedAt = 1,
            ),
        )

        plan.target shouldBe LocalVaultImportTarget.Existing(
            manga = titleMatch,
            reason = LocalVaultImportTarget.Reason.EXACT_TITLE_MATCH,
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
    fun `stale import target hint falls back to exact title matching`() {
        val match = vaultManga(id = 2, title = "One Piece")

        val plan = builder.build(
            localManga = localManga("One Piece"),
            localChapters = emptyList(),
            vaultManga = listOf(match),
            existingChaptersByMangaId = emptyMap(),
            hint = ImportTargetHint(
                localMangaId = 10,
                localMangaIdentity = "local/one-piece",
                vaultMangaId = 99,
                updatedAt = 1,
            ),
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
    fun `filename duplicate chapters are flagged and deselected by default`() {
        val target = vaultManga(id = 2, title = "One Piece")
        val plan = builder.build(
            localManga = localManga("One Piece"),
            localChapters = listOf(
                localChapter(
                    fileName = "Chapter 1.cbz",
                    title = "Different Title",
                    chapterNumber = 10.0,
                    checksum = "new",
                ),
                localChapter(fileName = "Chapter 2.cbz", title = "Chapter 2", chapterNumber = 2.0, checksum = "same"),
                localChapter(fileName = "Chapter 3.cbz", title = "Chapter 3", chapterNumber = 3.0, checksum = "c"),
            ),
            vaultManga = listOf(target),
            existingChaptersByMangaId = mapOf(
                target.id to listOf(
                    vaultChapter(
                        path = "content/manga/chapter/Chapter 1.cbz",
                        title = "Chapter 1",
                        chapterNumber = 1.0,
                        checksum = "a",
                    ),
                    vaultChapter(
                        path = "content/manga/chapter/Different.cbz",
                        title = "Chapter 2",
                        chapterNumber = 2.0,
                        checksum = "same",
                    ),
                ),
            ),
            hint = null,
        )

        plan.chapters.map { it.duplicateState } shouldBe listOf(
            LocalVaultImportDuplicateState.POSSIBLE,
            LocalVaultImportDuplicateState.NONE,
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
                localChapter(fileName = "Chapter 1.cbz", title = "Chapter 1", chapterNumber = 1.0, checksum = "a"),
                localChapter(fileName = "Chapter 2.cbz", title = "Chapter 2", chapterNumber = 2.0, checksum = "b"),
            ),
            existingChapters = listOf(
                vaultChapter(
                    path = "content/manga/chapter/Chapter 2.cbz",
                    title = "Different",
                    chapterNumber = 20.0,
                    checksum = "different",
                ),
            ),
        )

        plan.target shouldBe LocalVaultImportTarget.Existing(
            manga = selectedTarget,
            reason = LocalVaultImportTarget.Reason.USER_SELECTED,
        )
        plan.chapters.map { it.duplicateState } shouldBe listOf(
            LocalVaultImportDuplicateState.NONE,
            LocalVaultImportDuplicateState.POSSIBLE,
        )
        plan.chapters.map { it.selectedByDefault } shouldBe listOf(true, false)
    }

    @Test
    fun `matching extension is not required for filename duplicates`() {
        val target = vaultManga(id = 2, title = "One Piece")
        val plan = builder.build(
            localManga = localManga("One Piece"),
            localChapters = listOf(
                localChapter(fileName = "Special", title = "Special", chapterNumber = -1.0, checksum = "a"),
            ),
            vaultManga = listOf(target),
            existingChaptersByMangaId = mapOf(
                target.id to listOf(
                    vaultChapter(
                        path = "content/manga/chapter/special.cbz",
                        title = "Different Special",
                        chapterNumber = -1.0,
                        checksum = "b",
                    ),
                ),
            ),
            hint = null,
        )

        plan.chapters.single().duplicateState shouldBe LocalVaultImportDuplicateState.POSSIBLE
        plan.chapters.single().selectedByDefault shouldBe false
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
        fileName: String,
        title: String,
        chapterNumber: Double,
        checksum: String,
    ) = tachiyomi.domain.vault.model.LocalVaultImportChapter(
        selectionId = "local/$fileName",
        sourceFileName = fileName,
        title = title,
        chapterNumber = chapterNumber,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = 0,
        contentFormat = VaultChapterContentFormat.CBZ,
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
        coverId = null,
        revision = VaultRevision("rev-$id", 1),
        createdAt = 1,
        updatedAt = 1,
    )

    private fun vaultChapter(
        path: String,
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
            path = path,
            format = VaultChapterContentFormat.CBZ,
            sizeBytes = 10,
            checksumSha256 = checksum,
        ),
        revision = VaultRevision("chapter-rev", 1),
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )
}

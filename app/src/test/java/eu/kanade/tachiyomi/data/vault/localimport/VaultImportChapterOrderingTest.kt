package eu.kanade.tachiyomi.data.vault.localimport

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent

class VaultImportChapterOrderingTest {

    @Test
    fun `local chapter file candidates include cbz fallback for stale extensionless urls`() {
        localChapterFileNameCandidates("Manga/1") shouldBe listOf("1", "1.cbz")
        localChapterFileNameCandidates("Manga/2.1") shouldBe listOf("2.1", "2.1.cbz")
        localChapterFileNameCandidates("Manga/Chapter 1.cbz") shouldBe listOf("Chapter 1.cbz")
    }

    @Test
    fun `vault import chapter order uses latest first physical filenames`() {
        val ordered = orderVaultImportChapters(
            chapters = listOf(
                manifestChapter(identity = "existing-1", fileName = "Chapter 1.cbz", sourceOrder = 99),
                manifestChapter(identity = "new-10", fileName = "Chapter 10.cbz", sourceOrder = 1),
                manifestChapter(identity = "existing-2", fileName = "Chapter 2.cbz", sourceOrder = 42),
            ),
            replacementIdentities = emptySet(),
        )

        ordered.map { it.content.path.substringAfterLast('/') } shouldBe listOf(
            "Chapter 10.cbz",
            "Chapter 2.cbz",
            "Chapter 1.cbz",
        )
        ordered.map { it.sourceOrder } shouldBe listOf(0L, 1L, 2L)
    }

    @Test
    fun `vault import chapter order preserves replacement catalogue position`() {
        val ordered = orderVaultImportChapters(
            chapters = listOf(
                manifestChapter(identity = "old-1", fileName = "Chapter 1.cbz", sourceOrder = 7),
                manifestChapter(identity = "replacement", fileName = "Chapter 2.cbz", sourceOrder = 5),
                manifestChapter(identity = "new-10", fileName = "Chapter 10.cbz", sourceOrder = 3),
            ),
            replacementIdentities = setOf("replacement"),
        )

        ordered.map { it.identity } shouldBe listOf("new-10", "old-1", "replacement")
        ordered.map { it.sourceOrder } shouldBe listOf(0L, 1L, 5L)
    }

    private fun manifestChapter(
        identity: String,
        fileName: String,
        sourceOrder: Long,
    ) = VaultManifestChapter(
        identity = identity,
        title = fileName.substringBeforeLast('.', missingDelimiterValue = fileName),
        chapterNumber = -1.0,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = sourceOrder,
        content = VaultManifestChapterContent(
            path = "content/manga/$identity/$fileName",
            format = VaultChapterContentFormat.CBZ,
            integrity = VaultContentIntegrity(
                sizeBytes = 1,
                checksumSha256 = identity,
            ),
        ),
        revisionId = "revision-$identity",
        revisionNumber = 1,
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )
}

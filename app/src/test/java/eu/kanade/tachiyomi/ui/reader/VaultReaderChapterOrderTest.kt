package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultRevision

class VaultReaderChapterOrderTest {

    @Test
    fun `vault reader continues from chapter one to chapter two`() {
        val ordered = listOf(
            chapter(id = 1, title = "Chapter 1", chapterNumber = 1.0, sourceOrder = 2),
            chapter(id = 3, title = "Chapter 3", chapterNumber = 3.0, sourceOrder = 0),
            chapter(id = 2, title = "Chapter 2", chapterNumber = 2.0, sourceOrder = 1),
        ).inVaultReaderOrder()

        val chapterOneIndex = ordered.indexOfFirst { it.chapterNumber == 1.0 }

        ordered.map { it.title } shouldBe listOf("Chapter 1", "Chapter 2", "Chapter 3")
        ordered.getOrNull(chapterOneIndex + 1)?.title shouldBe "Chapter 2"
    }

    @Test
    fun `vault reader uses natural title order for unrecognized chapters`() {
        val ordered = listOf(
            chapter(id = 10, title = "Episode 10", chapterNumber = -1.0, sourceOrder = 0),
            chapter(id = 2, title = "Episode 2", chapterNumber = -1.0, sourceOrder = 1),
            chapter(id = 1, title = "Episode 1", chapterNumber = -1.0, sourceOrder = 2),
        ).inVaultReaderOrder()

        ordered.map { it.title } shouldBe listOf("Episode 1", "Episode 2", "Episode 10")
    }

    private fun chapter(
        id: Long,
        title: String,
        chapterNumber: Double,
        sourceOrder: Long,
    ) = VaultChapter(
        id = id,
        mangaId = 1,
        identity = VaultIdentity("chapter-$id"),
        title = title,
        chapterNumber = chapterNumber,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = sourceOrder,
        content = VaultChapterContent(
            path = "content/manga/chapter-$id/chapter.cbz",
            format = VaultChapterContentFormat.CBZ,
            sizeBytes = 1,
            checksumSha256 = "checksum-$id",
        ),
        revision = VaultRevision("revision-$id", 1),
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )
}

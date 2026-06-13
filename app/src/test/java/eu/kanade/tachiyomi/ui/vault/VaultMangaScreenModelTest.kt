package eu.kanade.tachiyomi.ui.vault

import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterThumbnailDisplayResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultRevision

class VaultMangaScreenModelTest {

    @Test
    fun `manga detail chapters sort latest first when catalogue order is stale`() {
        val ordered = orderVaultMangaDetailChapters(
            listOf(
                chapterItem(id = 9, title = "Ep.9 (ch. 9)", sourceOrder = 0),
                chapterItem(id = 8, title = "Ep.8 (ch. 8)", sourceOrder = 1),
                chapterItem(id = 20, title = "Ep.20 (ch. 20)", sourceOrder = 2),
                chapterItem(id = 19, title = "Ep.19 (ch. 19)", sourceOrder = 3),
            ),
        )

        ordered.map { it.chapter.title } shouldBe listOf(
            "Ep.20 (ch. 20)",
            "Ep.19 (ch. 19)",
            "Ep.9 (ch. 9)",
            "Ep.8 (ch. 8)",
        )
    }

    private fun chapterItem(
        id: Long,
        title: String,
        sourceOrder: Long,
        chapterNumber: Double = id.toDouble(),
        cacheState: VaultChapterCacheState? = null,
    ) = VaultMangaScreenModel.VaultChapterItem(
        chapter = VaultChapter(
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
        ),
        cacheState = cacheState,
        thumbnail = VaultChapterThumbnailDisplayResult.NotImplemented,
    )
}

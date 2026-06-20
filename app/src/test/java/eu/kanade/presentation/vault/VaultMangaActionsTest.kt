package eu.kanade.presentation.vault

import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterThumbnailDisplayResult
import eu.kanade.tachiyomi.ui.vault.VaultMangaScreenModel
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultReadingState
import tachiyomi.domain.vault.model.VaultRevision

class VaultMangaActionsTest {

    @Test
    fun `primary action resumes the latest read chapter`() {
        val state = VaultMangaScreenModel.State(
            chapters = listOf(
                chapterItem(id = 1, lastReadAt = 20),
                chapterItem(id = 2, lastReadAt = 50),
                chapterItem(id = 3, lastReadAt = 10),
            ),
        )

        state.primaryActionChapter()?.chapter?.id shouldBe 2
    }

    @Test
    fun `primary action falls back to catalogue order instead of cached chapter`() {
        val state = VaultMangaScreenModel.State(
            chapters = listOf(
                chapterItem(id = 2, sourceOrder = 2, cacheState = VaultCacheState.CACHED),
                chapterItem(id = 1, sourceOrder = 1, cacheState = VaultCacheState.VAULT_ONLY),
            ),
        )

        state.primaryActionChapter()?.chapter?.id shouldBe 1
    }

    private fun chapterItem(
        id: Long,
        sourceOrder: Long = id,
        lastReadAt: Long? = null,
        cacheState: VaultCacheState = VaultCacheState.VAULT_ONLY,
    ) = VaultMangaScreenModel.VaultChapterItem(
        chapter = chapter(id, sourceOrder),
        cacheState = VaultChapterCacheState(
            chapterId = id,
            state = cacheState,
            localPath = null,
            sizeBytes = null,
            checksumSha256 = null,
            lastVerifiedAt = null,
            lastOpenedAt = null,
            updatedAt = 1,
            failureReason = null,
        ),
        readingState = lastReadAt?.let {
            VaultReadingState(
                chapterId = id,
                read = false,
                bookmark = false,
                lastPageRead = 0,
                lastReadAt = it,
                updatedAt = it,
            )
        },
        thumbnail = VaultChapterThumbnailDisplayResult.NotImplemented,
    )

    private fun chapter(
        id: Long,
        sourceOrder: Long,
    ) = VaultChapter(
        id = id,
        mangaId = 1,
        identity = VaultIdentity("chapter-$id"),
        title = "Chapter $id",
        chapterNumber = id.toDouble(),
        volumeNumber = null,
        scanlator = null,
        sourceOrder = sourceOrder,
        content = VaultChapterContent(
            path = "content/manga/chapter-$id/chapter.cbz",
            format = VaultChapterContentFormat.CBZ,
            sizeBytes = 1,
            checksumSha256 = "checksum-$id",
        ),
        thumbnail = null,
        revision = VaultRevision("revision-$id", 1),
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )
}

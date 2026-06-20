package eu.kanade.tachiyomi.ui.vault

import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterThumbnailDisplayResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultChapterThumbnail
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

    @Test
    fun `chapter item rebuild preserves loaded thumbnail when cache state changes`() {
        val chapter = chapter(id = 1, thumbnailIdentity = "thumbnail-1")
        val previous = VaultMangaScreenModel.VaultChapterItem(
            chapter = chapter,
            cacheState = null,
            thumbnail = VaultChapterThumbnailDisplayResult.Ready("file:///thumbnail-1.jpg"),
        )

        val rebuilt = buildVaultChapterItems(
            chapters = listOf(chapter),
            cacheStates = listOf(cacheState(chapterId = chapter.id)),
            previousItems = listOf(previous),
        )

        rebuilt.single().thumbnail shouldBe previous.thumbnail
        rebuilt.single().state shouldBe VaultCacheState.CACHED
    }

    @Test
    fun `chapter item rebuild resets thumbnail when thumbnail identity changes`() {
        val previousChapter = chapter(id = 1, thumbnailIdentity = "thumbnail-1")
        val updatedChapter = chapter(id = 1, thumbnailIdentity = "thumbnail-2")
        val previous = VaultMangaScreenModel.VaultChapterItem(
            chapter = previousChapter,
            cacheState = null,
            thumbnail = VaultChapterThumbnailDisplayResult.Ready("file:///thumbnail-1.jpg"),
        )

        val rebuilt = buildVaultChapterItems(
            chapters = listOf(updatedChapter),
            cacheStates = emptyList(),
            previousItems = listOf(previous),
        )

        rebuilt.single().thumbnail shouldBe VaultChapterThumbnailDisplayResult.Unavailable
    }

    @Test
    fun `chapter item rebuild marks pending deletion`() {
        val chapter = chapter(id = 1)

        val rebuilt = buildVaultChapterItems(
            chapters = listOf(chapter),
            cacheStates = emptyList(),
            previousItems = emptyList(),
            pendingDeletingChapterIds = setOf(chapter.id),
        )

        rebuilt.single().isDeleting shouldBe true
        rebuilt.single().canDownloadCbz shouldBe false
    }

    private fun chapterItem(
        id: Long,
        title: String,
        sourceOrder: Long,
        chapterNumber: Double = id.toDouble(),
        cacheState: VaultChapterCacheState? = null,
    ) = VaultMangaScreenModel.VaultChapterItem(
        chapter = chapter(
            id = id,
            title = title,
            sourceOrder = sourceOrder,
            chapterNumber = chapterNumber,
        ),
        cacheState = cacheState,
        thumbnail = VaultChapterThumbnailDisplayResult.NotImplemented,
    )

    private fun chapter(
        id: Long,
        title: String = "Chapter $id",
        sourceOrder: Long = id,
        chapterNumber: Double = id.toDouble(),
        thumbnailIdentity: String? = null,
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
        thumbnail = thumbnailIdentity?.let {
            VaultChapterThumbnail(
                id = id,
                chapterId = id,
                identity = VaultIdentity(it),
                path = "content/manga/chapter-$id/thumbnail/$it.jpg",
                mediaType = "image/jpeg",
                sizeBytes = 1,
                checksumSha256 = "thumbnail-checksum-$id",
                revision = VaultRevision("thumbnail-revision-$id", 1),
                updatedAt = 1,
            )
        },
        revision = VaultRevision("revision-$id", 1),
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun cacheState(chapterId: Long) = VaultChapterCacheState(
        chapterId = chapterId,
        state = VaultCacheState.CACHED,
        localPath = "vault/chapter-$chapterId.cbz",
        sizeBytes = 1,
        checksumSha256 = "checksum-$chapterId",
        lastVerifiedAt = 1,
        lastOpenedAt = 2,
        updatedAt = 2,
        failureReason = null,
    )
}

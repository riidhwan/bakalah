package eu.kanade.tachiyomi.data.vault.export

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VaultChapterExportServiceTest {

    @Test
    fun `remote path includes configured root path and content path`() {
        vaultChapterRemotePath(
            rootPath = "/bakalah/vault/",
            contentPath = "/content/manga/chapter/chapter.cbz",
        ) shouldBe "bakalah/vault/content/manga/chapter/chapter.cbz"
    }

    @Test
    fun `export filename uses manga and chapter titles with cbz extension`() {
        vaultChapterExportFilename(
            mangaTitle = "Manga: Title",
            chapterTitle = "Chapter / 01",
        ) shouldBe "Manga_ Title - Chapter _ 01.cbz"
    }

    @Test
    fun `thumbnail export filename uses manga and chapter titles with thumbnail extension`() {
        vaultChapterThumbnailExportFilename(
            mangaTitle = "Manga: Title",
            chapterTitle = "Chapter / 01",
            thumbnailPath = "content/manga/chapter/thumbnail/thumbnail.webp",
        ) shouldBe "Manga_ Title - Chapter _ 01 thumbnail.webp"
    }

    @Test
    fun `unique downloads filename appends browser style suffix`() {
        val existing = setOf(
            "Manga - Chapter.cbz",
            "Manga - Chapter (1).cbz",
        )

        uniqueDownloadsFilename("Manga - Chapter.cbz") { it in existing } shouldBe "Manga - Chapter (2).cbz"
    }
}

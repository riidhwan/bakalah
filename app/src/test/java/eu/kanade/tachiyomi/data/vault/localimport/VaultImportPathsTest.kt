package eu.kanade.tachiyomi.data.vault.localimport

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VaultImportPathsTest {

    @Test
    fun `webdav paths encode chapter filenames without losing base path`() {
        val url = "https://example.test/remote.php/dav/files/user"
            .resolveWebDavPath("vault/content/manga-id/chapter-id/Ch 1 [Final] #1?.cbz")

        url.toString() shouldBe
            "https://example.test/remote.php/dav/files/user/vault/content/manga-id/chapter-id/Ch%201%20[Final]%20%231%3F.cbz"
    }

    @Test
    fun `webdav collection paths end with slash`() {
        val url = "https://example.test/remote.php/dav/files/user/"
            .resolveWebDavPath("vault/content", collection = true)

        url.toString() shouldBe "https://example.test/remote.php/dav/files/user/vault/content/"
    }

    @Test
    fun `relative paths remove encoded leading separator`() {
        val path = relativePathFromUriStrings(
            rootUri = "content://local/tree/Manga/document/Manga%2FChapter%201",
            fileUri = "content://local/tree/Manga/document/Manga%2FChapter%201%2F001.webp",
        )

        path shouldBe "001.webp"
    }
}

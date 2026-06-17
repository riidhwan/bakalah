package eu.kanade.tachiyomi.data.vault.localimport

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VaultImportPathsTest {

    @Test
    fun `relative paths remove encoded leading separator`() {
        val path = relativePathFromUriStrings(
            rootUri = "content://local/tree/Manga/document/Manga%2FChapter%201",
            fileUri = "content://local/tree/Manga/document/Manga%2FChapter%201%2F001.webp",
        )

        path shouldBe "001.webp"
    }
}

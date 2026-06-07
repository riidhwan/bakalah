package eu.kanade.tachiyomi.data.vault

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class LocalVaultImportServiceTest {

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

    @Test
    fun `cbz entry names are relative and normalized`() {
        cbzEntryName("\\001.jpg") shouldBe "001.jpg"
        cbzEntryName("/nested/002.jpg") shouldBe "nested/002.jpg"
    }

    @Test
    fun `collision safe cbz name appends suffix before extension`() {
        val name = collisionSafeCbzName(
            baseName = "Ch 1?",
            existingNames = setOf("Ch 1_.cbz", "ch 1_ (1).CBZ"),
        )

        name shouldBe "Ch 1_ (2).cbz"
    }

    @Test
    fun `stored cbz preserves entry order and bytes`() {
        val output = ByteArrayOutputStream()

        writeStoredCbz(
            output = output,
            entries = listOf(
                CbzEntry("002.jpg") { ByteArrayInputStream(byteArrayOf(2)) },
                CbzEntry("001.jpg") { ByteArrayInputStream(byteArrayOf(1)) },
            ),
        )

        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            val first = zip.nextEntry
            first.name shouldBe "002.jpg"
            zip.readBytes() shouldBe byteArrayOf(2)
            val second = zip.nextEntry
            second.name shouldBe "001.jpg"
            zip.readBytes() shouldBe byteArrayOf(1)
        }
    }
}

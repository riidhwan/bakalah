package eu.kanade.tachiyomi.data.vault

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent
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
    fun `numbered cbz entry names use flat three digit sequence`() {
        numberedCbzEntryName(index = 1, extension = "JPG") shouldBe "001.jpg"
        numberedCbzEntryName(index = 12, extension = "webp") shouldBe "012.webp"
        numberedCbzEntryName(index = 1234, extension = null) shouldBe "1234.jpg"
    }

    @Test
    fun `local chapter file candidates include cbz fallback for stale extensionless urls`() {
        localChapterFileNameCandidates("Manga/1") shouldBe listOf("1", "1.cbz")
        localChapterFileNameCandidates("Manga/2.1") shouldBe listOf("2.1", "2.1.cbz")
        localChapterFileNameCandidates("Manga/Chapter 1.cbz") shouldBe listOf("Chapter 1.cbz")
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
    fun `directory cbz base name preserves decimal chapter names`() {
        val name = collisionSafeCbzName(
            baseName = directoryChapterCbzBaseName("6.5"),
            existingNames = setOf("6.cbz"),
        )

        name shouldBe "6.5.cbz"
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

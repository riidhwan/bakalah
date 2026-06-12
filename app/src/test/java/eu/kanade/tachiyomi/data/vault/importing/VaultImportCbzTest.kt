package eu.kanade.tachiyomi.data.vault.importing

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class VaultImportCbzTest {

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
}

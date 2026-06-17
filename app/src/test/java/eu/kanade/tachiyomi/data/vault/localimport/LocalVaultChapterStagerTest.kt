package eu.kanade.tachiyomi.data.vault.localimport

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.localimport.LocalVaultChapterStager
import eu.kanade.tachiyomi.data.vault.localimport.ScannedLocalVaultChapter
import eu.kanade.tachiyomi.data.vault.staging.digest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.vault.model.LocalVaultImportChapter
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipInputStream

class LocalVaultChapterStagerTest {

    @TempDir
    lateinit var tempDir: Path

    private val stager = LocalVaultChapterStager(
        isReadablePageFile = { file ->
            !file.isDirectory && file.name.orEmpty().substringAfterLast('.') in setOf("jpg", "png", "webp")
        },
        imageExtension = { file ->
            file.name.orEmpty().substringAfterLast('.', missingDelimiterValue = "").takeIf { it.isNotBlank() }
        },
        splitTallImage = { _, _, _ -> },
    )

    @Test
    fun `already cbz chapter is returned unchanged`() {
        val cbzFile = tempDir.resolve("Chapter 1.cbz").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val scanned = scannedChapter(
            file = cbzFile.toUniFile(),
            sourceFileName = "Chapter 1.cbz",
            requiresLocalCbzConversion = false,
        )

        val staged = stager.stageForUpload(scanned, stagingRoot())

        staged shouldBe scanned
    }

    @Test
    fun `directory chapter is converted to flat numbered cbz`() {
        val chapterDir = tempDir.resolve("6.5").toFile().apply { mkdirs() }
        chapterDir.resolve("002.png").writeBytes(byteArrayOf(2))
        chapterDir.resolve("001.jpg").writeBytes(byteArrayOf(1))
        chapterDir.resolve("notes.txt").writeText("ignore me")
        val scanned = scannedChapter(
            file = chapterDir.toUniFile(),
            sourceFileName = "6.5",
            requiresLocalCbzConversion = true,
        )

        val staged = stager.stageForUpload(scanned, stagingRoot())

        staged.file.isDirectory shouldBe false
        staged.file.name shouldNotBe scanned.file.name
        staged.chapter.sourceFileName shouldBe "6.5.cbz"
        staged.chapter.contentFormat shouldBe VaultChapterContentFormat.CBZ
        staged.chapter.requiresLocalCbzConversion shouldBe false
        staged.chapter.sizeBytes shouldBe staged.file.digest().sizeBytes
        staged.chapter.checksumSha256 shouldBe staged.file.digest().sha256
        staged.zipEntries() shouldContainExactly listOf("001.jpg", "002.png")
        chapterDir.resolve("001.jpg").exists() shouldBe true
        chapterDir.resolve("002.png").exists() shouldBe true
        chapterDir.resolve("notes.txt").exists() shouldBe true
    }

    @Test
    fun `non-image files are ignored during directory staging`() {
        val chapterDir = tempDir.resolve("Chapter 2").toFile().apply { mkdirs() }
        chapterDir.resolve("page.webp").writeBytes(byteArrayOf(1))
        chapterDir.resolve("metadata.json").writeText("{}")
        val scanned = scannedChapter(
            file = chapterDir.toUniFile(),
            sourceFileName = "Chapter 2",
            requiresLocalCbzConversion = true,
        )

        val staged = stager.stageForUpload(scanned, stagingRoot())

        staged.zipEntries() shouldContainExactly listOf("001.webp")
    }

    @Test
    fun `empty directory staging fails`() {
        val chapterDir = tempDir.resolve("empty").toFile().apply { mkdirs() }
        chapterDir.resolve("notes.txt").writeText("no pages")
        val scanned = scannedChapter(
            file = chapterDir.toUniFile(),
            sourceFileName = "empty",
            requiresLocalCbzConversion = true,
        )

        val error = assertThrows<IllegalArgumentException> {
            stager.stageForUpload(scanned, stagingRoot())
        }

        error.message shouldBe "empty_pages"
    }

    private fun stagingRoot() = tempDir.resolve("staging").toFile().apply {
        mkdirs()
    }

    private fun scannedChapter(
        file: UniFile,
        sourceFileName: String,
        requiresLocalCbzConversion: Boolean,
    ) = ScannedLocalVaultChapter(
        file = file,
        chapter = LocalVaultImportChapter(
            selectionId = sourceFileName,
            sourceFileName = sourceFileName,
            title = sourceFileName,
            chapterNumber = -1.0,
            volumeNumber = null,
            scanlator = null,
            sourceOrder = 0,
            contentFormat = VaultChapterContentFormat.CBZ,
            sizeBytes = 0,
            checksumSha256 = "preview",
            dateUpload = 0,
            requiresLocalCbzConversion = requiresLocalCbzConversion,
        ),
    )

    private fun ScannedLocalVaultChapter.zipEntries(): List<String> {
        return ZipInputStream(file.openInputStream()).use { zip ->
            generateSequence { zip.nextEntry }
                .map { it.name }
                .toList()
        }
    }

    private fun File.toUniFile(): UniFile = UniFile.fromFile(this) ?: error("test file")
}

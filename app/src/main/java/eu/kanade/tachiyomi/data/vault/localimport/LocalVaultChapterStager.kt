package eu.kanade.tachiyomi.data.vault.localimport

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.staging.CbzEntry
import eu.kanade.tachiyomi.data.vault.staging.collisionSafeCbzName
import eu.kanade.tachiyomi.data.vault.staging.digest
import eu.kanade.tachiyomi.data.vault.staging.directoryChapterCbzBaseName
import eu.kanade.tachiyomi.data.vault.staging.numberedCbzEntryName
import eu.kanade.tachiyomi.data.vault.staging.validateCbz
import eu.kanade.tachiyomi.data.vault.staging.writeStoredCbz
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import java.io.File
import java.util.Locale
import java.util.UUID

internal class LocalVaultChapterStager(
    private val isReadablePageFile: (file: UniFile) -> Boolean = { file ->
        !file.isDirectory && ImageUtil.isImage(file.name) { file.openInputStream() }
    },
    private val imageExtension: (file: UniFile) -> String? = { file ->
        ImageUtil.findImageType { file.openInputStream() }?.extension
            ?: file.extension?.lowercase()
    },
    private val splitTallImage: (
        chapterDirectory: UniFile,
        imageFile: UniFile,
        filename: String,
    ) -> Unit = { chapterDirectory, imageFile, filename ->
        ImageUtil.splitTallImage(chapterDirectory, imageFile, filename)
    },
) {
    fun stageForUpload(
        chapter: ScannedLocalVaultChapter,
        stagingRoot: File,
    ): ScannedLocalVaultChapter {
        return if (chapter.file.isDirectory) {
            chapter.stageDirectoryAsCbz(stagingRoot)
        } else {
            chapter
        }
    }

    private fun ScannedLocalVaultChapter.stageDirectoryAsCbz(stagingRoot: File): ScannedLocalVaultChapter {
        val pageRoot = File(stagingRoot, "pages").apply { mkdirs() }
        val pageRootFile = UniFile.fromFile(pageRoot) ?: error("staging")
        val archive = UniFile.fromFile(File(stagingRoot, "${UUID.randomUUID()}.cbz")) ?: error("staging")
        val pages = file.listFilesRecursively()
            .filter(isReadablePageFile)
        require(pages.isNotEmpty()) { "empty_pages" }

        val digitCount = pages.size.toString().length.coerceAtLeast(3)
        pages.forEachIndexed { index, page ->
            val filename = "%0${digitCount}d".format(Locale.ENGLISH, index + 1)
            val extension = imageExtension(page) ?: "jpg"
            val target = File(pageRoot, "$filename.$extension")
            page.openInputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            splitStagedImage(pageRootFile, filename)
        }

        val entries = pageRootFile.listFiles().orEmpty()
            .filter(isReadablePageFile)
            .sortedBy { it.name.orEmpty() }
            .mapIndexed { index, page ->
                CbzEntry(
                    name = numberedCbzEntryName(index = index + 1, extension = page.extension),
                    openInputStream = { page.openInputStream() },
                )
            }
        require(entries.isNotEmpty()) { "empty_pages" }
        archive.openOutputStream().use { output ->
            writeStoredCbz(output, entries)
        }
        validateCbz(archive, entries.map { it.name })
        val digest = archive.digest()
        val archiveName = collisionSafeCbzName(
            baseName = directoryChapterCbzBaseName(file.name),
            existingNames = emptySet(),
        )
        return copy(
            file = archive,
            chapter = chapter.copy(
                sourceFileName = archiveName,
                contentFormat = VaultChapterContentFormat.CBZ,
                sizeBytes = digest.sizeBytes,
                checksumSha256 = digest.sha256,
                requiresLocalCbzConversion = false,
            ),
        )
    }

    private fun splitStagedImage(chapterUniFile: UniFile, filename: String) {
        val imageFile = chapterUniFile.listFiles().orEmpty()
            .firstOrNull { it.name.orEmpty().startsWith(filename) }
            ?: error("staging")
        splitTallImage(chapterUniFile, imageFile, filename)
    }

    private fun UniFile.listFilesRecursively(): List<UniFile> {
        return listFilesWithRelativePaths()
            .sortedWith { first, second ->
                first.relativePath.compareToCaseInsensitiveNaturalOrder(second.relativePath)
            }
            .map { it.file }
    }

    private fun UniFile.listFilesWithRelativePaths(prefix: String = ""): List<RelativeUniFile> {
        return listFiles().orEmpty().flatMap { file ->
            val relativePath = listOf(prefix, file.name.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString("/")
            if (file.isDirectory) {
                file.listFilesWithRelativePaths(relativePath)
            } else {
                listOf(RelativeUniFile(relativePath, file))
            }
        }
    }

    private data class RelativeUniFile(
        val relativePath: String,
        val file: UniFile,
    )
}

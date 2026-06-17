package eu.kanade.tachiyomi.data.vault.capture

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.vault.add.AddToVaultProgressPhase
import eu.kanade.tachiyomi.data.vault.staging.CbzEntry
import eu.kanade.tachiyomi.data.vault.staging.digest
import eu.kanade.tachiyomi.data.vault.staging.validateCbz
import eu.kanade.tachiyomi.data.vault.staging.writeStoredCbz
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.archive.archiveReader
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.UUID

internal interface LibraryVaultChapterStager {
    suspend fun stageForCapture(
        source: HttpSource,
        manga: Manga,
        chapter: Chapter,
        stagingRoot: File,
        progressPhase: (AddToVaultProgressPhase) -> Unit,
    ): LibraryVaultStagedChapter

    suspend fun findCaptureCover(manga: Manga, source: HttpSource): LibraryVaultCaptureCover?
}

internal class DefaultLibraryVaultChapterStager(
    private val context: Context,
    private val downloadProvider: DownloadProvider,
    private val coverCache: CoverCache,
) : LibraryVaultChapterStager {
    override suspend fun stageForCapture(
        source: HttpSource,
        manga: Manga,
        chapter: Chapter,
        stagingRoot: File,
        progressPhase: (AddToVaultProgressPhase) -> Unit,
    ): LibraryVaultStagedChapter = withIOContext {
        val chapterDir = File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val chapterUniFile = UniFile.fromFile(chapterDir) ?: error("staging")
        try {
            val downloaded = downloadProvider.findChapterDir(
                chapterName = chapter.name,
                chapterScanlator = chapter.scanlator,
                chapterUrl = chapter.url,
                mangaTitle = manga.title,
                source = source,
            )
            if (downloaded != null) {
                progressPhase(AddToVaultProgressPhase.COPYING_DOWNLOADED)
                runCatching {
                    copyDownloadedPages(downloaded, chapterUniFile)
                }.getOrElse {
                    chapterUniFile.listFiles().orEmpty().forEach { file -> file.delete() }
                    progressPhase(AddToVaultProgressPhase.DOWNLOADING)
                    fetchSourcePages(source, chapter, chapterUniFile)
                }
            } else {
                progressPhase(AddToVaultProgressPhase.DOWNLOADING)
                fetchSourcePages(source, chapter, chapterUniFile)
            }
            progressPhase(AddToVaultProgressPhase.COMPRESSING)
            val entries = chapterUniFile.listFiles().orEmpty()
                .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
                .sortedBy { it.name.orEmpty() }
                .mapIndexed { index, file ->
                    CbzEntry(
                        name = "%03d.${file.extension ?: "jpg"}".format(Locale.ENGLISH, index + 1),
                        openInputStream = { file.openInputStream() },
                    )
                }
            require(entries.isNotEmpty()) { "empty_pages" }
            val cbzFile = UniFile.fromFile(File(stagingRoot, "${UUID.randomUUID()}.cbz")) ?: error("staging")
            cbzFile.openOutputStream().use { output ->
                writeStoredCbz(output, entries)
            }
            validateCbz(cbzFile, entries.map { it.name })
            val digest = cbzFile.digest()
            LibraryVaultStagedChapter(cbzFile, digest.sizeBytes, digest.sha256)
        } finally {
            chapterDir.deleteRecursively()
        }
    }

    override suspend fun findCaptureCover(manga: Manga, source: HttpSource): LibraryVaultCaptureCover? {
        return withContext(Dispatchers.IO) {
            listOfNotNull(
                coverCache.getCustomCoverFile(manga.id).takeIf { it.exists() && it.isFile },
                coverCache.getCoverFile(manga.thumbnailUrl)?.takeIf { it.exists() && it.isFile },
            ).firstNotNullOfOrNull { file ->
                file.readBytes().toCaptureCover(file.name, null)
            } ?: manga.fetchCaptureCover(source)
        }
    }

    private suspend fun fetchSourcePages(
        source: HttpSource,
        chapter: Chapter,
        chapterUniFile: UniFile,
    ) {
        val pages = source.getPageList(chapter.toSChapter()).mapIndexed { index, page ->
            Page(index, page.url, page.imageUrl, page.uri)
        }
        require(pages.isNotEmpty()) { "empty_pages" }
        val digitCount = pages.size.toString().length.coerceAtLeast(3)
        pages.forEach { page ->
            if (page.imageUrl.isNullOrEmpty()) {
                page.imageUrl = source.getImageUrl(page)
            }
            val filename = "%0${digitCount}d".format(Locale.ENGLISH, page.number)
            val response = source.getImage(page)
            val tmpFile = chapterUniFile.createFile("$filename.tmp") ?: error("staging")
            try {
                response.body.source().use { input ->
                    tmpFile.openOutputStream().use { output ->
                        output.write(input.readByteArray())
                    }
                }
                val extension = ImageUtil.getExtensionFromMimeType(
                    response.body.contentType()?.run { if (type == "image") "image/$subtype" else null },
                ) { tmpFile.openInputStream() }
                tmpFile.renameTo("$filename.$extension")
                splitStagedImage(chapterUniFile, filename)
            } finally {
                response.close()
            }
        }
    }

    private fun copyDownloadedPages(downloaded: UniFile, chapterUniFile: UniFile) {
        if (downloaded.isDirectory) {
            val files = downloaded.listFiles().orEmpty()
                .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
                .sortedBy { it.name.orEmpty() }
            require(files.isNotEmpty()) { "downloaded_copy" }
            val digitCount = files.size.toString().length.coerceAtLeast(3)
            files.forEachIndexed { index, file ->
                val filename = "%0${digitCount}d".format(Locale.ENGLISH, index + 1)
                val extension =
                    ImageUtil.findImageType { file.openInputStream() }?.extension ?: file.extension?.lowercase()
                        ?: "jpg"
                val target = chapterUniFile.createFile("$filename.$extension") ?: error("staging")
                file.openInputStream().use { input ->
                    target.openOutputStream().use { output -> input.copyTo(output) }
                }
                splitStagedImage(chapterUniFile, filename)
            }
            return
        }

        val archiveEntries = downloaded.archiveReader(context).use { reader ->
            reader.useEntries { entries ->
                entries
                    .filter { it.isFile }
                    .sortedBy { it.name }
                    .mapNotNull { entry ->
                        val bytes = reader.getInputStream(entry.name)?.use { it.readBytes() } ?: return@mapNotNull null
                        val imageType = ImageUtil.findImageType(ByteArrayInputStream(bytes))
                            ?: entry.name.imageTypeFromExtension()
                            ?: return@mapNotNull null
                        ArchivedPage(bytes = bytes, extension = imageType.extension)
                    }
                    .toList()
            }
        }
        require(archiveEntries.isNotEmpty()) { "downloaded_copy" }
        val digitCount = archiveEntries.size.toString().length.coerceAtLeast(3)
        val extracted = archiveEntries.mapIndexed { index, page ->
            val filename = "%0${digitCount}d".format(Locale.ENGLISH, index + 1)
            val target = chapterUniFile.createFile("$filename.${page.extension}") ?: error("staging")
            target.openOutputStream().use { output -> output.write(page.bytes) }
            target
        }
        extracted.forEach { file ->
            val filename = file.name.orEmpty().substringBeforeLast('.', file.name.orEmpty())
            splitStagedImage(chapterUniFile, filename)
        }
    }

    private fun splitStagedImage(chapterUniFile: UniFile, filename: String) {
        val imageFile = chapterUniFile.listFiles().orEmpty()
            .firstOrNull { it.name.orEmpty().startsWith(filename) }
            ?: error("staging")
        ImageUtil.splitTallImage(chapterUniFile, imageFile, filename)
    }

    private fun Manga.fetchCaptureCover(source: HttpSource): LibraryVaultCaptureCover? {
        val coverUrl = thumbnailUrl?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val request = okhttp3.Request.Builder()
                .url(coverUrl)
                .headers(source.headers)
                .build()
            source.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val mediaType = response.body.contentType()
                    ?.toString()
                    ?.takeIf { it.startsWith("image/") }
                response.body.bytes().toCaptureCover(coverUrl.substringAfterLast('/'), mediaType)
            }
        }.getOrNull()
    }

    private fun ByteArray.toCaptureCover(fileName: String?, mediaType: String?): LibraryVaultCaptureCover? {
        val imageType = ImageUtil.findImageType(ByteArrayInputStream(this))
        val extension = imageType?.extension
            ?: mediaType.mediaTypeExtension()
            ?: fileName?.imageTypeFromExtension()?.extension
            ?: return null
        val normalizedExtension = extension.validImageExtension() ?: return null
        return LibraryVaultCaptureCover(
            bytes = this,
            extension = normalizedExtension,
            mediaType = imageType?.mime ?: mediaType,
        )
    }

    private fun String?.mediaTypeExtension(): String? {
        return when (this) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/avif" -> "avif"
            "image/heif" -> "heif"
            "image/jxl" -> "jxl"
            else -> null
        }
    }

    private fun String.imageTypeFromExtension(): ImageUtil.ImageType? {
        val extension = substringAfterLast('.', "").lowercase()
        return ImageUtil.ImageType.entries.firstOrNull { it.extension == extension }
    }

    private fun String.validImageExtension(): String? {
        return lowercase()
            .takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
            ?.takeIf { extension ->
                ImageUtil.ImageType.entries.any { it.extension == extension }
            }
    }

    private data class ArchivedPage(
        val bytes: ByteArray,
        val extension: String,
    )
}

internal data class LibraryVaultStagedChapter(
    val file: UniFile,
    val sizeBytes: Long,
    val checksumSha256: String,
)

internal data class LibraryVaultCaptureCover(
    val bytes: ByteArray,
    val extension: String,
    val mediaType: String?,
)

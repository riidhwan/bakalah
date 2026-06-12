package eu.kanade.tachiyomi.data.vault.importing

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.vault.model.LocalVaultImportChapter
import tachiyomi.domain.vault.model.LocalVaultImportManga
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem

internal class LocalVaultMangaScanner(
    private val sourceManager: SourceManager,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {
    fun localSourceName(): String? = localSource()?.name

    suspend fun scan(
        manga: Manga,
        selectedChapterIds: Set<String>? = null,
    ): LocalVaultMangaScan? = withContext(Dispatchers.IO) {
        if (selectedChapterIds != null) {
            return@withContext scanSelectedLocalManga(manga, selectedChapterIds)
        }

        val localSource = localSource() ?: return@withContext null
        val details = manga.toSourceManga()
        val chapterFiles = fileSystem.getFilesInMangaDirectory(manga.url).associateBy { it.name.orEmpty() }
        val chapters = localSource.getChapterList(details).mapIndexedNotNull { index, chapter ->
            val fileName = chapter.url.substringAfter('/', missingDelimiterValue = chapter.url)
            val file = chapterFiles[fileName] ?: return@mapIndexedNotNull null
            if (!file.isDirectory && !file.isCbz()) return@mapIndexedNotNull null
            val digest = file.previewDigest()
            ScannedLocalVaultChapter(
                file = file,
                chapter = LocalVaultImportChapter(
                    selectionId = chapter.url,
                    sourceFileName = file.name.orEmpty(),
                    title = chapter.name,
                    chapterNumber = chapter.chapter_number.toDouble(),
                    volumeNumber = null,
                    scanlator = chapter.scanlator,
                    sourceOrder = index.toLong(),
                    contentFormat = VaultChapterContentFormat.CBZ,
                    sizeBytes = digest.sizeBytes,
                    checksumSha256 = digest.sha256,
                    dateUpload = chapter.date_upload,
                    requiresLocalCbzConversion = file.isDirectory,
                ),
            )
        }
        LocalVaultMangaScan(
            manga = LocalVaultImportManga(
                localMangaId = manga.id,
                localMangaIdentity = manga.url,
                title = details.title,
                metadata = VaultMetadata(
                    title = details.title,
                    author = details.author,
                    artist = details.artist,
                    description = details.description,
                    status = details.status.toVaultStatus(),
                ),
            ),
            chapters = chapters,
            coverFile = coverManager.find(manga.url),
        )
    }

    private suspend fun scanSelectedLocalManga(
        manga: Manga,
        selectedChapterIds: Set<String>,
    ): LocalVaultMangaScan? {
        val details = manga.toSourceManga()
        val chapterFiles = fileSystem.getFilesInMangaDirectory(manga.url).associateBy { it.name.orEmpty() }
        val chapters = getChaptersByMangaId.await(manga.id)
            .filter { it.url in selectedChapterIds }
            .mapNotNull { chapter ->
                val file = chapter.resolveLocalChapterFile(chapterFiles) ?: return@mapNotNull null
                if (!file.isDirectory && !file.isCbz()) return@mapNotNull null
                val digest = file.previewDigest()
                ScannedLocalVaultChapter(
                    file = file,
                    chapter = chapter.toLocalVaultImportChapter(
                        sourceFileName = file.name.orEmpty(),
                        sizeBytes = digest.sizeBytes,
                        checksumSha256 = digest.sha256,
                        requiresLocalCbzConversion = file.isDirectory,
                    ),
                )
            }
        return LocalVaultMangaScan(
            manga = details.toLocalVaultImportManga(manga),
            chapters = chapters,
            coverFile = coverManager.find(manga.url),
        )
    }

    private fun localSource(): LocalSource? = sourceManager.get(LocalSource.ID) as? LocalSource

    private fun Chapter.resolveLocalChapterFile(chapterFiles: Map<String, UniFile>): UniFile? {
        return localChapterFileNameCandidates(url)
            .firstNotNullOfOrNull(chapterFiles::get)
    }

    private fun Manga.toSourceManga(): SManga {
        return SManga.create().apply {
            url = this@toSourceManga.url
            title = this@toSourceManga.title
            author = this@toSourceManga.author
            artist = this@toSourceManga.artist
            description = this@toSourceManga.description
            status = this@toSourceManga.status.toInt()
            thumbnail_url = this@toSourceManga.thumbnailUrl
        }
    }

    private fun SManga.toLocalVaultImportManga(manga: Manga): LocalVaultImportManga {
        return LocalVaultImportManga(
            localMangaId = manga.id,
            localMangaIdentity = manga.url,
            title = title,
            metadata = VaultMetadata(
                title = title,
                author = author,
                artist = artist,
                description = description,
                status = status.toVaultStatus(),
            ),
        )
    }

    private fun Chapter.toLocalVaultImportChapter(
        sourceFileName: String,
        sizeBytes: Long,
        checksumSha256: String,
        requiresLocalCbzConversion: Boolean,
    ): LocalVaultImportChapter {
        return LocalVaultImportChapter(
            selectionId = url,
            sourceFileName = sourceFileName,
            title = name,
            chapterNumber = chapterNumber,
            volumeNumber = null,
            scanlator = scanlator,
            sourceOrder = sourceOrder,
            contentFormat = VaultChapterContentFormat.CBZ,
            sizeBytes = sizeBytes,
            checksumSha256 = checksumSha256,
            dateUpload = dateUpload,
            requiresLocalCbzConversion = requiresLocalCbzConversion,
        )
    }

    private fun Int.toVaultStatus(): VaultMangaStatus {
        return when (this) {
            SManga.ONGOING -> VaultMangaStatus.ONGOING
            SManga.COMPLETED -> VaultMangaStatus.COMPLETED
            SManga.LICENSED -> VaultMangaStatus.LICENSED
            SManga.PUBLISHING_FINISHED -> VaultMangaStatus.PUBLISHING_FINISHED
            SManga.CANCELLED -> VaultMangaStatus.CANCELLED
            SManga.ON_HIATUS -> VaultMangaStatus.ON_HIATUS
            else -> VaultMangaStatus.UNKNOWN
        }
    }
}

internal data class LocalVaultMangaScan(
    val manga: LocalVaultImportManga,
    val chapters: List<ScannedLocalVaultChapter>,
    val coverFile: UniFile?,
) {
    fun withCreateNewTitle(
        createNew: Boolean,
        title: String?,
    ): LocalVaultMangaScan {
        val targetTitle = title?.trim()?.takeIf { createNew && it.isNotBlank() } ?: return this
        return copy(
            manga = manga.copy(
                title = targetTitle,
                metadata = manga.metadata.copy(title = targetTitle),
            ),
        )
    }
}

internal data class ScannedLocalVaultChapter(
    val file: UniFile,
    val chapter: LocalVaultImportChapter,
)

internal fun localChapterFileNameCandidates(chapterUrl: String): List<String> {
    val fileName = chapterUrl.substringAfter('/', missingDelimiterValue = chapterUrl)
    return if (fileName.endsWith(".cbz", ignoreCase = true)) {
        listOf(fileName)
    } else {
        listOf(fileName, "$fileName.cbz")
    }
}

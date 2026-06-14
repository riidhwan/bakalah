package eu.kanade.tachiyomi.data.vault.export

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.contentValuesOf
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.vault.transfer.UniFileVaultTransferLocalStaging
import eu.kanade.tachiyomi.data.vault.transfer.WebDavVaultTransferStorage
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultChapterThumbnail
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.io.File
import java.security.MessageDigest
import java.time.Instant

class VaultChapterExportService(
    private val context: Context,
    private val preferences: ContentVaultPreferences,
    private val networkHelper: NetworkHelper,
    private val notifier: VaultChapterExportNotifier = VaultChapterExportNotifier(context),
) {

    suspend fun export(
        manga: VaultManga,
        chapter: VaultChapter,
        cacheState: VaultChapterCacheState?,
        localStaging: UniFileVaultTransferLocalStaging?,
    ): VaultChapterExportResult {
        if (chapter.content.format != VaultChapterContentFormat.CBZ) {
            return VaultChapterExportResult.UnsupportedFormat
        }

        val config = preferences.getWebDavConfig()
        if (!config.isComplete) {
            return VaultChapterExportResult.IncompleteConfiguration
        }

        val bytes = verifiedCachedBytes(chapter, cacheState, localStaging)
            ?: remoteBytes(config, chapter)
            ?: return VaultChapterExportResult.RemoteFileNotFound

        if (!bytes.matchesChapterContent(chapter)) {
            return VaultChapterExportResult.IntegrityCheckFailed
        }

        val filename = vaultChapterExportFilename(manga.metadata.title, chapter.title)
        val savedFilename = runCatching {
            saveToDownloads(bytes, filename)
        }.getOrElse {
            return VaultChapterExportResult.SaveFailed
        }
        notifier.showChapterComplete(savedFilename)

        return VaultChapterExportResult.Exported(savedFilename)
    }

    suspend fun exportThumbnail(
        manga: VaultManga,
        chapter: VaultChapter,
    ): VaultChapterExportResult {
        val thumbnail = chapter.thumbnail ?: return VaultChapterExportResult.RemoteFileNotFound
        val config = preferences.getWebDavConfig()
        if (!config.isComplete) {
            return VaultChapterExportResult.IncompleteConfiguration
        }

        val bytes = remoteBytes(config, thumbnail)
            ?: return VaultChapterExportResult.RemoteFileNotFound

        if (!bytes.matchesThumbnail(thumbnail)) {
            return VaultChapterExportResult.IntegrityCheckFailed
        }

        val filename = vaultChapterThumbnailExportFilename(
            mangaTitle = manga.metadata.title,
            chapterTitle = chapter.title,
            thumbnailPath = thumbnail.path,
        )
        val savedFilename = runCatching {
            saveToDownloads(bytes, filename, thumbnail.mediaType ?: OCTET_MIME_TYPE)
        }.getOrElse {
            return VaultChapterExportResult.SaveFailed
        }
        notifier.showThumbnailComplete(savedFilename)

        return VaultChapterExportResult.Exported(savedFilename)
    }

    private suspend fun verifiedCachedBytes(
        chapter: VaultChapter,
        cacheState: VaultChapterCacheState?,
        localStaging: UniFileVaultTransferLocalStaging?,
    ): ByteArray? {
        if (cacheState?.state != VaultCacheState.CACHED || localStaging == null) return null
        val localPath = cacheState.localPath ?: return null
        val bytes = localStaging.read(localPath) ?: return null
        return bytes.takeIf { it.matchesChapterContent(chapter) }
    }

    private suspend fun remoteBytes(
        config: WebDavVaultConfig,
        chapter: VaultChapter,
    ): ByteArray? {
        val storage = WebDavVaultTransferStorage(networkHelper, config)
        return storage.get(vaultChapterRemotePath(config.rootPath, chapter.content.path))
    }

    private suspend fun remoteBytes(
        config: WebDavVaultConfig,
        thumbnail: VaultChapterThumbnail,
    ): ByteArray? {
        val storage = WebDavVaultTransferStorage(networkHelper, config)
        return storage.get(vaultChapterRemotePath(config.rootPath, thumbnail.path))
    }

    private suspend fun saveToDownloads(
        bytes: ByteArray,
        filename: String,
        mimeType: String = CBZ_MIME_TYPE,
    ): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloadsApi29(bytes, filename, mimeType)
        } else {
            saveToDownloadsLegacy(bytes, filename)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloadsApi29(bytes: ByteArray, filename: String, mimeType: String): String {
        val relativePath = Environment.DIRECTORY_DOWNLOADS
        val savedFilename = uniqueDownloadsFilename(filename) { candidate ->
            mediaStoreDownloadExists(relativePath, candidate)
        }
        val values = contentValuesOf(
            MediaStore.MediaColumns.RELATIVE_PATH to relativePath,
            MediaStore.MediaColumns.DISPLAY_NAME to savedFilename,
            MediaStore.MediaColumns.MIME_TYPE to mimeType,
            MediaStore.MediaColumns.DATE_MODIFIED to Instant.now().epochSecond,
        )
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("could not create export")
        try {
            context.contentResolver.openOutputStream(uri, "w").use { output ->
                output ?: error("could not open export")
                output.write(bytes)
            }
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
        DiskUtil.scanMedia(context, uri)
        return savedFilename
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreDownloadExists(relativePath: String, filename: String): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val normalizedPath = "${relativePath.removeSuffix(File.separator)}${File.separator}"
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(normalizedPath, filename),
            null,
        ).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                return true
            }
        }
        return false
    }

    private fun saveToDownloadsLegacy(bytes: ByteArray, filename: String): String {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        directory.mkdirs()
        val savedFilename = uniqueDownloadsFilename(filename) { candidate ->
            File(directory, candidate).exists()
        }
        val file = File(directory, savedFilename)
        file.writeBytes(bytes)
        DiskUtil.scanMedia(context, file.toUri())
        return savedFilename
    }

    private companion object {
        const val CBZ_MIME_TYPE = "application/vnd.comicbook+zip"
        const val OCTET_MIME_TYPE = "application/octet-stream"
    }
}

sealed interface VaultChapterExportResult {
    data class Exported(val filename: String) : VaultChapterExportResult
    data object IncompleteConfiguration : VaultChapterExportResult
    data object RemoteFileNotFound : VaultChapterExportResult
    data object IntegrityCheckFailed : VaultChapterExportResult
    data object UnsupportedFormat : VaultChapterExportResult
    data object SaveFailed : VaultChapterExportResult
}

internal fun vaultChapterRemotePath(rootPath: String, contentPath: String): String {
    return "${rootPath.trim().trimEnd('/')}/${contentPath.trim().trimStart('/')}".trimStart('/')
}

internal fun vaultChapterExportFilename(mangaTitle: String, chapterTitle: String): String {
    return DiskUtil.buildValidFilename("$mangaTitle - $chapterTitle.cbz")
}

internal fun vaultChapterThumbnailExportFilename(
    mangaTitle: String,
    chapterTitle: String,
    thumbnailPath: String,
): String {
    val extension = thumbnailPath.substringAfterLast('.', "jpg")
    return DiskUtil.buildValidFilename("$mangaTitle - $chapterTitle thumbnail.$extension")
}

internal fun uniqueDownloadsFilename(filename: String, exists: (String) -> Boolean): String {
    val cleanFilename = DiskUtil.buildValidFilename(filename)
    if (!exists(cleanFilename)) return cleanFilename
    val extensionIndex = cleanFilename.lastIndexOf('.').takeIf { it > 0 }
    val basename = extensionIndex?.let { cleanFilename.substring(0, it) } ?: cleanFilename
    val extension = extensionIndex?.let { cleanFilename.substring(it) }.orEmpty()
    return generateSequence(1) { it + 1 }
        .map { index -> "$basename ($index)$extension" }
        .first { !exists(it) }
}

private fun ByteArray.matchesChapterContent(chapter: VaultChapter): Boolean {
    return size.toLong() == chapter.content.sizeBytes && sha256() == chapter.content.checksumSha256
}

private fun ByteArray.matchesThumbnail(thumbnail: VaultChapterThumbnail): Boolean {
    val expectedSize = thumbnail.sizeBytes
    if (expectedSize != null && size.toLong() != expectedSize) return false
    val expectedChecksum = thumbnail.checksumSha256
    if (expectedChecksum != null && sha256() != expectedChecksum) return false
    return true
}

private fun ByteArray.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}

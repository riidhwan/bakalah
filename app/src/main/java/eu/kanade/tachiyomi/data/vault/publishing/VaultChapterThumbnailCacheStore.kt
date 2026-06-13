package eu.kanade.tachiyomi.data.vault.publishing

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.transfer.UniFileVaultTransferLocalStaging
import tachiyomi.domain.storage.service.StorageManager

internal interface VaultChapterThumbnailCacheStore {
    fun localUri(key: VaultChapterThumbnailCacheKey): String?
    suspend fun write(key: VaultChapterThumbnailCacheKey, bytes: ByteArray)
    suspend fun delete(key: VaultChapterThumbnailCacheKey)
}

internal data class VaultChapterThumbnailCacheKey(
    val vaultId: Long,
    val mangaIdentity: String,
    val chapterIdentity: String,
    val thumbnailIdentity: String,
    val remotePath: String,
)

internal class DefaultVaultChapterThumbnailCacheStore(
    private val storageManager: StorageManager,
) : VaultChapterThumbnailCacheStore {

    override fun localUri(key: VaultChapterThumbnailCacheKey): String? {
        val root = storageManager.getVaultCacheDirectory() ?: return null
        return key.path()
            .pathSegments()
            .fold(root as UniFile?) { parent, segment -> parent?.findFile(segment) }
            ?.takeIf { it.isFile }
            ?.uri
            ?.toString()
    }

    override suspend fun write(key: VaultChapterThumbnailCacheKey, bytes: ByteArray) {
        val root = storageManager.getVaultCacheDirectory() ?: return
        UniFileVaultTransferLocalStaging(root).write(key.path(), bytes)
    }

    override suspend fun delete(key: VaultChapterThumbnailCacheKey) {
        val root = storageManager.getVaultCacheDirectory() ?: return
        UniFileVaultTransferLocalStaging(root).delete(key.path())
    }

    private fun VaultChapterThumbnailCacheKey.path(): String {
        val extension = remotePath.substringAfterLast('/', "")
            .substringAfterLast('.', "jpg")
            .takeIf { it.isNotBlank() }
            ?: "jpg"
        return listOf(
            vaultId.toString(),
            mangaIdentity,
            chapterIdentity,
            "thumbnails",
            "$thumbnailIdentity.$extension",
        ).joinToString("/") { it.toCachePathSegment() }
    }

    private fun String.pathSegments(): List<String> {
        return trim()
            .split('/')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun String.toCachePathSegment(): String {
        return trim()
            .replace(Regex("[/\\\\]+"), "_")
            .replace(Regex("""\A[.]+|\p{Cntrl}"""), "_")
            .ifBlank { "_" }
    }
}

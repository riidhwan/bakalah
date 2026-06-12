package eu.kanade.tachiyomi.data.vault

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.importing.FileDigest
import eu.kanade.tachiyomi.data.vault.importing.childPath
import eu.kanade.tachiyomi.data.vault.importing.digest
import eu.kanade.tachiyomi.data.vault.importing.toHex
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultManifestCover
import tachiyomi.domain.vault.model.WebDavVaultConfig
import java.security.MessageDigest
import java.util.UUID

internal interface VaultContentUploadStorage {
    suspend fun putFile(path: String, file: UniFile): Boolean
    suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): Boolean
    suspend fun createDirectory(path: String): Boolean
}

internal class VaultContentUploader {
    suspend fun uploadChapterFile(
        storage: VaultContentUploadStorage,
        config: WebDavVaultConfig,
        mangaIdentity: String,
        contentIdentity: String,
        chapterFile: UniFile,
        remoteFileName: String = defaultRemoteFileName(chapterFile),
    ): String {
        val basePath = "content/$mangaIdentity/$contentIdentity"
        storage.createDirectory(config.rootPath.childPath("content"))
        storage.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        storage.createDirectory(config.rootPath.childPath(basePath))

        val path = "$basePath/$remoteFileName"
        if (!storage.putFile(config.rootPath.childPath(path), chapterFile)) {
            throw VaultContentUploadFailure("chapter_upload")
        }
        return path
    }

    suspend fun uploadCover(
        storage: VaultContentUploadStorage,
        config: WebDavVaultConfig,
        mangaIdentity: String,
        cover: VaultUploadCover,
        now: Long,
    ): VaultManifestCover {
        val coverIdentity = UUID.randomUUID().toString()
        val path = "content/$mangaIdentity/cover/$coverIdentity.${cover.extension.normalizedCoverExtension()}"
        val digest = cover.digest()

        storage.createDirectory(config.rootPath.childPath("content"))
        storage.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        storage.createDirectory(config.rootPath.childPath("content/$mangaIdentity/cover"))
        val uploaded = when (cover) {
            is VaultUploadCover.File -> storage.putFile(config.rootPath.childPath(path), cover.file)
            is VaultUploadCover.Bytes -> storage.putBytes(config.rootPath.childPath(path), cover.bytes, cover.mediaType)
        }
        if (!uploaded) {
            throw VaultContentUploadFailure("cover_upload")
        }

        return VaultManifestCover(
            identity = coverIdentity,
            path = path,
            mediaType = cover.mediaType,
            integrity = VaultContentIntegrity(
                sizeBytes = digest.sizeBytes,
                checksumSha256 = digest.sha256,
            ),
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = 1,
            updatedAt = now,
        )
    }

    private fun defaultRemoteFileName(file: UniFile): String {
        val extension = file.extension?.let { ".$it" }.orEmpty()
        return "${file.nameWithoutExtension}$extension"
    }

    private fun VaultUploadCover.digest(): FileDigest {
        return when (this) {
            is VaultUploadCover.File -> file.digest()
            is VaultUploadCover.Bytes -> bytes.digest()
        }
    }

    private fun ByteArray.digest(): FileDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(this)
        return FileDigest(size.toLong(), digest.digest().toHex())
    }

    private fun String?.normalizedCoverExtension(): String {
        return this
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
            ?: "img"
    }
}

internal sealed interface VaultUploadCover {
    val extension: String?
    val mediaType: String?

    data class File(
        val file: UniFile,
        override val extension: String?,
        override val mediaType: String?,
    ) : VaultUploadCover

    data class Bytes(
        val bytes: ByteArray,
        override val extension: String?,
        override val mediaType: String?,
    ) : VaultUploadCover
}

internal class VaultContentUploadFailure(
    val category: String,
) : RuntimeException(category)

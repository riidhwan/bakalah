package eu.kanade.tachiyomi.data.vault.publishing

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.remote.childPath
import eu.kanade.tachiyomi.data.vault.staging.FileDigest
import eu.kanade.tachiyomi.data.vault.staging.digest
import eu.kanade.tachiyomi.data.vault.staging.toHex
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultManifestCover
import tachiyomi.domain.vault.model.WebDavVaultConfig
import java.security.MessageDigest
import java.util.UUID

internal interface VaultContentUploadStorage {
    suspend fun getBytes(path: String): ByteArray?
    suspend fun putFile(path: String, file: UniFile): Boolean
    suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): Boolean
    suspend fun createDirectory(path: String): Boolean
    suspend fun delete(path: String): Boolean
    suspend fun promote(stagedPath: String, finalPath: String): Boolean
}

internal class VaultContentUploader {
    suspend fun uploadChapterFile(
        storage: VaultContentUploadStorage,
        config: WebDavVaultConfig,
        mangaIdentity: String,
        contentIdentity: String,
        chapterFile: UniFile,
        remoteFileName: String = defaultRemoteFileName(chapterFile),
    ): VaultPromotableUpload {
        val basePath = "content/$mangaIdentity/$contentIdentity"
        val stagedPath = stagedPath(contentIdentity, remoteFileName)
        storage.createDirectory(config.rootPath.childPath("content"))
        storage.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        storage.createDirectory(config.rootPath.childPath(basePath))
        createStagingDirectories(storage, config, contentIdentity)

        val finalPath = "$basePath/$remoteFileName"
        if (!storage.putFile(config.rootPath.childPath(stagedPath), chapterFile)) {
            throw VaultContentUploadFailure("chapter_upload")
        }
        runCatching {
            verifyRemoteIntegrity(
                storage = storage,
                config = config,
                stagedPath = stagedPath,
                expected = chapterFile.digest(),
                failureCategory = "chapter_upload",
            )
        }.getOrElse { error ->
            runCatching { storage.delete(config.rootPath.childPath(stagedPath)) }
            throw error
        }
        return VaultPromotableUpload(stagedPath = stagedPath, finalPath = finalPath)
    }

    suspend fun uploadCover(
        storage: VaultContentUploadStorage,
        config: WebDavVaultConfig,
        mangaIdentity: String,
        cover: VaultUploadCover,
        now: Long,
    ): VaultUploadedCover {
        val coverIdentity = UUID.randomUUID().toString()
        val fileName = "$coverIdentity.${cover.extension.normalizedCoverExtension()}"
        val path = "content/$mangaIdentity/cover/$fileName"
        val stagedPath = stagedPath(coverIdentity, fileName)
        val digest = cover.digest()

        storage.createDirectory(config.rootPath.childPath("content"))
        storage.createDirectory(config.rootPath.childPath("content/$mangaIdentity"))
        storage.createDirectory(config.rootPath.childPath("content/$mangaIdentity/cover"))
        createStagingDirectories(storage, config, coverIdentity)
        val uploaded = when (cover) {
            is VaultUploadCover.File -> storage.putFile(config.rootPath.childPath(stagedPath), cover.file)
            is VaultUploadCover.Bytes -> storage.putBytes(
                config.rootPath.childPath(stagedPath),
                cover.bytes,
                cover.mediaType,
            )
        }
        if (!uploaded) {
            throw VaultContentUploadFailure("cover_upload")
        }
        runCatching {
            verifyRemoteIntegrity(
                storage = storage,
                config = config,
                stagedPath = stagedPath,
                expected = digest,
                failureCategory = "cover_upload",
            )
        }.getOrElse { error ->
            runCatching { storage.delete(config.rootPath.childPath(stagedPath)) }
            throw error
        }

        val manifestCover = VaultManifestCover(
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
        return VaultUploadedCover(
            cover = manifestCover,
            upload = VaultPromotableUpload(stagedPath = stagedPath, finalPath = path),
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

    private suspend fun createStagingDirectories(
        storage: VaultContentUploadStorage,
        config: WebDavVaultConfig,
        contentIdentity: String,
    ) {
        storage.createDirectory(config.rootPath.childPath(".staging"))
        storage.createDirectory(config.rootPath.childPath(".staging/add-to-vault"))
        storage.createDirectory(config.rootPath.childPath(".staging/add-to-vault/$contentIdentity"))
    }

    private fun stagedPath(contentIdentity: String, fileName: String): String {
        return ".staging/add-to-vault/$contentIdentity/${UUID.randomUUID()}-$fileName"
    }

    private suspend fun verifyRemoteIntegrity(
        storage: VaultContentUploadStorage,
        config: WebDavVaultConfig,
        stagedPath: String,
        expected: FileDigest,
        failureCategory: String,
    ) {
        val remoteBytes = storage.getBytes(config.rootPath.childPath(stagedPath))
            ?: throw VaultContentUploadFailure(failureCategory)
        val actual = remoteBytes.digest()
        if (actual != expected) {
            throw VaultContentUploadFailure(failureCategory)
        }
    }

    private fun String?.normalizedCoverExtension(): String {
        return this
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
            ?: "img"
    }
}

internal data class VaultPromotableUpload(
    val stagedPath: String,
    val finalPath: String,
)

internal data class VaultUploadedCover(
    val cover: VaultManifestCover,
    val upload: VaultPromotableUpload,
)

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

package eu.kanade.tachiyomi.data.vault

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestCover
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.net.HttpURLConnection
import java.util.UUID

class VaultCoverPublishService(
    networkHelper: NetworkHelper,
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val refreshService: VaultCatalogueRefreshService,
    private val storageManager: StorageManager,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val client = networkHelper.nonCloudflareClient
    private val codec = VaultManifestCodec(json)

    suspend fun publish(request: VaultCoverPublishRequest): VaultCoverPublishResult {
        if (request.bytes.isEmpty()) return VaultCoverPublishResult.EmptyCover
        val manga = repository.getMangaById(request.mangaId) ?: return VaultCoverPublishResult.MangaNotFound
        val config = preferences.getWebDavConfig()
        if (!config.isComplete) return VaultCoverPublishResult.IncompleteConfiguration

        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootBody = getText(config, rootPath) ?: return VaultCoverPublishResult.ManifestNotFound(rootPath)
        val root = when (val result = codec.decodeRoot(rootBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            is VaultManifestReadResult.UnsupportedOlderVersion ->
                return VaultCoverPublishResult.UnsupportedOlderVersion(result.layoutVersion)
            is VaultManifestReadResult.UnsupportedNewerVersion ->
                return VaultCoverPublishResult.UnsupportedNewerVersion(result.layoutVersion)
            VaultManifestReadResult.NotVault -> return VaultCoverPublishResult.NotVault
            is VaultManifestReadResult.Malformed -> return VaultCoverPublishResult.Malformed(rootPath)
        }

        val expectedIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
        if (expectedIdentity != null && expectedIdentity != root.identity) {
            return VaultCoverPublishResult.IdentityChanged(ContentVaultIdentity(root.identity))
        }
        val localVault = repository.getVaultByIdentity(ContentVaultIdentity(root.identity))
            ?: return VaultCoverPublishResult.VaultNotFound
        val remoteRevision = VaultRevision(root.revisionId, root.revisionNumber)
        if (localVault.rootRevision != remoteRevision) {
            return VaultCoverPublishResult.RevisionMismatch(remoteRevision)
        }

        val pointer = root.manga.firstOrNull { it.identity == manga.identity.value }
            ?: return VaultCoverPublishResult.MangaNotFound
        val manifestPath = config.rootPath.childPath(pointer.path)
        val manifestBody = getText(config, manifestPath)
            ?: return VaultCoverPublishResult.ManifestNotFound(pointer.path)
        val remoteManga = when (val result = codec.decodeManga(manifestBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            is VaultManifestReadResult.UnsupportedOlderVersion ->
                return VaultCoverPublishResult.UnsupportedOlderVersion(result.layoutVersion)
            is VaultManifestReadResult.UnsupportedNewerVersion ->
                return VaultCoverPublishResult.UnsupportedNewerVersion(result.layoutVersion)
            VaultManifestReadResult.NotVault -> return VaultCoverPublishResult.NotVault
            is VaultManifestReadResult.Malformed -> return VaultCoverPublishResult.Malformed(pointer.path)
        }
        if (remoteManga.vaultIdentity != root.identity || remoteManga.mangaIdentity != pointer.identity) {
            return VaultCoverPublishResult.IdentityMismatch(pointer.path)
        }

        val timestamp = now()
        val coverIdentity = UUID.randomUUID().toString()
        val coverRevision = (remoteManga.cover?.revisionNumber ?: 0) + 1
        val mediaType = request.mediaType
            ?.takeIf { it.startsWith("image/") }
            ?: request.bytes.detectImageMediaType()
        val extension = request.fileName?.extension()
            ?: mediaType.mediaTypeExtension()
            ?: "img"
        val coverPath = "content/${remoteManga.mangaIdentity}/cover/$coverIdentity.$extension"
        val integrity = request.bytes.vaultTransferIntegrity()

        createDirectory(config, config.rootPath.childPath("content"))
        createDirectory(config, config.rootPath.childPath("content/${remoteManga.mangaIdentity}"))
        createDirectory(config, config.rootPath.childPath("content/${remoteManga.mangaIdentity}/cover"))
        if (!putAssetStaged(config, config.rootPath.childPath(coverPath), request.bytes, mediaType, integrity)) {
            return VaultCoverPublishResult.PublishFailed
        }

        val mangaRevisionId = UUID.randomUUID().toString()
        val mangaRevisionNumber = remoteManga.revisionNumber + 1
        val updatedManga = remoteManga.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = mangaRevisionId,
            revisionNumber = mangaRevisionNumber,
            cover = VaultManifestCover(
                identity = coverIdentity,
                path = coverPath,
                mediaType = mediaType,
                integrity = VaultContentIntegrity(
                    sizeBytes = integrity.sizeBytes,
                    checksumSha256 = integrity.checksumSha256,
                ),
                revisionId = UUID.randomUUID().toString(),
                revisionNumber = coverRevision,
                updatedAt = timestamp,
            ),
            updatedAt = timestamp,
        )
        val updatedPointers = root.manga
            .map {
                if (it.identity == pointer.identity) {
                    it.copy(
                        revisionId = mangaRevisionId,
                        revisionNumber = mangaRevisionNumber,
                        updatedAt = timestamp,
                    )
                } else {
                    it
                }
            }
            .sortedBy { VaultMetadata.normalizeTitle(it.title) }
        val updatedRoot = root.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = root.revisionNumber + 1,
            updatedAt = timestamp,
            summary = VaultCatalogueSummary(
                mangaCount = root.summary.mangaCount,
                chapterCount = root.summary.chapterCount,
                labelCount = root.summary.labelCount,
                updatedAt = timestamp,
            ),
            manga = updatedPointers,
        )

        if (!putTextStaged(config, manifestPath, codec.encodeManga(updatedManga))) {
            return VaultCoverPublishResult.PublishFailed
        }
        if (!putTextStaged(config, rootPath, codec.encodeRoot(updatedRoot))) {
            return VaultCoverPublishResult.PublishFailed
        }

        return when (val refresh = refreshService.refreshConfiguredVault()) {
            is VaultCatalogueRefreshResult.Refreshed -> {
                writeCoverCache(
                    vaultId = localVault.id,
                    mangaIdentity = remoteManga.mangaIdentity,
                    coverIdentity = coverIdentity,
                    remotePath = coverPath,
                    bytes = request.bytes,
                )
                VaultCoverPublishResult.Published
            }
            VaultCatalogueRefreshResult.IncompleteConfiguration -> VaultCoverPublishResult.IncompleteConfiguration
            VaultCatalogueRefreshResult.NotVault -> VaultCoverPublishResult.NotVault
            is VaultCatalogueRefreshResult.UnsupportedOlderVersion ->
                VaultCoverPublishResult.UnsupportedOlderVersion(refresh.layoutVersion)
            is VaultCatalogueRefreshResult.UnsupportedNewerVersion ->
                VaultCoverPublishResult.UnsupportedNewerVersion(refresh.layoutVersion)
            is VaultCatalogueRefreshResult.IdentityChanged ->
                VaultCoverPublishResult.IdentityChanged(refresh.remoteIdentity)
            is VaultCatalogueRefreshResult.ManifestNotFound ->
                VaultCoverPublishResult.ManifestNotFound(refresh.manifestPath)
            is VaultCatalogueRefreshResult.IdentityMismatch ->
                VaultCoverPublishResult.IdentityMismatch(refresh.manifestPath)
            is VaultCatalogueRefreshResult.Malformed -> VaultCoverPublishResult.Malformed(refresh.manifestPath)
        }
    }

    suspend fun cacheCover(mangaId: Long): String? {
        val manga = repository.getMangaById(mangaId) ?: return null
        val cover = repository.getCoverForManga(mangaId) ?: return null
        localCoverUri(
            vaultId = manga.vaultId,
            mangaIdentity = manga.identity.value,
            coverIdentity = cover.identity.value,
            remotePath = cover.path,
        )?.let { return it }

        val config = preferences.getWebDavConfig()
        if (!config.isComplete) return null
        val bytes = getBytes(config, config.rootPath.childPath(cover.path)) ?: return null
        val integrity = bytes.vaultTransferIntegrity()
        if (cover.sizeBytes != null && cover.sizeBytes != integrity.sizeBytes) return null
        if (cover.checksumSha256 != null && cover.checksumSha256 != integrity.checksumSha256) return null
        writeCoverCache(
            vaultId = manga.vaultId,
            mangaIdentity = manga.identity.value,
            coverIdentity = cover.identity.value,
            remotePath = cover.path,
            bytes = bytes,
        )
        return localCoverUri(
            vaultId = manga.vaultId,
            mangaIdentity = manga.identity.value,
            coverIdentity = cover.identity.value,
            remotePath = cover.path,
        )
    }

    private suspend fun writeCoverCache(
        vaultId: Long,
        mangaIdentity: String,
        coverIdentity: String,
        remotePath: String,
        bytes: ByteArray,
    ) {
        val root = storageManager.getVaultCacheDirectory() ?: return
        UniFileVaultTransferLocalStaging(root).write(
            coverCachePath(vaultId, mangaIdentity, coverIdentity, remotePath),
            bytes,
        )
    }

    private fun localCoverUri(
        vaultId: Long,
        mangaIdentity: String,
        coverIdentity: String,
        remotePath: String,
    ): String? {
        val root = storageManager.getVaultCacheDirectory() ?: return null
        return coverCachePath(vaultId, mangaIdentity, coverIdentity, remotePath)
            .pathSegments()
            .fold(root as UniFile?) { parent, segment -> parent?.findFile(segment) }
            ?.takeIf { it.isFile }
            ?.uri
            ?.toString()
    }

    private fun coverCachePath(
        vaultId: Long,
        mangaIdentity: String,
        coverIdentity: String,
        remotePath: String,
    ): String {
        val extension = remotePath.substringAfterLast('/', "")
            .extension()
            ?: "img"
        return listOf(
            vaultId.toString(),
            mangaIdentity,
            "covers",
            "$coverIdentity.$extension",
        ).joinToString("/") { it.toCachePathSegment() }
    }

    private suspend fun getText(config: WebDavVaultConfig, path: String): String? = withContext(Dispatchers.IO) {
        val request = request(config, path).get().build()
        client.newCall(request).await().use { response ->
            response.takeIf { it.isSuccessful }?.body?.string()
        }
    }

    private suspend fun getBytes(config: WebDavVaultConfig, path: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = request(config, path).get().build()
        client.newCall(request).await().use { response ->
            response.takeIf { it.isSuccessful }?.body?.bytes()
        }
    }

    private suspend fun putTextStaged(config: WebDavVaultConfig, path: String, body: String): Boolean {
        val stagedPath = "$path.staged-${UUID.randomUUID()}"
        return runCatching {
            putBytes(config, stagedPath, body.toByteArray(), "application/json")
            move(config, stagedPath, path)
        }.onFailure {
            delete(config, stagedPath)
        }.isSuccess
    }

    private suspend fun putAssetStaged(
        config: WebDavVaultConfig,
        path: String,
        bytes: ByteArray,
        mediaType: String?,
        expectedIntegrity: VaultTransferIntegrity,
    ): Boolean {
        val stagedPath = "$path.staged-${UUID.randomUUID()}"
        return runCatching {
            putBytes(config, stagedPath, bytes, mediaType)
            val stagedBytes = getBytes(config, stagedPath) ?: error("staged cover missing")
            val stagedIntegrity = stagedBytes.vaultTransferIntegrity()
            check(stagedIntegrity == expectedIntegrity) { "cover integrity mismatch" }
            move(config, stagedPath, path)
        }.onFailure {
            delete(config, stagedPath)
        }.isSuccess
    }

    private suspend fun putBytes(config: WebDavVaultConfig, path: String, bytes: ByteArray, mediaType: String?) {
        val request = request(config, path)
            .put(bytes.toRequestBody(mediaType?.toMediaTypeOrNull()))
            .build()
        client.newCall(request).await().use { response ->
            check(response.isSuccessful) { "remote upload failed with ${response.code}" }
        }
    }

    private suspend fun move(config: WebDavVaultConfig, stagedPath: String, finalPath: String) {
        val request = request(config, stagedPath)
            .header("Destination", config.serverUrl.resolveWebDavPath(finalPath).toString())
            .header("Overwrite", "T")
            .method("MOVE", ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).await().use { response ->
            check(
                response.isSuccessful ||
                    response.code == HttpURLConnection.HTTP_CREATED ||
                    response.code == HttpURLConnection.HTTP_NO_CONTENT,
            ) { "remote promote failed with ${response.code}" }
        }
    }

    private suspend fun createDirectory(config: WebDavVaultConfig, path: String) {
        val request = request(config, path, collection = true)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).await().use { response ->
            check(response.isSuccessful || response.code == HttpURLConnection.HTTP_BAD_METHOD) {
                "remote directory create failed with ${response.code}"
            }
        }
    }

    private suspend fun delete(config: WebDavVaultConfig, path: String) {
        val request = request(config, path).delete().build()
        client.newCall(request).await().use { }
    }

    private fun request(
        config: WebDavVaultConfig,
        path: String,
        collection: Boolean = false,
    ): Request.Builder {
        return Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path, collection = collection))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
    }

    private fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')

    private fun String?.mediaTypeExtension(): String? {
        return when (this) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> null
        }
    }

    private fun ByteArray.detectImageMediaType(): String? {
        return when {
            size >= 3 &&
                this[0] == 0xFF.toByte() &&
                this[1] == 0xD8.toByte() &&
                this[2] == 0xFF.toByte() -> "image/jpeg"
            size >= 8 &&
                this[0] == 0x89.toByte() &&
                this[1] == 0x50.toByte() &&
                this[2] == 0x4E.toByte() &&
                this[3] == 0x47.toByte() -> "image/png"
            size >= 12 &&
                copyOfRange(0, 4).decodeToString() == "RIFF" &&
                copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
            size >= 6 &&
                (
                    copyOfRange(0, 6).decodeToString() == "GIF87a" ||
                        copyOfRange(0, 6).decodeToString() == "GIF89a"
                    ) -> "image/gif"
            else -> null
        }
    }

    private fun String.extension(): String? {
        return substringAfterLast('/', this)
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotBlank() && it.length <= 8 && it.all { char -> char.isLetterOrDigit() } }
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

data class VaultCoverPublishRequest(
    val mangaId: Long,
    val bytes: ByteArray,
    val mediaType: String?,
    val fileName: String?,
)

sealed interface VaultCoverPublishResult {
    data object Published : VaultCoverPublishResult
    data object EmptyCover : VaultCoverPublishResult
    data object IncompleteConfiguration : VaultCoverPublishResult
    data object VaultNotFound : VaultCoverPublishResult
    data object MangaNotFound : VaultCoverPublishResult
    data object NotVault : VaultCoverPublishResult
    data class UnsupportedOlderVersion(val layoutVersion: Long) : VaultCoverPublishResult
    data class UnsupportedNewerVersion(val layoutVersion: Long) : VaultCoverPublishResult
    data class IdentityChanged(val remoteIdentity: ContentVaultIdentity) : VaultCoverPublishResult
    data class RevisionMismatch(val currentRevision: VaultRevision) : VaultCoverPublishResult
    data class ManifestNotFound(val manifestPath: String) : VaultCoverPublishResult
    data class IdentityMismatch(val manifestPath: String) : VaultCoverPublishResult
    data class Malformed(val manifestPath: String) : VaultCoverPublishResult
    data object PublishFailed : VaultCoverPublishResult
}

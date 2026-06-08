package eu.kanade.tachiyomi.data.vault

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.net.HttpURLConnection
import java.util.UUID

class VaultMangaDeletionService(
    networkHelper: NetworkHelper,
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val refreshService: VaultCatalogueRefreshService,
    private val activeReaderSessions: ActiveVaultReaderSessions,
    private val storageManager: StorageManager,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val client = networkHelper.nonCloudflareClient
    private val codec = VaultManifestCodec(json)

    suspend fun delete(mangaId: Long): VaultMangaDeletionResult {
        val manga = repository.getMangaById(mangaId) ?: return VaultMangaDeletionResult.MangaNotFound
        if (activeReaderSessions.isActive(mangaId)) {
            return VaultMangaDeletionResult.BlockedByActiveReader
        }
        if (hasActiveTransfer(mangaId, manga.vaultId)) {
            return VaultMangaDeletionResult.BlockedByActiveTransfer
        }

        val config = preferences.getWebDavConfig()
        if (!config.isComplete) return VaultMangaDeletionResult.IncompleteConfiguration

        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootBody = get(config, rootPath) ?: return VaultMangaDeletionResult.ManifestNotFound(rootPath)
        val root = when (val result = codec.decodeRoot(rootBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            is VaultManifestReadResult.UnsupportedOlderVersion ->
                return VaultMangaDeletionResult.UnsupportedOlderVersion(result.layoutVersion)
            is VaultManifestReadResult.UnsupportedNewerVersion ->
                return VaultMangaDeletionResult.UnsupportedNewerVersion(result.layoutVersion)
            VaultManifestReadResult.NotVault -> return VaultMangaDeletionResult.NotVault
            is VaultManifestReadResult.Malformed -> return VaultMangaDeletionResult.Malformed(rootPath)
        }

        val expectedIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
        if (expectedIdentity != null && expectedIdentity != root.identity) {
            return VaultMangaDeletionResult.IdentityChanged(ContentVaultIdentity(root.identity))
        }
        val localVault = repository.getVaultByIdentity(ContentVaultIdentity(root.identity))
            ?: return VaultMangaDeletionResult.VaultNotFound
        val remoteRevision = VaultRevision(root.revisionId, root.revisionNumber)
        if (localVault.rootRevision != remoteRevision) {
            return VaultMangaDeletionResult.RevisionMismatch(remoteRevision)
        }

        val pointer = root.manga.firstOrNull { it.identity == manga.identity.value }
            ?: return VaultMangaDeletionResult.MangaNotFound
        val mangaPath = config.rootPath.childPath(pointer.path)
        val mangaBody = get(config, mangaPath) ?: return VaultMangaDeletionResult.ManifestNotFound(pointer.path)
        val remoteManga = when (val result = codec.decodeManga(mangaBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            is VaultManifestReadResult.UnsupportedOlderVersion ->
                return VaultMangaDeletionResult.UnsupportedOlderVersion(result.layoutVersion)
            is VaultManifestReadResult.UnsupportedNewerVersion ->
                return VaultMangaDeletionResult.UnsupportedNewerVersion(result.layoutVersion)
            VaultManifestReadResult.NotVault -> return VaultMangaDeletionResult.NotVault
            is VaultManifestReadResult.Malformed -> return VaultMangaDeletionResult.Malformed(pointer.path)
        }
        if (remoteManga.vaultIdentity != root.identity || remoteManga.mangaIdentity != pointer.identity) {
            return VaultMangaDeletionResult.IdentityMismatch(pointer.path)
        }
        if (hasActiveTransfer(mangaId, manga.vaultId, remoteManga.mangaIdentity)) {
            return VaultMangaDeletionResult.BlockedByActiveTransfer
        }

        val timestamp = now()
        val updatedPointers = root.manga
            .filterNot { it.identity == pointer.identity }
        val updatedRoot = root.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = root.revisionNumber + 1,
            updatedAt = timestamp,
            summary = VaultCatalogueSummary(
                mangaCount = updatedPointers.size.toLong(),
                chapterCount = (root.summary.chapterCount - remoteManga.chapters.size).coerceAtLeast(0),
                labelCount = root.summary.labelCount,
                updatedAt = timestamp,
            ),
            manga = updatedPointers,
        )

        if (!putStaged(config, rootPath, codec.encodeRoot(updatedRoot))) {
            return VaultMangaDeletionResult.PublishFailed
        }

        evictLocalCache(mangaId, manga.vaultId, remoteManga.mangaIdentity, remoteManga.cover?.path)

        val refreshResult = when (val refresh = refreshService.refreshConfiguredVault()) {
            is VaultCatalogueRefreshResult.Refreshed -> null
            VaultCatalogueRefreshResult.IncompleteConfiguration -> VaultMangaDeletionResult.IncompleteConfiguration
            VaultCatalogueRefreshResult.NotVault -> VaultMangaDeletionResult.NotVault
            is VaultCatalogueRefreshResult.UnsupportedOlderVersion ->
                VaultMangaDeletionResult.UnsupportedOlderVersion(refresh.layoutVersion)
            is VaultCatalogueRefreshResult.UnsupportedNewerVersion ->
                VaultMangaDeletionResult.UnsupportedNewerVersion(refresh.layoutVersion)
            is VaultCatalogueRefreshResult.IdentityChanged -> VaultMangaDeletionResult.IdentityChanged(
                refresh.remoteIdentity,
            )
            is VaultCatalogueRefreshResult.ManifestNotFound -> VaultMangaDeletionResult.ManifestNotFound(
                refresh.manifestPath,
            )
            is VaultCatalogueRefreshResult.IdentityMismatch -> VaultMangaDeletionResult.IdentityMismatch(
                refresh.manifestPath,
            )
            is VaultCatalogueRefreshResult.Malformed -> VaultMangaDeletionResult.Malformed(refresh.manifestPath)
        }
        refreshResult?.let { return it }

        repository.deleteMangaLocalState(mangaId)

        val cleanupFailures = cleanupRemoteFiles(
            config = config,
            paths = buildList {
                add(pointer.path)
                addAll(remoteManga.chapters.map { it.content.path })
                remoteManga.cover?.path?.let(::add)
            },
        )
        return if (cleanupFailures.isEmpty()) {
            VaultMangaDeletionResult.Deleted(manga.vaultId)
        } else {
            VaultMangaDeletionResult.DeletedWithCleanupFailures(
                vaultId = manga.vaultId,
                failedPaths = cleanupFailures,
            )
        }
    }

    private suspend fun hasActiveTransfer(
        mangaId: Long,
        vaultId: Long,
        mangaIdentity: String? = null,
    ): Boolean {
        val chapterIds = repository.getChapters(mangaId).map { it.id }.toSet()
        return repository.getTransferJobsForVault(vaultId).any { job ->
            job.state in ACTIVE_TRANSFER_STATES &&
                (
                    job.chapterId in chapterIds ||
                        mangaIdentity?.let { identity ->
                            job.remotePath?.contains("content/$identity/") == true
                        } == true
                    )
        }
    }

    private suspend fun get(config: WebDavVaultConfig, path: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
            .get()
            .build()
        client.newCall(request).await().use { response ->
            response.takeIf { it.isSuccessful }?.body?.string()
        }
    }

    private suspend fun putStaged(config: WebDavVaultConfig, path: String, body: String): Boolean = withContext(
        Dispatchers.IO,
    ) {
        val stagedPath = "$path.staged-${UUID.randomUUID()}"
        runCatching {
            put(config, stagedPath, body)
            move(config, stagedPath, path)
        }.onFailure {
            delete(config, stagedPath)
        }.isSuccess
    }

    private suspend fun put(config: WebDavVaultConfig, path: String, body: String) {
        val request = Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
            .put(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).await().use { response ->
            check(response.isSuccessful) { "remote upload failed with ${response.code}" }
        }
    }

    private suspend fun move(config: WebDavVaultConfig, stagedPath: String, finalPath: String) {
        val request = Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(stagedPath))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
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

    private suspend fun cleanupRemoteFiles(config: WebDavVaultConfig, paths: List<String>): List<String> {
        return paths.distinct().filterNot { delete(config, config.rootPath.childPath(it)) }
    }

    private suspend fun evictLocalCache(
        mangaId: Long,
        vaultId: Long,
        mangaIdentity: String,
        coverPath: String?,
    ) {
        val root = storageManager.getVaultCacheDirectory() ?: return
        val localStaging = UniFileVaultTransferLocalStaging(root)
        VaultCachePolicyService(
            repository = repository,
            localStaging = localStaging,
            preferences = preferences,
        ).evictManga(mangaId)
        coverPath?.let {
            localStaging.delete(
                listOf(
                    vaultId.toString(),
                    mangaIdentity,
                    "covers",
                    it.substringAfterLast('/', "cover.img"),
                ).joinToString("/") { segment -> segment.toCachePathSegment() },
            )
        }
    }

    private suspend fun delete(config: WebDavVaultConfig, path: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
            .delete()
            .build()
        client.newCall(request).await().use { response ->
            response.isSuccessful ||
                response.code == HttpURLConnection.HTTP_NOT_FOUND ||
                response.code == HttpURLConnection.HTTP_GONE
        }
    }

    private fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')

    private fun String.toCachePathSegment(): String {
        return replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .trim('_')
            .takeIf { it.isNotBlank() }
            ?: "item"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val ACTIVE_TRANSFER_STATES = setOf(VaultTransferState.QUEUED, VaultTransferState.RUNNING)
    }
}

sealed interface VaultMangaDeletionResult {
    data class Deleted(val vaultId: Long) : VaultMangaDeletionResult
    data class DeletedWithCleanupFailures(
        val vaultId: Long,
        val failedPaths: List<String>,
    ) : VaultMangaDeletionResult
    data object BlockedByActiveTransfer : VaultMangaDeletionResult
    data object BlockedByActiveReader : VaultMangaDeletionResult
    data object IncompleteConfiguration : VaultMangaDeletionResult
    data object VaultNotFound : VaultMangaDeletionResult
    data object MangaNotFound : VaultMangaDeletionResult
    data object NotVault : VaultMangaDeletionResult
    data class UnsupportedOlderVersion(val layoutVersion: Long) : VaultMangaDeletionResult
    data class UnsupportedNewerVersion(val layoutVersion: Long) : VaultMangaDeletionResult
    data class IdentityChanged(val remoteIdentity: ContentVaultIdentity) : VaultMangaDeletionResult
    data class RevisionMismatch(val currentRevision: VaultRevision) : VaultMangaDeletionResult
    data class ManifestNotFound(val manifestPath: String) : VaultMangaDeletionResult
    data class IdentityMismatch(val manifestPath: String) : VaultMangaDeletionResult
    data class Malformed(val manifestPath: String) : VaultMangaDeletionResult
    data object PublishFailed : VaultMangaDeletionResult
}

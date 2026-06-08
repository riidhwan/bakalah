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
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestLabel
import tachiyomi.domain.vault.model.VaultManifestMetadata
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.net.HttpURLConnection
import java.util.UUID

class VaultMetadataPublishService(
    networkHelper: NetworkHelper,
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val refreshService: VaultCatalogueRefreshService,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val client = networkHelper.nonCloudflareClient
    private val codec = VaultManifestCodec(json)

    suspend fun publish(request: VaultMetadataPublishRequest): VaultMetadataPublishResult {
        val title = request.title.trim()
        if (title.isBlank()) return VaultMetadataPublishResult.TitleRequired

        val manga = repository.getMangaById(request.mangaId) ?: return VaultMetadataPublishResult.MangaNotFound
        val config = preferences.getWebDavConfig()
        if (!config.isComplete) return VaultMetadataPublishResult.IncompleteConfiguration

        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootBody = get(config, rootPath) ?: return VaultMetadataPublishResult.ManifestNotFound(rootPath)
        val root = when (val result = codec.decodeRoot(rootBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            is VaultManifestReadResult.UnsupportedOlderVersion ->
                return VaultMetadataPublishResult.UnsupportedOlderVersion(result.layoutVersion)
            is VaultManifestReadResult.UnsupportedNewerVersion ->
                return VaultMetadataPublishResult.UnsupportedNewerVersion(result.layoutVersion)
            VaultManifestReadResult.NotVault -> return VaultMetadataPublishResult.NotVault
            is VaultManifestReadResult.Malformed -> return VaultMetadataPublishResult.Malformed(rootPath)
        }

        val expectedIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
        if (expectedIdentity != null && expectedIdentity != root.identity) {
            return VaultMetadataPublishResult.IdentityChanged(ContentVaultIdentity(root.identity))
        }
        val localVault = repository.getVaultByIdentity(ContentVaultIdentity(root.identity))
            ?: return VaultMetadataPublishResult.VaultNotFound
        val remoteRevision = VaultRevision(root.revisionId, root.revisionNumber)
        if (localVault.rootRevision != remoteRevision) {
            return VaultMetadataPublishResult.RevisionMismatch(remoteRevision)
        }

        val pointer = root.manga.firstOrNull { it.identity == manga.identity.value }
            ?: return VaultMetadataPublishResult.MangaNotFound
        val remoteManifests = root.manga.associateWith { manifestPointer ->
            val path = config.rootPath.childPath(manifestPointer.path)
            val body = get(config, path) ?: return VaultMetadataPublishResult.ManifestNotFound(manifestPointer.path)
            val manifest = when (val result = codec.decodeManga(body)) {
                is VaultManifestReadResult.Success -> result.manifest
                is VaultManifestReadResult.UnsupportedOlderVersion ->
                    return VaultMetadataPublishResult.UnsupportedOlderVersion(result.layoutVersion)
                is VaultManifestReadResult.UnsupportedNewerVersion ->
                    return VaultMetadataPublishResult.UnsupportedNewerVersion(result.layoutVersion)
                VaultManifestReadResult.NotVault -> return VaultMetadataPublishResult.NotVault
                is VaultManifestReadResult.Malformed -> return VaultMetadataPublishResult.Malformed(
                    manifestPointer.path,
                )
            }
            if (manifest.vaultIdentity != root.identity || manifest.mangaIdentity != manifestPointer.identity) {
                return VaultMetadataPublishResult.IdentityMismatch(manifestPointer.path)
            }
            manifest
        }
        val remoteManga = remoteManifests[pointer] ?: return VaultMetadataPublishResult.MangaNotFound

        val timestamp = now()
        val mangaRevisionId = UUID.randomUUID().toString()
        val mangaRevisionNumber = remoteManga.revisionNumber + 1
        val metadata = VaultManifestMetadata(
            title = title,
            author = request.author.trimToNull(),
            artist = request.artist.trimToNull(),
            description = request.description.trimToNull(),
            status = request.status,
        )
        val labels = buildLabels(
            requestedNames = request.labelNames,
            existingLabels = remoteManifests.values.flatMap { it.labels },
            now = timestamp,
        )
        val updatedManga = remoteManga.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = mangaRevisionId,
            revisionNumber = mangaRevisionNumber,
            metadata = metadata,
            labels = labels,
            updatedAt = timestamp,
        )

        val updatedPointers = root.manga
            .map {
                if (it.identity == pointer.identity) {
                    it.copy(
                        title = title,
                        revisionId = mangaRevisionId,
                        revisionNumber = mangaRevisionNumber,
                        updatedAt = timestamp,
                    )
                } else {
                    it
                }
            }
            .sortedBy { VaultMetadata.normalizeTitle(it.title) }
        val activeManifests = remoteManifests.map { (manifestPointer, manifest) ->
            if (manifestPointer.identity == pointer.identity) {
                updatedManga
            } else {
                manifest
            }
        }
        val updatedRoot = root.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = root.revisionNumber + 1,
            updatedAt = timestamp,
            summary = VaultCatalogueSummary(
                mangaCount = updatedPointers.size.toLong(),
                chapterCount = activeManifests.sumOf { it.chapters.size }.toLong(),
                labelCount = activeManifests.flatMap { it.labels }.distinctBy { it.identity }.size.toLong(),
                updatedAt = timestamp,
            ),
            manga = updatedPointers,
        )

        if (!putStaged(config, config.rootPath.childPath(pointer.path), codec.encodeManga(updatedManga))) {
            return VaultMetadataPublishResult.PublishFailed
        }
        if (!putStaged(config, rootPath, codec.encodeRoot(updatedRoot))) {
            return VaultMetadataPublishResult.PublishFailed
        }

        return when (val refresh = refreshService.refreshConfiguredVault()) {
            is VaultCatalogueRefreshResult.Refreshed -> VaultMetadataPublishResult.Published
            VaultCatalogueRefreshResult.IncompleteConfiguration -> VaultMetadataPublishResult.IncompleteConfiguration
            VaultCatalogueRefreshResult.NotVault -> VaultMetadataPublishResult.NotVault
            is VaultCatalogueRefreshResult.UnsupportedOlderVersion ->
                VaultMetadataPublishResult.UnsupportedOlderVersion(refresh.layoutVersion)
            is VaultCatalogueRefreshResult.UnsupportedNewerVersion ->
                VaultMetadataPublishResult.UnsupportedNewerVersion(refresh.layoutVersion)
            is VaultCatalogueRefreshResult.IdentityChanged ->
                VaultMetadataPublishResult.IdentityChanged(refresh.remoteIdentity)
            is VaultCatalogueRefreshResult.ManifestNotFound ->
                VaultMetadataPublishResult.ManifestNotFound(refresh.manifestPath)
            is VaultCatalogueRefreshResult.IdentityMismatch ->
                VaultMetadataPublishResult.IdentityMismatch(refresh.manifestPath)
            is VaultCatalogueRefreshResult.Malformed -> VaultMetadataPublishResult.Malformed(refresh.manifestPath)
        }
    }

    private fun buildLabels(
        requestedNames: List<String>,
        existingLabels: List<VaultManifestLabel>,
        now: Long,
    ): List<VaultManifestLabel> {
        val existingBySortKey = existingLabels.associateBy { it.sortKey }
        return requestedNames
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinctBy { VaultMetadata.normalizeTitle(it) }
            .map { name ->
                val sortKey = VaultMetadata.normalizeTitle(name)
                existingBySortKey[sortKey]
                    ?.copy(
                        name = name,
                        sortKey = sortKey,
                        updatedAt = now,
                    )
                    ?: VaultManifestLabel(
                        identity = UUID.randomUUID().toString(),
                        name = name,
                        sortKey = sortKey,
                        createdAt = now,
                        updatedAt = now,
                    )
            }
            .sortedBy { it.sortKey }
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

    private suspend fun delete(config: WebDavVaultConfig, path: String) {
        val request = Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
            .delete()
            .build()
        client.newCall(request).await().use { }
    }

    private fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')

    private fun String.trimToNull(): String? = trim().takeIf(String::isNotBlank)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

data class VaultMetadataPublishRequest(
    val mangaId: Long,
    val title: String,
    val author: String,
    val artist: String,
    val description: String,
    val status: VaultMangaStatus,
    val labelNames: List<String>,
)

sealed interface VaultMetadataPublishResult {
    data object Published : VaultMetadataPublishResult
    data object TitleRequired : VaultMetadataPublishResult
    data object IncompleteConfiguration : VaultMetadataPublishResult
    data object VaultNotFound : VaultMetadataPublishResult
    data object MangaNotFound : VaultMetadataPublishResult
    data object NotVault : VaultMetadataPublishResult
    data class UnsupportedOlderVersion(val layoutVersion: Long) : VaultMetadataPublishResult
    data class UnsupportedNewerVersion(val layoutVersion: Long) : VaultMetadataPublishResult
    data class IdentityChanged(val remoteIdentity: ContentVaultIdentity) : VaultMetadataPublishResult
    data class RevisionMismatch(val currentRevision: VaultRevision) : VaultMetadataPublishResult
    data class ManifestNotFound(val manifestPath: String) : VaultMetadataPublishResult
    data class IdentityMismatch(val manifestPath: String) : VaultMetadataPublishResult
    data class Malformed(val manifestPath: String) : VaultMetadataPublishResult
    data object PublishFailed : VaultMetadataPublishResult
}

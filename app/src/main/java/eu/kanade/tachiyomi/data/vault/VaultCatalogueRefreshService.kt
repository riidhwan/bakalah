package eu.kanade.tachiyomi.data.vault

import eu.kanade.tachiyomi.data.vault.importing.childPath
import eu.kanade.tachiyomi.data.vault.importing.resolveWebDavPath
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.Request
import tachiyomi.domain.vault.interactor.BuildVaultCatalogueRefresh
import tachiyomi.domain.vault.interactor.VaultCatalogueRefreshBuildResult
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences

interface VaultCatalogueRefresher {
    suspend fun refreshConfiguredVault(): VaultCatalogueRefreshResult
}

class VaultCatalogueRefreshService(
    networkHelper: NetworkHelper,
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
) : VaultCatalogueRefresher {
    private val client = networkHelper.nonCloudflareClient
    private val codec = VaultManifestCodec(json)
    private val refreshBuilder = BuildVaultCatalogueRefresh(codec)

    override suspend fun refreshConfiguredVault(): VaultCatalogueRefreshResult {
        val config = preferences.getWebDavConfig()
        if (!config.isComplete) return VaultCatalogueRefreshResult.IncompleteConfiguration

        val rootManifestPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootManifestBody =
            get(config, rootManifestPath) ?: return VaultCatalogueRefreshResult.ManifestNotFound(rootManifestPath)
        val rootManifest = when (val result = codec.decodeRoot(rootManifestBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            is VaultManifestReadResult.UnsupportedOlderVersion ->
                return VaultCatalogueRefreshResult.UnsupportedOlderVersion(result.layoutVersion)
            is VaultManifestReadResult.UnsupportedNewerVersion ->
                return VaultCatalogueRefreshResult.UnsupportedNewerVersion(result.layoutVersion)
            VaultManifestReadResult.NotVault -> return VaultCatalogueRefreshResult.NotVault
            is VaultManifestReadResult.Malformed -> return VaultCatalogueRefreshResult.Malformed(rootManifestPath)
        }

        val expectedIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
        if (expectedIdentity != null && expectedIdentity != rootManifest.identity) {
            return VaultCatalogueRefreshResult.IdentityChanged(ContentVaultIdentity(rootManifest.identity))
        }

        val mangaManifestBodies = mutableMapOf<String, String>()
        rootManifest.manga.forEach { pointer ->
            mangaManifestBodies[pointer.path] = get(config, config.rootPath.childPath(pointer.path))
                ?: return VaultCatalogueRefreshResult.ManifestNotFound(pointer.path)
        }

        val existingVault = repository.getVaultByIdentity(ContentVaultIdentity(rootManifest.identity))
        val buildResult = refreshBuilder.build(
            rootManifestPath = rootManifestPath,
            rootManifestBody = rootManifestBody,
            mangaManifestBodies = mangaManifestBodies,
            existingVault = existingVault,
            fetchedAt = System.currentTimeMillis(),
        )

        val refresh = when (buildResult) {
            is VaultCatalogueRefreshBuildResult.Success -> buildResult.refresh
            VaultCatalogueRefreshBuildResult.NotVault -> return VaultCatalogueRefreshResult.NotVault
            is VaultCatalogueRefreshBuildResult.UnsupportedOlderVersion ->
                return VaultCatalogueRefreshResult.UnsupportedOlderVersion(buildResult.layoutVersion)
            is VaultCatalogueRefreshBuildResult.UnsupportedNewerVersion ->
                return VaultCatalogueRefreshResult.UnsupportedNewerVersion(buildResult.layoutVersion)
            is VaultCatalogueRefreshBuildResult.MissingMangaManifest ->
                return VaultCatalogueRefreshResult.ManifestNotFound(buildResult.manifestPath)
            is VaultCatalogueRefreshBuildResult.IdentityMismatch ->
                return VaultCatalogueRefreshResult.IdentityMismatch(buildResult.manifestPath)
            is VaultCatalogueRefreshBuildResult.Malformed -> return VaultCatalogueRefreshResult.Malformed(
                buildResult.manifestPath,
            )
        }

        repository.refreshCatalogue(refresh)
        return VaultCatalogueRefreshResult.Refreshed(
            identity = refresh.vault.identity,
            mangaCount = refresh.activeManga.size,
            chapterCount = refresh.activeManga.sumOf { it.chapters.size },
        )
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

    private fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')
}

sealed interface VaultCatalogueRefreshResult {
    data object IncompleteConfiguration : VaultCatalogueRefreshResult
    data object NotVault : VaultCatalogueRefreshResult
    data class UnsupportedOlderVersion(val layoutVersion: Long) : VaultCatalogueRefreshResult
    data class UnsupportedNewerVersion(val layoutVersion: Long) : VaultCatalogueRefreshResult
    data class IdentityChanged(val remoteIdentity: ContentVaultIdentity) : VaultCatalogueRefreshResult
    data class ManifestNotFound(val manifestPath: String) : VaultCatalogueRefreshResult
    data class IdentityMismatch(val manifestPath: String) : VaultCatalogueRefreshResult
    data class Malformed(val manifestPath: String) : VaultCatalogueRefreshResult
    data class Refreshed(
        val identity: ContentVaultIdentity,
        val mangaCount: Int,
        val chapterCount: Int,
    ) : VaultCatalogueRefreshResult
}

package eu.kanade.tachiyomi.data.vault.refresh

import eu.kanade.tachiyomi.data.vault.localimport.childPath
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorage
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.remote.getTextOrNull
import eu.kanade.tachiyomi.data.vault.remote.webdav.WebDavVaultRemoteStorageFactory
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import tachiyomi.domain.vault.interactor.BuildVaultCatalogueRefresh
import tachiyomi.domain.vault.interactor.VaultCatalogueRefreshBuildResult
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences

interface VaultCatalogueRefresher {
    suspend fun refreshConfiguredVault(): VaultCatalogueRefreshResult
}

class VaultCatalogueRefreshService internal constructor(
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val remoteStorageFactory: VaultRemoteStorageFactory,
) : VaultCatalogueRefresher {
    constructor(
        networkHelper: NetworkHelper,
        json: Json,
        repository: VaultRepository,
        preferences: ContentVaultPreferences,
    ) : this(
        json = json,
        repository = repository,
        preferences = preferences,
        remoteStorageFactory = WebDavVaultRemoteStorageFactory(networkHelper),
    )

    private val codec = VaultManifestCodec(json)
    private val refreshBuilder = BuildVaultCatalogueRefresh(codec)

    override suspend fun refreshConfiguredVault(): VaultCatalogueRefreshResult {
        val config = preferences.getWebDavConfig()
        if (!config.isComplete) return VaultCatalogueRefreshResult.IncompleteConfiguration

        val rootManifestPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val remoteStorage = remoteStorageFactory.create(config)
        val rootManifestBody =
            remoteStorage.get(rootManifestPath) ?: return VaultCatalogueRefreshResult.ManifestNotFound(rootManifestPath)
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
            mangaManifestBodies[pointer.path] = remoteStorage.get(config.rootPath.childPath(pointer.path))
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

    private suspend fun VaultRemoteStorage.get(path: String): String? = getTextOrNull(path)

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

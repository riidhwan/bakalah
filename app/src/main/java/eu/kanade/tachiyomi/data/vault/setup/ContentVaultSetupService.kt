package eu.kanade.tachiyomi.data.vault.setup

import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteListResult
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorage
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteWriteResult
import eu.kanade.tachiyomi.data.vault.remote.getTextOrNull
import kotlinx.serialization.json.Json
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultRootManifest
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

internal class ContentVaultSetupService(
    private val json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val remoteStorageFactory: VaultRemoteStorageFactory,
) {
    private val manifestCodec = VaultManifestCodec(json)

    suspend fun validate(config: WebDavVaultConfig, initializeEmptyRoot: Boolean): ContentVaultSetupResult {
        if (!config.isComplete) return ContentVaultSetupResult.IncompleteConfiguration

        return runCatching {
            val remoteStorage = remoteStorageFactory.create(config)
            val children = when (val listResult = remoteStorage.list(config.rootPath)) {
                is VaultRemoteListResult.Entries -> listResult.entries
                VaultRemoteListResult.NotFound -> {
                    if (initializeEmptyRoot) {
                        if (remoteStorage.createDirectory(config.rootPath) is VaultRemoteWriteResult.Success) {
                            emptyList()
                        } else {
                            return@runCatching ContentVaultSetupResult.ConnectionFailed
                        }
                    } else {
                        return@runCatching ContentVaultSetupResult.EmptyRoot
                    }
                }
                is VaultRemoteListResult.Unauthorized -> return@runCatching ContentVaultSetupResult.ConnectionFailed
                is VaultRemoteListResult.Failed -> return@runCatching ContentVaultSetupResult.ConnectionFailed
            }

            val manifestPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
            val manifestEntry = children.firstOrNull { it.path.matchesWebDavPath(manifestPath) }
            when {
                manifestEntry != null -> connectExisting(config, remoteStorage, manifestPath)
                children.isEmpty() && initializeEmptyRoot -> initialize(config, remoteStorage, manifestPath)
                children.isEmpty() -> ContentVaultSetupResult.EmptyRoot
                else -> ContentVaultSetupResult.NonVaultRoot
            }
        }.getOrElse { ContentVaultSetupResult.ConnectionFailed }
    }

    suspend fun testConnection(config: WebDavVaultConfig): ContentVaultConnectionTestResult {
        if (!config.hasConnectionDetails) return ContentVaultConnectionTestResult.IncompleteConfiguration
        return runCatching {
            val remoteStorage = remoteStorageFactory.create(config)
            when (val result = remoteStorage.list("")) {
                is VaultRemoteListResult.Entries -> ContentVaultConnectionTestResult.Connected
                VaultRemoteListResult.NotFound -> ContentVaultConnectionTestResult.Failed(
                    HttpURLConnection.HTTP_NOT_FOUND,
                )
                is VaultRemoteListResult.Unauthorized -> {
                    if (config.rootPath.isNotBlank()) {
                        remoteStorage.list(config.rootPath).toConnectionTestResult()
                    } else {
                        ContentVaultConnectionTestResult.Unauthorized(result.statusCode)
                    }
                }
                is VaultRemoteListResult.Failed -> ContentVaultConnectionTestResult.Failed(result.statusCode)
            }
        }.getOrElse {
            ContentVaultConnectionTestResult.Failed(statusCode = null, detail = it.toConnectionFailureDetail())
        }
    }

    private fun VaultRemoteListResult.toConnectionTestResult(): ContentVaultConnectionTestResult {
        return when (this) {
            is VaultRemoteListResult.Entries -> ContentVaultConnectionTestResult.Connected
            VaultRemoteListResult.NotFound -> ContentVaultConnectionTestResult.Failed(HttpURLConnection.HTTP_NOT_FOUND)
            is VaultRemoteListResult.Unauthorized -> ContentVaultConnectionTestResult.Unauthorized(statusCode)
            is VaultRemoteListResult.Failed -> ContentVaultConnectionTestResult.Failed(statusCode)
        }
    }

    private fun Throwable.toConnectionFailureDetail(): String {
        return when (this) {
            is UnknownHostException -> "unknown host"
            is SocketTimeoutException -> "timeout"
            is SSLException -> "TLS error: ${message.orEmpty().take(MAX_FAILURE_DETAIL_LENGTH)}"
            is IllegalArgumentException -> "invalid URL"
            else -> message?.take(MAX_FAILURE_DETAIL_LENGTH) ?: this::class.simpleName ?: "network error"
        }
    }

    private suspend fun connectExisting(
        config: WebDavVaultConfig,
        remoteStorage: VaultRemoteStorage,
        manifestPath: String,
    ): ContentVaultSetupResult {
        val manifest = remoteStorage.getTextOrNull(manifestPath)
            ?.let { body ->
                when (val result = manifestCodec.decodeRoot(body)) {
                    is VaultManifestReadResult.Success -> result.manifest
                    else -> null
                }
            }
            ?: return ContentVaultSetupResult.InvalidManifest

        val now = System.currentTimeMillis()
        val identity = ContentVaultIdentity(manifest.identity)
        val vault = repository.getVaultByIdentity(identity)
        val previousIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
        if (previousIdentity != null && previousIdentity != identity.value) {
            return ContentVaultSetupResult.IdentityChanged(identity)
        }

        repository.upsertVault(
            ContentVault(
                id = vault?.id ?: -1,
                identity = identity,
                displayName = manifest.displayName,
                layoutVersion = manifest.layoutVersion,
                rootRevision = VaultRevision(manifest.revisionId, manifest.revisionNumber),
                writerId = manifest.writerId,
                lastCatalogueRefreshAt = vault?.lastCatalogueRefreshAt,
                createdAt = vault?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        preferences.setWebDavConfig(config, identity)
        return ContentVaultSetupResult.Connected(identity, manifest.displayName)
    }

    private suspend fun initialize(
        config: WebDavVaultConfig,
        remoteStorage: VaultRemoteStorage,
        manifestPath: String,
    ): ContentVaultSetupResult {
        val now = System.currentTimeMillis()
        val identity = ContentVaultIdentity(UUID.randomUUID().toString())
        val manifest = VaultRootManifest(
            identity = identity.value,
            displayName = preferences.newVaultDisplayName.get().trim().ifBlank { DEFAULT_DISPLAY_NAME },
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = VaultRevision.initial().id,
            revisionNumber = VaultRevision.initial().number,
            writerId = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now,
        )

        if (remoteStorage.putText(
                manifestPath,
                manifestCodec.encodeRoot(manifest),
            ) !is VaultRemoteWriteResult.Success
        ) {
            return ContentVaultSetupResult.ConnectionFailed
        }

        repository.upsertVault(
            ContentVault.create(
                identity = identity,
                displayName = manifest.displayName,
                layoutVersion = manifest.layoutVersion,
                now = now,
                writerId = manifest.writerId,
            ),
        )
        preferences.setWebDavConfig(config, identity)
        return ContentVaultSetupResult.Initialized(identity, manifest.displayName)
    }

    private fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')

    private fun String.matchesWebDavPath(path: String): Boolean {
        val cleanSelf = trimEnd('/')
        val cleanPath = path.trimEnd('/')
        return cleanSelf == cleanPath || cleanSelf.endsWith("/$cleanPath")
    }

    companion object {
        private const val DEFAULT_DISPLAY_NAME = "Content Vault"
        private const val MAX_FAILURE_DETAIL_LENGTH = 96
    }
}

sealed interface ContentVaultSetupResult {
    data object IncompleteConfiguration : ContentVaultSetupResult
    data object ConnectionFailed : ContentVaultSetupResult
    data object EmptyRoot : ContentVaultSetupResult
    data object NonVaultRoot : ContentVaultSetupResult
    data object InvalidManifest : ContentVaultSetupResult
    data class IdentityChanged(val remoteIdentity: ContentVaultIdentity) : ContentVaultSetupResult
    data class Initialized(val identity: ContentVaultIdentity, val displayName: String) : ContentVaultSetupResult
    data class Connected(val identity: ContentVaultIdentity, val displayName: String) : ContentVaultSetupResult
}

sealed interface ContentVaultConnectionTestResult {
    data object Connected : ContentVaultConnectionTestResult
    data object IncompleteConfiguration : ContentVaultConnectionTestResult
    data class Unauthorized(val statusCode: Int) : ContentVaultConnectionTestResult
    data class Failed(val statusCode: Int?, val detail: String? = null) : ContentVaultConnectionTestResult
}

package eu.kanade.tachiyomi.data.vault.setup

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
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
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

class ContentVaultSetupService(
    networkHelper: NetworkHelper,
    private val json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
) {
    private val client = networkHelper.nonCloudflareClient
    private val manifestCodec = VaultManifestCodec(json)

    suspend fun validate(config: WebDavVaultConfig, initializeEmptyRoot: Boolean): ContentVaultSetupResult {
        if (!config.isComplete) return ContentVaultSetupResult.IncompleteConfiguration

        return runCatching {
            val webDav = WebDavClient(config)
            val children = when (val listResult = webDav.list(config.rootPath)) {
                is WebDavListResult.Entries -> listResult.entries
                WebDavListResult.NotFound -> {
                    if (initializeEmptyRoot) {
                        if (webDav.createDirectory(config.rootPath)) {
                            emptyList()
                        } else {
                            return@runCatching ContentVaultSetupResult.ConnectionFailed
                        }
                    } else {
                        return@runCatching ContentVaultSetupResult.EmptyRoot
                    }
                }
                is WebDavListResult.Unauthorized -> return@runCatching ContentVaultSetupResult.ConnectionFailed
                is WebDavListResult.Failed -> return@runCatching ContentVaultSetupResult.ConnectionFailed
            }

            val manifestPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
            val manifestEntry = children.firstOrNull { it.path.matchesWebDavPath(manifestPath) }
            when {
                manifestEntry != null -> connectExisting(config, webDav, manifestPath)
                children.isEmpty() && initializeEmptyRoot -> initialize(config, webDav, manifestPath)
                children.isEmpty() -> ContentVaultSetupResult.EmptyRoot
                else -> ContentVaultSetupResult.NonVaultRoot
            }
        }.getOrElse { ContentVaultSetupResult.ConnectionFailed }
    }

    suspend fun testConnection(config: WebDavVaultConfig): ContentVaultConnectionTestResult {
        if (!config.hasConnectionDetails) return ContentVaultConnectionTestResult.IncompleteConfiguration
        return runCatching {
            val webDav = WebDavClient(config)
            when (val result = webDav.list("")) {
                is WebDavListResult.Entries -> ContentVaultConnectionTestResult.Connected
                WebDavListResult.NotFound -> ContentVaultConnectionTestResult.Failed(HttpURLConnection.HTTP_NOT_FOUND)
                is WebDavListResult.Unauthorized -> {
                    if (config.rootPath.isNotBlank()) {
                        webDav.list(config.rootPath).toConnectionTestResult()
                    } else {
                        ContentVaultConnectionTestResult.Unauthorized(result.statusCode)
                    }
                }
                is WebDavListResult.Failed -> ContentVaultConnectionTestResult.Failed(result.statusCode)
            }
        }.getOrElse {
            ContentVaultConnectionTestResult.Failed(statusCode = null, detail = it.toConnectionFailureDetail())
        }
    }

    private fun WebDavListResult.toConnectionTestResult(): ContentVaultConnectionTestResult {
        return when (this) {
            is WebDavListResult.Entries -> ContentVaultConnectionTestResult.Connected
            WebDavListResult.NotFound -> ContentVaultConnectionTestResult.Failed(HttpURLConnection.HTTP_NOT_FOUND)
            is WebDavListResult.Unauthorized -> ContentVaultConnectionTestResult.Unauthorized(statusCode)
            is WebDavListResult.Failed -> ContentVaultConnectionTestResult.Failed(statusCode)
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
        webDav: WebDavClient,
        manifestPath: String,
    ): ContentVaultSetupResult {
        val manifest = webDav.get(manifestPath)
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
        webDav: WebDavClient,
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

        if (!webDav.put(manifestPath, manifestCodec.encodeRoot(manifest))) {
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

    private inner class WebDavClient(
        private val config: WebDavVaultConfig,
    ) {
        suspend fun list(path: String): WebDavListResult = withContext(Dispatchers.IO) {
            val body = PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE)
            val request = requestBuilder(path, collection = true)
                .method("PROPFIND", body)
                .header("Depth", "1")
                .build()

            client.newCall(request).await().use { response ->
                when (response.code) {
                    HttpURLConnection.HTTP_OK, HTTP_MULTI_STATUS -> {
                        val bodyText = response.body.string()
                        WebDavListResult.Entries(parseEntries(bodyText, path))
                    }
                    HttpURLConnection.HTTP_NOT_FOUND -> WebDavListResult.NotFound
                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN,
                    -> WebDavListResult.Unauthorized(response.code)
                    else -> WebDavListResult.Failed(response.code)
                }
            }
        }

        suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
            val request = requestBuilder(path, collection = false)
                .method("MKCOL", EMPTY_BODY)
                .build()

            client.newCall(request).await().use { response ->
                response.code == HttpURLConnection.HTTP_CREATED ||
                    response.code == HttpURLConnection.HTTP_OK ||
                    response.code == HttpURLConnection.HTTP_NO_CONTENT ||
                    response.code == HTTP_METHOD_NOT_ALLOWED
            }
        }

        suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
            val request = requestBuilder(path, collection = false).get().build()
            client.newCall(request).await().use { response ->
                response.takeIf { it.isSuccessful }?.body?.string()
            }
        }

        suspend fun put(path: String, body: String): Boolean = withContext(Dispatchers.IO) {
            val request = requestBuilder(path, collection = false)
                .put(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).await().use { response ->
                response.isSuccessful
            }
        }

        private fun requestBuilder(path: String, collection: Boolean): Request.Builder {
            return Request.Builder()
                .url(config.serverUrl.toBaseUrl().resolvePath(path, collection))
                .header("Authorization", Credentials.basic(config.username.trim(), config.password))
        }
    }

    private fun parseEntries(body: String, rootPath: String): List<WebDavEntry> {
        val document = DocumentBuilderFactory.newInstance()
            .apply {
                isNamespaceAware = true
                setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
                setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
            }
            .newDocumentBuilder()
            .parse(body.byteInputStream())

        val root = rootPath.trimEnd('/')
        val rootSegment = "/${rootPath.trim('/')}"
        val responses = document.getElementsByTagNameNS("*", "response")
        return buildList {
            for (index in 0 until responses.length) {
                val response = responses.item(index) as? Element ?: continue
                val href = response.getElementsByTagNameNS("*", "href").item(0)?.textContent ?: continue
                val decodedPath = java.net.URLDecoder.decode(href, Charsets.UTF_8.name()).trimEnd('/')
                if (decodedPath == root || decodedPath.endsWith(rootSegment)) continue
                if (decodedPath.contains("$rootSegment/").not()) continue
                add(WebDavEntry(decodedPath))
            }
        }
    }

    private fun String.toBaseUrl(): String = trim().trimEnd('/')

    private fun String.resolvePath(path: String, collection: Boolean): String {
        val cleanPath = path.trim().trim('/')
        val url = if (cleanPath.isBlank()) this else "$this/$cleanPath"
        return if (collection) "$url/" else url
    }

    private fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')

    private fun String.matchesWebDavPath(path: String): Boolean {
        val cleanSelf = trimEnd('/')
        val cleanPath = path.trimEnd('/')
        return cleanSelf == cleanPath || cleanSelf.endsWith("/$cleanPath")
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(feature: String, value: Boolean) {
        try {
            setFeature(feature, value)
        } catch (_: ParserConfigurationException) {
        } catch (_: SAXNotRecognizedException) {
        } catch (_: SAXNotSupportedException) {
        }
    }

    companion object {
        private const val DEFAULT_DISPLAY_NAME = "Content Vault"
        private const val HTTP_MULTI_STATUS = 207
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val MAX_FAILURE_DETAIL_LENGTH = 96
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""
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

private data class WebDavEntry(
    val path: String,
)

private sealed interface WebDavListResult {
    data class Entries(val entries: List<WebDavEntry>) : WebDavListResult
    data object NotFound : WebDavListResult
    data class Unauthorized(val statusCode: Int) : WebDavListResult
    data class Failed(val statusCode: Int) : WebDavListResult
}

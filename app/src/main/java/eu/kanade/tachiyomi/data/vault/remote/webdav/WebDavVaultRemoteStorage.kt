package eu.kanade.tachiyomi.data.vault.remote.webdav

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.localimport.asRequestBody
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteEntry
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteListResult
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteReadResult
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorage
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteWriteResult
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import tachiyomi.domain.vault.model.WebDavVaultConfig
import java.net.HttpURLConnection
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

internal class WebDavVaultRemoteStorageFactory(
    networkHelper: NetworkHelper,
) : VaultRemoteStorageFactory {
    private val client = networkHelper.nonCloudflareClient

    override fun create(config: WebDavVaultConfig): VaultRemoteStorage {
        return WebDavVaultRemoteStorage(config, client)
    }
}

internal class WebDavVaultRemoteStorage(
    private val config: WebDavVaultConfig,
    private val client: OkHttpClient,
) : VaultRemoteStorage {

    override suspend fun list(path: String): VaultRemoteListResult = withContext(Dispatchers.IO) {
        val request = requestBuilder(path, collection = true)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        client.newCall(request).await().use { response ->
            when (response.code) {
                HttpURLConnection.HTTP_OK,
                HTTP_MULTI_STATUS,
                -> VaultRemoteListResult.Entries(parseEntries(response.body.string(), path))
                HttpURLConnection.HTTP_NOT_FOUND -> VaultRemoteListResult.NotFound
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                -> VaultRemoteListResult.Unauthorized(response.code)
                else -> VaultRemoteListResult.Failed(response.code)
            }
        }
    }

    override suspend fun getText(path: String): VaultRemoteReadResult<String> = withContext(Dispatchers.IO) {
        val request = requestBuilder(path).get().build()
        client.newCall(request).await().use { response ->
            when {
                response.isSuccessful -> VaultRemoteReadResult.Found(response.body.string())
                response.code == HttpURLConnection.HTTP_NOT_FOUND -> VaultRemoteReadResult.NotFound
                else -> VaultRemoteReadResult.Failed(response.code)
            }
        }
    }

    override suspend fun getBytes(path: String): VaultRemoteReadResult<ByteArray> = withContext(Dispatchers.IO) {
        val request = requestBuilder(path).get().build()
        client.newCall(request).await().use { response ->
            when {
                response.isSuccessful -> VaultRemoteReadResult.Found(response.body.bytes())
                response.code == HttpURLConnection.HTTP_NOT_FOUND -> VaultRemoteReadResult.NotFound
                else -> VaultRemoteReadResult.Failed(response.code)
            }
        }
    }

    override suspend fun putText(path: String, body: String): VaultRemoteWriteResult = withContext(Dispatchers.IO) {
        val request = requestBuilder(path)
            .put(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).await().use { response ->
            response.toWriteResult()
        }
    }

    override suspend fun putBytes(
        path: String,
        bytes: ByteArray,
        mediaType: String?,
    ): VaultRemoteWriteResult = withContext(Dispatchers.IO) {
        val request = requestBuilder(path)
            .put(bytes.toRequestBody(mediaType?.toMediaTypeOrNull()))
            .build()
        client.newCall(request).await().use { response ->
            response.toWriteResult()
        }
    }

    override suspend fun putFile(path: String, file: UniFile): VaultRemoteWriteResult = withContext(Dispatchers.IO) {
        val request = requestBuilder(path)
            .put(file.asRequestBody())
            .build()
        client.newCall(request).await().use { response ->
            response.toWriteResult()
        }
    }

    override suspend fun createDirectory(path: String): VaultRemoteWriteResult = withContext(Dispatchers.IO) {
        val request = requestBuilder(path, collection = true)
            .method("MKCOL", EMPTY_BODY)
            .build()
        client.newCall(request).await().use { response ->
            when {
                response.isSuccessful ||
                    response.code == HttpURLConnection.HTTP_CREATED ||
                    response.code == HttpURLConnection.HTTP_NO_CONTENT ||
                    response.code == HTTP_METHOD_NOT_ALLOWED -> VaultRemoteWriteResult.Success
                response.code == HttpURLConnection.HTTP_NOT_FOUND -> VaultRemoteWriteResult.NotFound
                else -> VaultRemoteWriteResult.Failed(response.code)
            }
        }
    }

    override suspend fun delete(path: String): VaultRemoteWriteResult = withContext(Dispatchers.IO) {
        val request = requestBuilder(path).delete().build()
        client.newCall(request).await().use { response ->
            when {
                response.isSuccessful || response.code == HttpURLConnection.HTTP_NOT_FOUND ->
                    VaultRemoteWriteResult.Success
                else -> VaultRemoteWriteResult.Failed(response.code)
            }
        }
    }

    override suspend fun move(stagedPath: String, finalPath: String): VaultRemoteWriteResult = withContext(
        Dispatchers.IO,
    ) {
        val request = requestBuilder(stagedPath)
            .header("Destination", config.serverUrl.resolveWebDavPath(finalPath).toString())
            .header("Overwrite", "T")
            .method("MOVE", EMPTY_BODY)
            .build()
        client.newCall(request).await().use { response ->
            when {
                response.isSuccessful ||
                    response.code == HttpURLConnection.HTTP_CREATED ||
                    response.code == HttpURLConnection.HTTP_NO_CONTENT -> VaultRemoteWriteResult.Success
                response.code == HttpURLConnection.HTTP_NOT_FOUND -> VaultRemoteWriteResult.NotFound
                else -> VaultRemoteWriteResult.Failed(response.code)
            }
        }
    }

    private fun requestBuilder(path: String, collection: Boolean = false): Request.Builder {
        return Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path, collection = collection))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
    }

    private fun okhttp3.Response.toWriteResult(): VaultRemoteWriteResult {
        return when {
            isSuccessful -> VaultRemoteWriteResult.Success
            code == HttpURLConnection.HTTP_NOT_FOUND -> VaultRemoteWriteResult.NotFound
            else -> VaultRemoteWriteResult.Failed(code)
        }
    }

    companion object {
        internal fun parseEntries(body: String, rootPath: String): List<VaultRemoteEntry> {
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
                    val decodedPath = URLDecoder.decode(href, Charsets.UTF_8.name()).trimEnd('/')
                    if (decodedPath == root || decodedPath.endsWith(rootSegment)) continue
                    if (decodedPath.contains("$rootSegment/").not()) continue
                    add(VaultRemoteEntry(decodedPath))
                }
            }
        }

        private fun DocumentBuilderFactory.setFeatureIfSupported(feature: String, value: Boolean) {
            try {
                setFeature(feature, value)
            } catch (_: ParserConfigurationException) {
            } catch (_: SAXNotRecognizedException) {
            } catch (_: SAXNotSupportedException) {
            }
        }

        private const val HTTP_MULTI_STATUS = 207
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""
    }
}

package eu.kanade.tachiyomi.data.vault

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.importing.asRequestBody
import eu.kanade.tachiyomi.data.vault.importing.resolveWebDavPath
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
import tachiyomi.domain.vault.model.WebDavVaultConfig

internal interface LibraryVaultCaptureWebDav : VaultManifestPublishStorage, VaultContentUploadStorage {
    override suspend fun get(path: String): String?
    override suspend fun put(path: String, body: String): Boolean
    override suspend fun putFile(path: String, file: UniFile): Boolean
    override suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): Boolean
    override suspend fun createDirectory(path: String): Boolean
    override suspend fun delete(path: String): Boolean
}

internal interface LibraryVaultCaptureWebDavFactoryBoundary {
    fun create(config: WebDavVaultConfig): LibraryVaultCaptureWebDav
}

internal class LibraryVaultCaptureWebDavFactory(
    networkHelper: NetworkHelper,
) : LibraryVaultCaptureWebDavFactoryBoundary {
    private val client = networkHelper.nonCloudflareClient

    override fun create(config: WebDavVaultConfig): LibraryVaultCaptureWebDav {
        return HttpLibraryVaultCaptureWebDav(config, client)
    }
}

private class HttpLibraryVaultCaptureWebDav(
    private val config: WebDavVaultConfig,
    private val client: OkHttpClient,
) : LibraryVaultCaptureWebDav {
    override suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
            .get()
            .build()
        client.newCall(request).await().use { response ->
            response.takeIf { it.isSuccessful }?.body?.string()
        }
    }

    override suspend fun put(path: String, body: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
            .put(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).await().use { it.isSuccessful }
    }

    override suspend fun putFile(path: String, file: UniFile): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
            .put(file.asRequestBody())
            .build()
        client.newCall(request).await().use { it.isSuccessful }
    }

    override suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): Boolean = withContext(
        Dispatchers.IO,
    ) {
        val request = Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
            .put(bytes.toRequestBody(mediaType?.toMediaTypeOrNull()))
            .build()
        client.newCall(request).await().use { it.isSuccessful }
    }

    override suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path, collection = true))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
            .method("MKCOL", EMPTY_BODY)
            .build()
        client.newCall(request).await().use { response ->
            response.isSuccessful || response.code == HTTP_METHOD_NOT_ALLOWED
        }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
            .delete()
            .build()
        client.newCall(request).await().use { response ->
            response.isSuccessful || response.code == HTTP_NOT_FOUND
        }
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_NOT_FOUND = 404
    }
}

package mihon.data.extension.service

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.data.extension.model.NetworkExtensionStore
import mihon.data.extension.model.NetworkLegacyExtension
import mihon.data.extension.model.NetworkLegacyExtensionRepo
import mihon.data.extension.model.toAvailableExtensions
import mihon.domain.extension.model.ExtensionStore
import okio.BufferedSource
import okio.buffer
import okio.gzip
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException

private const val JSON_ARRAY_START = 0x5B
private const val JSON_OBJECT_START = 0x7B
private const val GZIP_MAGIC = 0x1f8b

class ExtensionStoreService(
    private val network: NetworkHelper,
    private val json: Json,
    private val protoBuf: ProtoBuf,
) {
    suspend fun fetch(indexUrl: String): Result<ExtensionStore> {
        var updatedIndexUrl: String = indexUrl
        return try {
            val store = network.client.newCall(
                GET(indexUrl),
            ).awaitSuccess().body.source().decompressIfGzipped().use { source ->
                val networkStore = when (source.peek().readByte().toInt()) {
                    // "[..."
                    JSON_ARRAY_START -> {
                        require(indexUrl.endsWith("/index.min.json")) { "Provided legacy store url is not valid" }
                        updatedIndexUrl = indexUrl.replace("/index.min.json", "/repo.json")
                        network.client.newCall(GET(updatedIndexUrl)).awaitSuccess().body.source().use {
                            json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(it)
                        }
                    }
                    // "{..."
                    JSON_OBJECT_START -> try {
                        json.decodeFromBufferedSource<NetworkExtensionStore>(source.peek())
                    } catch (_: IllegalArgumentException) {
                        json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(source)
                    }
                    else -> protoBuf.decodeFromByteArray<NetworkExtensionStore>(source.readByteArray())
                }
                if (networkStore is NetworkLegacyExtensionRepo && networkStore.indexV2 != null) {
                    return fetch(networkStore.indexV2)
                }
                networkStore.toExtensionStore(updatedIndexUrl)
            }
            Result.success(store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) {
                "Failed to add extension store '$updatedIndexUrl'"
            }
            Result.failure(e)
        }
    }

    suspend fun getExtensions(store: ExtensionStore): Result<List<Extension.Available>> {
        return try {
            val extensionListUrl = store.extensionListUrl
            val extensions = if (extensionListUrl != null) {
                fetchExtensionList(extensionListUrl, store)
            } else if (!store.isLegacy) {
                val response = network.client.newCall(GET(store.indexUrl)).awaitSuccess()
                response.body.source().decompressIfGzipped().use { source ->
                    val networkStore = when (source.peek().readByte().toInt()) {
                        // "{..."
                        JSON_OBJECT_START -> json.decodeFromBufferedSource<NetworkExtensionStore>(source)
                        else -> protoBuf.decodeFromByteArray<NetworkExtensionStore>(source.readByteArray())
                    }
                    networkStore.extensionList
                        ?.toAvailableExtensions(store)
                        ?: fetchExtensionList(networkStore.extensionListUrl!!, store)
                }
            } else {
                fetchLegacyExtensions(store)
            }
            Result.success(extensions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchLegacyExtensions(store: ExtensionStore): List<Extension.Available> {
        val storeBaseUrl = store.indexUrl.removeSuffix("/repo.json")
        val repo = network.client.newCall(GET(store.indexUrl)).awaitSuccess()
            .body.source()
            .decompressIfGzipped()
            .use { source ->
                json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(source)
            }
        repo.indexV2?.let { indexV2 ->
            return fetchExtensionStore(indexV2, store)
        }

        val response = network.client.newCall(GET("$storeBaseUrl/index.min.json")).awaitSuccess()
        return response.body.source().use { source ->
            json.decodeFromBufferedSource<List<NetworkLegacyExtension>>(source)
                .map { it.toAvailableExtension(store, storeBaseUrl) }
        }
    }

    private suspend fun fetchExtensionList(
        extensionListUrl: String,
        store: ExtensionStore,
    ): List<Extension.Available> {
        val response = network.client.newCall(GET(extensionListUrl)).awaitSuccess()
        return response.body.source().decompressIfGzipped().use { source ->
            when (source.peek().readByte().toInt()) {
                // "{..."
                JSON_OBJECT_START -> json.decodeFromBufferedSource(source)
                else -> protoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(
                    source.readByteArray(),
                )
            }
                .toAvailableExtensions(store)
        }
    }

    private suspend fun fetchExtensionStore(
        indexUrl: String,
        store: ExtensionStore,
    ): List<Extension.Available> {
        val response = network.client.newCall(GET(indexUrl)).awaitSuccess()
        return response.body.source().decompressIfGzipped().use { source ->
            val networkStore = when (source.peek().readByte().toInt()) {
                // "{..."
                JSON_OBJECT_START -> json.decodeFromBufferedSource<NetworkExtensionStore>(source)
                else -> protoBuf.decodeFromByteArray<NetworkExtensionStore>(source.readByteArray())
            }
            networkStore.extensionList
                ?.toAvailableExtensions(store)
                ?: fetchExtensionList(networkStore.extensionListUrl!!, store)
        }
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == GZIP_MAGIC
            } catch (_: Exception) {
                false
            }
        }
        return if (isGzip) gzip().buffer() else this
    }
}

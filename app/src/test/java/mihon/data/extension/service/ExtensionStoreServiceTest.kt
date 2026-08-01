package mihon.data.extension.service

import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.extension.model.NetworkExtensionStore
import mihon.domain.extension.model.ExtensionStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404

class ExtensionStoreServiceTest {

    @Test
    fun `legacy repo with v2 index uses v2 extension list`() = runTest {
        val repoUrl = "https://repo.example/repo.json"
        val indexV2Url = "https://repo.example/index.pb"
        val service = ExtensionStoreService(
            network = networkHelper(
                repoUrl to """
                    {
                      "index_v2": "$indexV2Url",
                      "meta": {
                        "name": "Example Repo",
                        "website": "https://repo.example",
                        "signingKeyFingerprint": "signature"
                        }
                    }
                """.trimIndent().toByteArray(),
                indexV2Url to ProtoBuf.encodeToByteArray(
                    NetworkExtensionStore(
                        name = "Example Repo",
                        badgeLabel = "EX",
                        signingKey = "signature",
                        contact = NetworkExtensionStore.Contact(
                            website = "https://repo.example",
                            discord = null,
                        ),
                        extensionList = NetworkExtensionStore.ExtensionList(
                            extensions = listOf(
                                NetworkExtensionStore.Extension(
                                    name = "Example Source",
                                    packageName = "eu.kanade.tachiyomi.extension.en.example",
                                    resources = NetworkExtensionStore.Resources(
                                        apkUrl = "https://repo.example/example.apk",
                                        iconUrl = "https://repo.example/example.png",
                                    ),
                                    extensionLib = "1.6",
                                    versionCode = 1,
                                    versionName = "1.6.1",
                                    contentWarning = NetworkExtensionStore.ContentWarning.SAFE,
                                    sources = listOf(
                                        NetworkExtensionStore.Source(
                                            id = 123,
                                            name = "Example Source",
                                            language = "en",
                                            homeUrl = "https://source.example",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            json = Json { ignoreUnknownKeys = true },
            protoBuf = ProtoBuf,
        )

        val extensions = service.getExtensions(legacyStore(repoUrl)).getOrThrow()

        extensions.map { it.pkgName } shouldContainExactly listOf(
            "eu.kanade.tachiyomi.extension.en.example",
        )
    }

    @Test
    fun `legacy repo with gzipped v2 index uses available packages from v2 list`() = runTest {
        val repoUrl = "https://repo.example/repo.json"
        val indexV2Url = "https://repo.example/index.pb"
        val service = ExtensionStoreService(
            network = networkHelper(
                repoUrl to """
                    {
                      "index_v2": "$indexV2Url",
                      "meta": {
                        "name": "Example Repo",
                        "website": "https://repo.example",
                        "signingKeyFingerprint": "signature"
                      }
                    }
                """.trimIndent().toByteArray(),
                indexV2Url to gzip(
                    ProtoBuf.encodeToByteArray(
                        NetworkExtensionStore(
                            name = "Example Repo",
                            badgeLabel = "EX",
                            signingKey = "signature",
                            contact = NetworkExtensionStore.Contact(
                                website = "https://repo.example",
                                discord = null,
                            ),
                            extensionList = NetworkExtensionStore.ExtensionList(
                                extensions = listOf(
                                    NetworkExtensionStore.Extension(
                                        name = "Available Source",
                                        packageName = "eu.kanade.tachiyomi.extension.en.available",
                                        resources = NetworkExtensionStore.Resources(
                                            apkUrl = "https://repo.example/available.apk",
                                            iconUrl = "https://repo.example/available.png",
                                        ),
                                        extensionLib = "1.4",
                                        versionCode = 33,
                                        versionName = "1.4.33",
                                        contentWarning = NetworkExtensionStore.ContentWarning.SAFE,
                                        sources = listOf(
                                            NetworkExtensionStore.Source(
                                                id = 123,
                                                name = "Available Source",
                                                language = "en",
                                                homeUrl = "https://source.example",
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            json = Json { ignoreUnknownKeys = true },
            protoBuf = ProtoBuf,
        )

        val extensions = service.getExtensions(legacyStore(repoUrl)).getOrThrow()

        extensions.map { it.pkgName } shouldContainExactly listOf(
            "eu.kanade.tachiyomi.extension.en.available",
        )
    }

    private fun networkHelper(
        vararg responses: Pair<String, ByteArray>,
    ): NetworkHelper {
        val responseMap = responses.toMap()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (url in responseMap) HTTP_OK else HTTP_NOT_FOUND)
                    .message(if (url in responseMap) "OK" else "Not Found")
                    .body((responseMap[url] ?: ByteArray(0)).toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        return mockk {
            every { this@mockk.client } returns client
        }
    }

    private fun legacyStore(indexUrl: String) = ExtensionStore(
        indexUrl = indexUrl,
        name = "Example Repo",
        badgeLabel = "Example Repo",
        signingKey = "signature",
        contact = ExtensionStore.Contact(
            website = "https://repo.example",
            discord = null,
        ),
        isLegacy = true,
    )

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }
}

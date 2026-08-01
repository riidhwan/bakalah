package mihon.data.extension.model

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test

class NetworkExtensionStoreTest {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun `store index can point to separate extension list`() {
        val store = json.decodeFromString<NetworkExtensionStore>(
            """
            {
              "name": "Repo",
              "badgeLabel": "Repo",
              "signingKey": "signature",
              "contact": {
                "website": "https://repo.example",
                "discord": null
              },
              "extensionListUrl": "https://repo.example/extensions.pb"
            }
            """.trimIndent(),
        ).toExtensionStore("https://repo.example/repo.json")

        store.extensionListUrl shouldBe "https://repo.example/extensions.pb"
    }

    @Test
    fun `separate extension list maps to available extensions`() {
        val extensionList = json.decodeFromString<NetworkExtensionStore.ExtensionList>(
            """
            {
              "extensions": [
                {
                  "name": "Tachiyomi: Example",
                  "packageName": "eu.kanade.tachiyomi.extension.en.example",
                  "resources": {
                    "apkUrl": "https://repo.example/apk/example.apk",
                    "iconUrl": "https://repo.example/icon/example.png"
                  },
                  "extensionLib": "1.6",
                  "versionCode": 1,
                  "versionName": "1.6.1",
                  "contentWarning": "CONTENT_WARNING_SAFE",
                  "sources": [
                    {
                      "id": 1,
                      "name": "Example",
                      "language": "en",
                      "homeUrl": "https://example.org"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val availableExtension = extensionList.toAvailableExtensions(extensionStore()).single()

        availableExtension.pkgName shouldBe "eu.kanade.tachiyomi.extension.en.example"
        availableExtension.sources.single().name shouldBe "Example"
    }

    private fun extensionStore() = ExtensionStore(
        indexUrl = "https://repo.example/repo.json",
        name = "Repo",
        badgeLabel = "Repo",
        signingKey = "signature",
        contact = ExtensionStore.Contact(
            website = "https://repo.example",
            discord = null,
        ),
        isLegacy = false,
        extensionListUrl = "https://repo.example/extensions.pb",
    )
}

package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test

class ExtensionStatusTest {

    @Test
    fun `installed extension is obsolete when its origin store no longer lists it`() {
        val storeA = extensionStore("https://repo-a.example/repo.json")
        val storeB = extensionStore("https://repo-b.example/repo.json")
        val installed = installedExtension(
            pkgName = "eu.kanade.tachiyomi.extension.en.h",
            store = storeA,
        )

        val result = reconcileInstalledExtensionStatuses(
            installedExtensions = mapOf(installed.pkgName to installed),
            availableExtensions = listOf(
                availableExtension(
                    pkgName = installed.pkgName,
                    store = storeB,
                ),
            ),
        )

        result.getValue(installed.pkgName).isObsolete shouldBe true
        result.getValue(installed.pkgName).hasUpdate shouldBe false
        result.getValue(installed.pkgName).store shouldBe storeA
    }

    @Test
    fun `installed extension matches signing key when origin store is unknown`() {
        val store = extensionStore("https://repo.example/repo.json", signingKey = "signature")
        val installed = installedExtension(
            pkgName = "eu.kanade.tachiyomi.extension.en.h",
            store = null,
        )

        val result = reconcileInstalledExtensionStatuses(
            installedExtensions = mapOf(installed.pkgName to installed),
            availableExtensions = listOf(
                availableExtension(
                    pkgName = installed.pkgName,
                    store = store,
                ),
            ),
        )

        result.getValue(installed.pkgName).isObsolete shouldBe false
        result.getValue(installed.pkgName).store shouldBe store
    }

    @Test
    fun `installed extension prefers signing key over package order when origin store is unknown`() {
        val storeA = extensionStore("https://repo-a.example/repo.json", signingKey = "signature-a")
        val storeB = extensionStore("https://repo-b.example/repo.json", signingKey = "signature-b")
        val installed = installedExtension(
            pkgName = "eu.kanade.tachiyomi.extension.en.h",
            store = null,
            signatureHash = storeA.signingKey,
        )

        val result = reconcileInstalledExtensionStatuses(
            installedExtensions = mapOf(installed.pkgName to installed),
            availableExtensions = listOf(
                availableExtension(
                    pkgName = installed.pkgName,
                    store = storeB,
                ),
                availableExtension(
                    pkgName = installed.pkgName,
                    store = storeA,
                ),
            ),
        )

        result.getValue(installed.pkgName).isObsolete shouldBe false
        result.getValue(installed.pkgName).store shouldBe storeA
    }

    @Test
    fun `installed extension is obsolete when only another signing key lists it`() {
        val storeB = extensionStore("https://repo-b.example/repo.json", signingKey = "signature-b")
        val installed = installedExtension(
            pkgName = "eu.kanade.tachiyomi.extension.en.h",
            store = null,
            signatureHash = "signature-a",
        )

        val result = reconcileInstalledExtensionStatuses(
            installedExtensions = mapOf(installed.pkgName to installed),
            availableExtensions = listOf(
                availableExtension(
                    pkgName = installed.pkgName,
                    store = storeB,
                ),
            ),
        )

        result.getValue(installed.pkgName).isObsolete shouldBe true
    }

    @Test
    fun `obsolete extension becomes active again when origin store lists it`() {
        val store = extensionStore("https://repo.example/repo.json")
        val installed = installedExtension(
            pkgName = "eu.kanade.tachiyomi.extension.en.h",
            store = store,
            isObsolete = true,
        )

        val result = reconcileInstalledExtensionStatuses(
            installedExtensions = mapOf(installed.pkgName to installed),
            availableExtensions = listOf(
                availableExtension(
                    pkgName = installed.pkgName,
                    store = store,
                ),
            ),
        )

        result.getValue(installed.pkgName).isObsolete shouldBe false
    }

    private fun installedExtension(
        pkgName: String,
        store: ExtensionStore?,
        isObsolete: Boolean = false,
        signatureHash: String = store?.signingKey ?: "signature",
    ) = Extension.Installed(
        name = "Extension",
        pkgName = pkgName,
        versionName = "1.0",
        versionCode = 1,
        libVersion = 1.0,
        lang = "en",
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        signatureHash = signatureHash,
        isObsolete = isObsolete,
        isShared = true,
        store = store,
    )

    private fun availableExtension(
        pkgName: String,
        store: ExtensionStore,
    ) = Extension.Available(
        name = "Extension",
        pkgName = pkgName,
        versionName = "1.0",
        versionCode = 1,
        libVersion = 1.0,
        lang = "en",
        sources = emptyList(),
        apkUrl = "https://example.com/extension.apk",
        iconUrl = "https://example.com/icon.png",
        store = store,
    )

    private fun extensionStore(
        indexUrl: String,
        signingKey: String = "signing-key",
    ) = ExtensionStore(
        indexUrl = indexUrl,
        name = indexUrl,
        badgeLabel = indexUrl,
        signingKey = signingKey,
        contact = ExtensionStore.Contact(
            website = indexUrl,
            discord = null,
        ),
        isLegacy = false,
    )
}

package tachiyomi.domain.vault.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.WebDavVaultConfig

@Execution(ExecutionMode.CONCURRENT)
class ContentVaultPreferencesTest {

    @Test
    fun `webdav config is incomplete until all setup fields are present`() {
        val configWithoutRoot = WebDavVaultConfig(
            serverUrl = "https://example.com/dav",
            username = "user",
            password = "token",
            rootPath = "",
        )
        configWithoutRoot.hasConnectionDetails shouldBe true
        configWithoutRoot.isComplete shouldBe false

        WebDavVaultConfig(
            serverUrl = "https://example.com/dav",
            username = "user",
            password = "token",
            rootPath = "bakalah",
        ).isComplete shouldBe true
    }

    @Test
    fun `credentials are stored behind private preference keys`() {
        val preferences = ContentVaultPreferences(InMemoryPreferenceStore())

        Preference.isPrivate(preferences.webDavUsername.key()) shouldBe true
        Preference.isPrivate(preferences.webDavPassword.key()) shouldBe true
        Preference.isPrivate(preferences.webDavServerUrl.key()) shouldBe false
        Preference.isPrivate(preferences.webDavRootPath.key()) shouldBe false
    }

    @Test
    fun `saved webdav config records validated content vault identity`() {
        val preferences = ContentVaultPreferences(InMemoryPreferenceStore())
        val config = WebDavVaultConfig(
            serverUrl = "https://example.com/dav/",
            username = "user",
            password = "token",
            rootPath = "vault-root",
        )

        preferences.setWebDavConfig(config, ContentVaultIdentity("vault-identity"))

        val savedConfig = preferences.getWebDavConfig()
        savedConfig.serverUrl shouldBe config.serverUrl
        savedConfig.username shouldBe config.username
        savedConfig.password shouldBe config.password
        savedConfig.rootPath shouldBe config.rootPath
        preferences.configuredVaultIdentity.get() shouldBe "vault-identity"
    }
}

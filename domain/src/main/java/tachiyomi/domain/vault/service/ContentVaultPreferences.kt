package tachiyomi.domain.vault.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.WebDavVaultConfig

class ContentVaultPreferences(
    private val preferenceStore: PreferenceStore,
) {
    val webDavServerUrl: Preference<String> = preferenceStore.getString("vault_webdav_server_url", "")
    val webDavRootPath: Preference<String> = preferenceStore.getString("vault_webdav_root_path", "")
    val newVaultDisplayName: Preference<String> = preferenceStore.getString("vault_display_name", "")
    val configuredVaultIdentity: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("vault_configured_identity"),
        "",
    )
    val localCacheLimitBytes: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("vault_local_cache_limit_bytes"),
        DEFAULT_LOCAL_CACHE_LIMIT_BYTES,
    )
    val includeSensitiveContent: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("vault_include_sensitive_content"),
        false,
    )

    val webDavUsername: Preference<String> = preferenceStore.getString(
        Preference.privateKey("vault_webdav_username"),
        "",
    )
    val webDavPassword: Preference<String> = preferenceStore.getString(
        Preference.privateKey("vault_webdav_password"),
        "",
    )

    fun getWebDavConfig(): WebDavVaultConfig {
        return WebDavVaultConfig(
            serverUrl = webDavServerUrl.get(),
            username = webDavUsername.get(),
            password = webDavPassword.get(),
            rootPath = webDavRootPath.get(),
        )
    }

    fun setWebDavConfig(config: WebDavVaultConfig, identity: ContentVaultIdentity) {
        webDavServerUrl.set(config.serverUrl.trim())
        webDavUsername.set(config.username)
        webDavPassword.set(config.password)
        webDavRootPath.set(config.rootPath.trim())
        configuredVaultIdentity.set(identity.value)
    }

    companion object {
        const val DEFAULT_LOCAL_CACHE_LIMIT_BYTES: Long = 2L * 1024L * 1024L * 1024L
    }
}

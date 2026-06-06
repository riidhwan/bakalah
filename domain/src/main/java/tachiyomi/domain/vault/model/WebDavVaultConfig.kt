package tachiyomi.domain.vault.model

data class WebDavVaultConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val rootPath: String,
) {
    val isComplete: Boolean
        get() = serverUrl.isNotBlank() &&
            username.isNotBlank() &&
            password.isNotBlank() &&
            rootPath.isNotBlank()

    val hasConnectionDetails: Boolean
        get() = serverUrl.isNotBlank() &&
            username.isNotBlank() &&
            password.isNotBlank()
}

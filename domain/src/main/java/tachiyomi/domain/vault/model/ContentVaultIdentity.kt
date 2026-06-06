package tachiyomi.domain.vault.model

@JvmInline
value class ContentVaultIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "Content Vault Identity must not be blank" }
    }
}

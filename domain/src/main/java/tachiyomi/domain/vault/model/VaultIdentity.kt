package tachiyomi.domain.vault.model

@JvmInline
value class VaultIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "Vault Identity must not be blank" }
    }
}

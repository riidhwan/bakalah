package eu.kanade.tachiyomi.data.vault.operation

import tachiyomi.domain.vault.model.ContentVaultIdentity

object VaultOperationQueueKey {
    private const val CONTENT_VAULT_PREFIX = "content-vault:"

    fun forContentVault(identity: ContentVaultIdentity): String = "$CONTENT_VAULT_PREFIX${identity.value}"

    fun contentVaultIdentity(queueKey: String): ContentVaultIdentity? {
        return queueKey
            .takeIf { it.startsWith(CONTENT_VAULT_PREFIX) }
            ?.removePrefix(CONTENT_VAULT_PREFIX)
            ?.takeIf(String::isNotBlank)
            ?.let(::ContentVaultIdentity)
    }
}

package tachiyomi.domain.vault.model

data class VaultRevision(
    val id: String,
    val number: Long,
) {
    companion object {
        fun initial() = VaultRevision(
            id = "",
            number = 0,
        )
    }
}

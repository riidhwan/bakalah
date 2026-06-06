package tachiyomi.domain.vault.model

data class VaultMetadata(
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val status: VaultMangaStatus,
) {
    val normalizedTitle: String
        get() = normalizeTitle(title)

    companion object {
        fun normalizeTitle(title: String): String {
            return title
                .trim()
                .lowercase()
                .replace(Regex("\\s+"), " ")
        }
    }
}

enum class VaultMangaStatus {
    UNKNOWN,
    ONGOING,
    COMPLETED,
    LICENSED,
    PUBLISHING_FINISHED,
    CANCELLED,
    ON_HIATUS,
}

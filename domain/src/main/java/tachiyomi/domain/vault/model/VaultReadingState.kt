package tachiyomi.domain.vault.model

data class VaultReadingState(
    val chapterId: Long,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val lastReadAt: Long?,
    val updatedAt: Long,
) {
    companion object {
        fun empty(chapterId: Long, now: Long) = VaultReadingState(
            chapterId = chapterId,
            read = false,
            bookmark = false,
            lastPageRead = 0,
            lastReadAt = null,
            updatedAt = now,
        )
    }
}

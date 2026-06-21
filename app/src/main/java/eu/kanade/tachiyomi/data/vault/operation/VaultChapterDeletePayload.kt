package eu.kanade.tachiyomi.data.vault.operation

import kotlinx.serialization.Serializable

@Serializable
data class VaultChapterDeletePayload(
    val version: Int = VERSION,
    val mangaId: Long,
    val chapterId: Long,
    val chapterIdentity: String? = null,
    val chapterTitle: String,
) {
    companion object {
        const val VERSION = 2
    }
}

package eu.kanade.tachiyomi.data.vault.operation

import kotlinx.serialization.Serializable

@Serializable
data class VaultChapterRenamePayload(
    val version: Int = VERSION,
    val mangaId: Long,
    val chapterId: Long,
    val chapterIdentity: String,
    val title: String,
) {
    companion object {
        const val VERSION = 1
    }
}

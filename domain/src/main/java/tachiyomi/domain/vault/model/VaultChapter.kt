package tachiyomi.domain.vault.model

data class VaultChapter(
    val id: Long,
    val mangaId: Long,
    val identity: VaultIdentity,
    val title: String,
    val chapterNumber: Double,
    val volumeNumber: Double?,
    val scanlator: String?,
    val sourceOrder: Long,
    val content: VaultChapterContent,
    val revision: VaultRevision,
    val dateUpload: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val thumbnail: VaultChapterThumbnail? = null,
) {
    val isRecognizedNumber: Boolean
        get() = chapterNumber >= 0
}

data class VaultChapterContent(
    val path: String,
    val format: VaultChapterContentFormat,
    val sizeBytes: Long,
    val checksumSha256: String,
)

enum class VaultChapterContentFormat {
    CBZ,
}

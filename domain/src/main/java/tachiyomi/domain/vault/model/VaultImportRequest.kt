package tachiyomi.domain.vault.model

data class VaultImportRequest(
    val id: Long,
    val mangaId: Long,
    val workflow: VaultImportRequestWorkflow,
    val targetMangaId: Long?,
    val createNewTitle: String?,
    val activeMangaIdentity: VaultIdentity? = null,
    val activeManifestPath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val chapters: List<VaultImportRequestChapter>,
) {
    val createNew: Boolean
        get() = createNewTitle != null

    val selectedChapterIds: Set<String>
        get() = chapters.map { it.selectionId }.toSet()

    val replacementChapterIds: Set<String>
        get() = chapters
            .filter { it.allowReplacement }
            .map { it.selectionId }
            .toSet()
}

enum class VaultImportRequestWorkflow {
    LOCAL_IMPORT,
    LIBRARY_CAPTURE,
}

enum class VaultImportRequestChapterState(
    val storageValue: String,
) {
    PENDING("pending"),
    COMPLETED("completed"),
    FAILED("failed"),
    ;

    companion object {
        fun fromStorageValue(value: String): VaultImportRequestChapterState {
            return entries.firstOrNull { it.storageValue == value } ?: PENDING
        }
    }
}

data class VaultImportRequestChapter(
    val chapterId: Long?,
    val selectionId: String,
    val sortOrder: Long,
    val allowReplacement: Boolean,
    val state: VaultImportRequestChapterState = VaultImportRequestChapterState.PENDING,
    val isReplaced: Boolean = false,
    val failureCategory: String? = null,
    val processedAt: Long? = null,
)

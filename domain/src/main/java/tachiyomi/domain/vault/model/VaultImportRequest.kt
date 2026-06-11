package tachiyomi.domain.vault.model

data class VaultImportRequest(
    val id: Long,
    val mangaId: Long,
    val workflow: VaultImportRequestWorkflow,
    val targetMangaId: Long?,
    val createNewTitle: String?,
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

data class VaultImportRequestChapter(
    val chapterId: Long?,
    val selectionId: String,
    val sortOrder: Long,
    val allowReplacement: Boolean,
)

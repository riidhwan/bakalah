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
    val sourceMangaTitle: String? = null,
    val targetMangaTitle: String? = null,
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

    val pendingTaskItems: List<VaultImportRequestChapter>
        get() = chapters
            .filter { it.state == VaultImportRequestChapterState.PENDING }
            .sortedWith(compareBy<VaultImportRequestChapter> { it.sortOrder }.thenBy { it.selectionId })

    val nonTerminalTaskItems: List<VaultImportRequestChapter>
        get() = chapters.filter {
            it.state == VaultImportRequestChapterState.PENDING ||
                it.state == VaultImportRequestChapterState.RUNNING
        }
}

data class VaultImportRequestSummary(
    val id: Long,
    val mangaId: Long,
    val workflow: VaultImportRequestWorkflow,
    val targetMangaId: Long?,
    val createNewTitle: String?,
    val activeMangaIdentity: VaultIdentity? = null,
    val activeManifestPath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val totalChapters: Int,
    val pendingChapters: Int,
    val runningChapters: Int,
    val completedChapters: Int,
    val failedChapters: Int,
    val replacedChapters: Int,
    val sourceMangaTitle: String? = null,
    val sourceMangaSourceId: Long? = null,
    val sourceMangaFavorite: Boolean = false,
    val sourceMangaThumbnailUrl: String? = null,
    val sourceMangaCoverLastModified: Long = 0,
    val targetMangaTitle: String? = null,
    val isTargetSensitive: Boolean = false,
) {
    val createNew: Boolean
        get() = createNewTitle != null
}

enum class VaultImportRequestWorkflow {
    LOCAL_IMPORT,
    LIBRARY_CAPTURE,
}

enum class VaultImportRequestChapterState(
    val storageValue: String,
) {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromStorageValue(value: String): VaultImportRequestChapterState {
            return entries.firstOrNull { it.storageValue == value } ?: UNKNOWN
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
    val chapterTitle: String? = null,
)

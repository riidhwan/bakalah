package tachiyomi.domain.vault.model

data class LibraryVaultCaptureManga(
    val mangaId: Long,
    val sourceId: Long,
    val sourceIdentity: String,
    val title: String,
    val metadata: VaultMetadata,
)

data class LibraryVaultCaptureChapter(
    val selectionId: String,
    val title: String,
    val chapterNumber: Double,
    val volumeNumber: Double?,
    val scanlator: String?,
    val sourceOrder: Long,
    val dateUpload: Long,
    val sourceChapterUrl: String,
)

data class LibraryVaultCapturePlan(
    val target: LibraryVaultCaptureTarget,
    val chapters: List<LibraryVaultCaptureChapterPlan>,
)

data class LibraryVaultCaptureChapterPlan(
    val chapter: LibraryVaultCaptureChapter,
    val duplicateState: LibraryVaultCaptureDuplicateState,
    val selectedByDefault: Boolean,
)

enum class LibraryVaultCaptureDuplicateState {
    NONE,
    POSSIBLE,
}

sealed interface LibraryVaultCaptureTarget {
    data class Existing(
        val manga: VaultManga,
        val reason: Reason,
    ) : LibraryVaultCaptureTarget

    data object CreateNew : LibraryVaultCaptureTarget

    data class Choose(
        val candidates: List<VaultManga>,
    ) : LibraryVaultCaptureTarget

    enum class Reason {
        IMPORT_TARGET_HINT,
        EXACT_TITLE_MATCH,
        USER_SELECTED,
    }
}

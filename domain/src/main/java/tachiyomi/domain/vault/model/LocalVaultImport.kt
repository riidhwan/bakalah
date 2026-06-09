package tachiyomi.domain.vault.model

data class LocalVaultImportManga(
    val localMangaId: Long,
    val localMangaIdentity: String?,
    val title: String,
    val metadata: VaultMetadata,
)

data class LocalVaultImportChapter(
    val selectionId: String,
    val sourceFileName: String,
    val title: String,
    val chapterNumber: Double,
    val volumeNumber: Double?,
    val scanlator: String?,
    val sourceOrder: Long,
    val contentFormat: VaultChapterContentFormat,
    val sizeBytes: Long,
    val checksumSha256: String,
    val dateUpload: Long,
    val requiresLocalCbzConversion: Boolean = false,
)

data class LocalVaultImportPlan(
    val target: LocalVaultImportTarget,
    val chapters: List<LocalVaultImportChapterPlan>,
)

data class LocalVaultImportChapterPlan(
    val chapter: LocalVaultImportChapter,
    val duplicateState: LocalVaultImportDuplicateState,
    val selectedByDefault: Boolean,
)

enum class LocalVaultImportDuplicateState {
    NONE,
    POSSIBLE,
}

sealed interface LocalVaultImportTarget {
    data class Existing(
        val manga: VaultManga,
        val reason: Reason,
    ) : LocalVaultImportTarget

    data object CreateNew : LocalVaultImportTarget

    data class Choose(
        val candidates: List<VaultManga>,
    ) : LocalVaultImportTarget

    enum class Reason {
        IMPORT_TARGET_HINT,
        EXACT_TITLE_MATCH,
        USER_SELECTED,
    }
}

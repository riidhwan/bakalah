package eu.kanade.tachiyomi.data.vault.localimport

internal fun Throwable.localImportFailureCategory(): String {
    return message?.takeIf {
        it in setOf(
            "empty_pages",
            "staging",
            "upload",
            "chapter_upload",
            "cover_upload",
            "publish",
            "manifest",
            "target",
            "identity",
            "unconfirmed_duplicate",
        )
    } ?: "import_failed"
}

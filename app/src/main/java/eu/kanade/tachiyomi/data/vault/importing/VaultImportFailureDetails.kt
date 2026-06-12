package eu.kanade.tachiyomi.data.vault.importing

internal data class LocalVaultImportChapterFailure(
    val title: String,
    val category: String,
)

internal fun Throwable.localImportFailureCategory(): String {
    return message?.takeIf {
        it in setOf(
            "empty_pages",
            "staging",
            "upload",
            "publish",
            "manifest",
            "target",
            "identity",
            "unconfirmed_duplicate",
        )
    } ?: "import_failed"
}

internal fun List<LocalVaultImportChapterFailure>.toDetailJson(): String? {
    if (isEmpty()) return null
    return joinToString(prefix = "[", postfix = "]") {
        """{"title":${it.title.jsonString()},"category":${it.category.jsonString()}}"""
    }
}

private fun String.jsonString(): String {
    return buildString {
        append('"')
        this@jsonString.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}

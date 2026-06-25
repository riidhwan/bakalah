package eu.kanade.tachiyomi.ui.manga.local

object LocalMangaMetadataEditValidator {

    fun isValidTitle(title: String): Boolean {
        return title.trim().isNotBlank()
    }

    fun normalizeOptional(value: String): String? {
        return value.trim().takeIf(String::isNotBlank)
    }

    fun parseGenres(value: String): List<String> {
        return value
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
    }
}

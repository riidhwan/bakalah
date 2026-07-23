package tachiyomi.domain.manga.model

import java.text.Normalizer

fun String.sameTitleLibraryMatchKey(): String {
    return libraryMatchKey()
}

fun String.sameArtistLibraryMatchKey(): String {
    return libraryMatchKey()
}

fun String?.sameArtistLibraryMatchKeys(): Set<String> {
    return this
        ?.split(',')
        ?.asSequence()
        ?.map { it.sameArtistLibraryMatchKey() }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        .orEmpty()
}

private fun String.libraryMatchKey(): String {
    return Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("(?<=\\p{IsLatin})\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

fun Manga.hasSameTitleLibraryMatch(
    libraryTitleKeys: Set<String>,
    localSourceId: Long,
): Boolean {
    return !favorite &&
        source != localSourceId &&
        title.sameTitleLibraryMatchKey() in libraryTitleKeys
}

fun Manga.hasSameArtistLibraryMatch(
    libraryArtistKeys: Set<String>,
    localSourceId: Long,
): Boolean {
    return !favorite &&
        source != localSourceId &&
        artist.sameArtistLibraryMatchKeys().any { it in libraryArtistKeys }
}

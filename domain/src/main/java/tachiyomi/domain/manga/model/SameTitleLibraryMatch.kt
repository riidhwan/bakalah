package tachiyomi.domain.manga.model

import java.text.Normalizer

fun String.sameTitleLibraryMatchKey(): String {
    return Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("(?<=\\p{IsLatin})\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

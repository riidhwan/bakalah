package eu.kanade.tachiyomi.data.vault.localimport

import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.domain.vault.model.VaultManifestChapter

internal fun orderVaultImportChapters(
    chapters: List<VaultManifestChapter>,
    replacementIdentities: Set<String>,
): List<VaultManifestChapter> {
    val reservedOrders = chapters
        .filter { it.identity in replacementIdentities }
        .map { it.sourceOrder }
        .toSet()
    var nextOrder = 0L
    fun nextAvailableOrder(): Long {
        while (nextOrder in reservedOrders) {
            nextOrder++
        }
        return nextOrder++
    }

    val replacements = chapters
        .filter { it.identity in replacementIdentities }
        .associateBy { it.identity }
    val orderedNonReplacements = chapters
        .filterNot { it.identity in replacementIdentities }
        .sortedWith { first, second ->
            second.importFileName().compareToCaseInsensitiveNaturalOrder(first.importFileName())
        }
        .map { it.copy(sourceOrder = nextAvailableOrder()) }
        .associateBy { it.identity }

    return chapters
        .map { chapter -> replacements[chapter.identity] ?: orderedNonReplacements.getValue(chapter.identity) }
        .sortedWith(compareBy<VaultManifestChapter> { it.sourceOrder }.thenBy { it.importFileName() })
}

internal fun String.duplicateFileKey(): String {
    val trimmed = trim()
    return trimmed
        .substringBeforeLast('.', missingDelimiterValue = trimmed)
        .lowercase()
}

private fun VaultManifestChapter.importFileName(): String {
    return content.path.substringAfterLast('/')
}

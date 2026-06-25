package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.domain.vault.interactor.duplicateTitleKey
import tachiyomi.domain.vault.model.VaultChapter

internal fun List<VaultChapter>.inVaultReaderOrder(): List<VaultChapter> {
    return sortedWith { first, second ->
        if (first.isRecognizedNumber && second.isRecognizedNumber) {
            first.chapterNumber.compareTo(second.chapterNumber)
                .takeIf { it != 0 }
                ?.let { return@sortedWith it }
        }
        first.title
            .duplicateTitleKey()
            .compareToCaseInsensitiveNaturalOrder(second.title.duplicateTitleKey())
            .takeIf { it != 0 }
            ?: second.sourceOrder.compareTo(first.sourceOrder)
    }
}

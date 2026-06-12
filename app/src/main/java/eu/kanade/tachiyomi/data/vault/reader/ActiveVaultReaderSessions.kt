package eu.kanade.tachiyomi.data.vault.reader

import java.util.concurrent.ConcurrentHashMap

class ActiveVaultReaderSessions {
    private val mangaIds = ConcurrentHashMap.newKeySet<Long>()

    fun markActive(mangaId: Long) {
        mangaIds += mangaId
    }

    fun clear(mangaId: Long) {
        mangaIds -= mangaId
    }

    fun isActive(mangaId: Long): Boolean = mangaId in mangaIds
}

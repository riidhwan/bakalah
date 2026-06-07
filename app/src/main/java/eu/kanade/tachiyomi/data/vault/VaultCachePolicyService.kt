package eu.kanade.tachiyomi.data.vault

import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences

class VaultCachePolicyService(
    private val repository: VaultRepository,
    private val localStaging: VaultTransferLocalStaging,
    private val preferences: ContentVaultPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun cachePath(manga: VaultManga, chapter: VaultChapter): String {
        val fileName = chapter.content.path.substringAfterLast('/').ifBlank { "${chapter.identity.value}.chapter" }
        return listOf(
            manga.vaultId.toString(),
            manga.identity.value,
            chapter.identity.value,
            fileName,
        ).joinToString("/") { it.toCachePathSegment() }
    }

    suspend fun markOpened(chapterId: Long) {
        val state = repository.getCacheState(chapterId) ?: return
        if (state.state != VaultCacheState.CACHED) return
        repository.upsertCacheState(state.copy(lastOpenedAt = now(), updatedAt = now()))
    }

    suspend fun evictChapter(chapterId: Long): VaultCacheEvictionResult {
        val state = repository.getCacheState(chapterId) ?: return VaultCacheEvictionResult.NotCached
        if (state.state != VaultCacheState.CACHED || state.localPath == null) {
            return VaultCacheEvictionResult.NotCached
        }
        evictState(state)
        return VaultCacheEvictionResult.Evicted
    }

    suspend fun evictManga(mangaId: Long): VaultMangaCacheEvictionResult {
        val chapters = repository.getChapters(mangaId)
        val evictedChapterIds = mutableListOf<Long>()
        chapters.forEach { chapter ->
            val state = repository.getCacheState(chapter.id) ?: return@forEach
            if (state.localPath == null) return@forEach
            evictState(state)
            evictedChapterIds += chapter.id
        }
        return VaultMangaCacheEvictionResult(evictedChapterIds)
    }

    suspend fun enforceLimit(
        vaultId: Long,
        protectedChapterIds: Set<Long> = emptySet(),
    ): VaultCacheLimitResult {
        val limit = preferences.localCacheLimitBytes.get().coerceAtLeast(0)
        var usage = repository.getLocalCacheUsageBytes(vaultId)
        if (usage <= limit) {
            return VaultCacheLimitResult(usageBytes = usage, limitBytes = limit, evictedChapterIds = emptyList())
        }

        val evictedChapterIds = mutableListOf<Long>()
        repository.getReadCacheStatesForVault(vaultId)
            .forEach { state ->
                if (usage <= limit) return@forEach
                if (state.chapterId in protectedChapterIds) return@forEach
                evictState(state)
                usage -= state.sizeBytes ?: 0L
                evictedChapterIds += state.chapterId
            }

        return VaultCacheLimitResult(
            usageBytes = usage.coerceAtLeast(0),
            limitBytes = limit,
            evictedChapterIds = evictedChapterIds,
        )
    }

    private suspend fun evictState(state: VaultChapterCacheState) {
        state.localPath?.let { localStaging.delete(it) }
        repository.upsertCacheState(
            state.copy(
                state = VaultCacheState.VAULT_ONLY,
                localPath = null,
                sizeBytes = null,
                checksumSha256 = null,
                lastVerifiedAt = null,
                lastOpenedAt = null,
                updatedAt = now(),
                failureReason = null,
            ),
        )
    }

    private fun String.toCachePathSegment(): String {
        return trim()
            .replace(Regex("[/\\\\]+"), "_")
            .replace(Regex("""\A[.]+|\p{Cntrl}"""), "_")
            .ifBlank { "_" }
    }
}

data class VaultCacheLimitResult(
    val usageBytes: Long,
    val limitBytes: Long,
    val evictedChapterIds: List<Long>,
) {
    val isOverLimit: Boolean
        get() = usageBytes > limitBytes
}

enum class VaultCacheEvictionResult {
    Evicted,
    NotCached,
}

data class VaultMangaCacheEvictionResult(
    val evictedChapterIds: List<Long>,
)

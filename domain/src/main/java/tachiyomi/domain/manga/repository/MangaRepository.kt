package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.LibraryMangaGroup
import tachiyomi.domain.manga.model.LibraryMangaGroupCandidate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount

interface MangaRepository {

    suspend fun getMangaById(id: Long): Manga

    suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga>

    suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga?

    fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?>

    suspend fun getFavorites(): List<Manga>

    suspend fun getReadMangaNotInLibrary(): List<Manga>

    suspend fun getLibraryManga(): List<LibraryManga>

    fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>>

    suspend fun getLibraryMangaGroupForManga(mangaId: Long): LibraryMangaGroup?

    suspend fun getLibraryMangaGroupCandidates(
        anchorMangaId: Long,
        groupId: Long?,
    ): List<LibraryMangaGroupCandidate>

    suspend fun createLibraryMangaGroup(primaryMangaId: Long, memberMangaIds: List<Long>): Long

    suspend fun addMangaToLibraryMangaGroup(groupId: Long, memberMangaIds: List<Long>)

    suspend fun setLibraryMangaGroupPrimary(groupId: Long, mangaId: Long)

    fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>>

    suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount>

    suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>>

    suspend fun resetViewerFlags(): Boolean

    suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>)

    suspend fun deleteById(id: Long): Boolean = error("Not implemented")

    suspend fun update(update: MangaUpdate): Boolean

    suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean

    suspend fun insertNetworkManga(manga: List<Manga>): List<Manga>
}

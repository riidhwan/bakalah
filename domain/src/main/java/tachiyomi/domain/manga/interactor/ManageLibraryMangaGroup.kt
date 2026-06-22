package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.LibraryMangaGroup
import tachiyomi.domain.manga.model.LibraryMangaGroupCandidate
import tachiyomi.domain.manga.repository.MangaRepository

class ManageLibraryMangaGroup(
    private val mangaRepository: MangaRepository,
) {

    suspend fun getGroupForManga(mangaId: Long): LibraryMangaGroup? {
        return mangaRepository.getLibraryMangaGroupForManga(mangaId)
    }

    suspend fun getCandidates(anchorMangaId: Long, groupId: Long?): List<LibraryMangaGroupCandidate> {
        return mangaRepository.getLibraryMangaGroupCandidates(anchorMangaId, groupId)
    }

    suspend fun createGroup(primaryMangaId: Long, memberMangaIds: List<Long>): Long {
        return mangaRepository.createLibraryMangaGroup(primaryMangaId, memberMangaIds)
    }

    suspend fun addSources(groupId: Long, memberMangaIds: List<Long>) {
        mangaRepository.addMangaToLibraryMangaGroup(groupId, memberMangaIds)
    }

    suspend fun setPrimary(groupId: Long, mangaId: Long) {
        mangaRepository.setLibraryMangaGroupPrimary(groupId, mangaId)
    }
}

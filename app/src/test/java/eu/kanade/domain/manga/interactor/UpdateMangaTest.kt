package eu.kanade.domain.manga.interactor

import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.LibraryMangaGroup
import tachiyomi.domain.manga.model.LibraryMangaGroupCandidate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository

class UpdateMangaTest {

    @Test
    fun `source update preserves existing optional metadata by default when source omits it`() = runTest {
        val repository = FakeMangaRepository()
        val updateManga = UpdateManga(repository, mockk<FetchInterval>())

        updateManga.awaitUpdateFromSource(
            localManga = localManga(),
            remoteManga = remoteMangaWithoutOptionalMetadata(),
            manualFetch = true,
            forceTitleUpdate = true,
            coverCache = mockk<CoverCache>(relaxed = true),
            libraryPreferences = libraryPreferences(),
            downloadManager = mockk<DownloadManager>(relaxed = true),
        )

        val update = repository.lastUpdate
        update shouldNotBe null
        update!!.author shouldBe null
        update.artist shouldBe null
        update.description shouldBe null
        update.genre shouldBe null
    }

    @Test
    fun `local metadata edit can clear optional metadata when source omits it`() = runTest {
        val repository = FakeMangaRepository()
        val updateManga = UpdateManga(repository, mockk<FetchInterval>())

        updateManga.awaitUpdateFromSource(
            localManga = localManga(),
            remoteManga = remoteMangaWithoutOptionalMetadata(),
            manualFetch = true,
            forceTitleUpdate = true,
            clearMissingMetadata = true,
            coverCache = mockk<CoverCache>(relaxed = true),
            libraryPreferences = libraryPreferences(),
            downloadManager = mockk<DownloadManager>(relaxed = true),
        )

        val update = repository.lastUpdate
        update shouldNotBe null
        update!!.author shouldBe ""
        update.artist shouldBe ""
        update.description shouldBe ""
        update.genre.shouldBeEmpty()
    }

    private fun localManga(): Manga {
        return Manga.create().copy(
            id = 1,
            source = 1,
            favorite = true,
            url = "Local Series",
            title = "Local Series",
            author = "Original Author",
            artist = "Original Artist",
            description = "Original Description",
            genre = listOf("Original Genre"),
            initialized = true,
        )
    }

    private fun remoteMangaWithoutOptionalMetadata(): SManga {
        return SManga.create().apply {
            url = "Local Series"
            title = "Edited Series"
            status = SManga.UNKNOWN
            thumbnail_url = null
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
            initialized = true
        }
    }

    private fun libraryPreferences(): LibraryPreferences {
        return mockk {
            every { updateMangaTitles } returns preference(false)
        }
    }

    private fun <T> preference(value: T): Preference<T> {
        return mockk {
            every { get() } returns value
            every { changes() } returns flowOf(value)
        }
    }

    private class FakeMangaRepository : MangaRepository {
        var lastUpdate: MangaUpdate? = null

        override suspend fun update(update: MangaUpdate): Boolean {
            lastUpdate = update
            return true
        }

        override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean = notImplemented()

        override suspend fun getMangaById(id: Long): Manga = notImplemented()

        override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> = notImplemented()

        override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? = notImplemented()

        override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> = notImplemented()

        override suspend fun getFavorites(): List<Manga> = notImplemented()

        override suspend fun getReadMangaNotInLibrary(): List<Manga> = notImplemented()

        override suspend fun getLibraryManga(): List<LibraryManga> = notImplemented()

        override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> = notImplemented()

        override suspend fun getLibraryMangaGroupForManga(mangaId: Long): LibraryMangaGroup? = notImplemented()

        override suspend fun getLibraryMangaGroupCandidates(
            anchorMangaId: Long,
            groupId: Long?,
        ): List<LibraryMangaGroupCandidate> = notImplemented()

        override suspend fun createLibraryMangaGroup(primaryMangaId: Long, memberMangaIds: List<Long>): Long {
            return notImplemented()
        }

        override suspend fun addMangaToLibraryMangaGroup(groupId: Long, memberMangaIds: List<Long>) {
            notImplemented<Unit>()
        }

        override suspend fun setLibraryMangaGroupPrimary(groupId: Long, mangaId: Long) {
            notImplemented<Unit>()
        }

        override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> = notImplemented()

        override suspend fun getLibraryMangaWithChapterCount(): List<MangaWithChapterCount> {
            return notImplemented()
        }

        override suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount> {
            return notImplemented()
        }

        override suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>> = notImplemented()

        override suspend fun resetViewerFlags(): Boolean = notImplemented()

        override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) = notImplemented<Unit>()

        override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> = notImplemented()

        private fun <T> notImplemented(): T = throw NotImplementedError()
    }
}

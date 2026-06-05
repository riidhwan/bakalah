package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Database
import tachiyomi.data.source.SourceRepositoryImpl
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.model.SourceWithCount
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource

class GetEnabledSourcesTest {

    @Test
    fun `subscribe returns online sources only`() = runTest {
        val remoteSource = source(id = 1, lang = "en", name = "Remote")
        val localSource = source(id = LocalSource.ID, lang = "other", name = "Local")
        val repository = FakeSourceRepository(
            sources = listOf(localSource),
            onlineSources = listOf(remoteSource),
        )
        val preferences = mockk<SourcePreferences> {
            every { pinnedSources } returns preference(emptySet())
            every { enabledLanguages } returns preference(setOf("en", "other"))
            every { disabledSources } returns preference(emptySet())
            every { lastUsedSource } returns preference(LocalSource.ID)
        }

        val result = GetEnabledSources(repository, preferences).subscribe().first()

        result.map { it.id } shouldContainExactly listOf(remoteSource.id)
    }

    @Test
    fun `subscribe preserves latest support from online sources`() = runTest {
        val onlineSource = mockk<HttpSource> {
            every { id } returns 1
            every { lang } returns "en"
            every { name } returns "Remote"
            every { supportsLatest } returns true
        }
        val repository = SourceRepositoryImpl(
            sourceManager = FakeSourceManager(listOf(onlineSource)),
            database = mockk<Database>(),
        )
        val preferences = mockk<SourcePreferences> {
            every { pinnedSources } returns preference(emptySet())
            every { enabledLanguages } returns preference(setOf("en"))
            every { disabledSources } returns preference(emptySet())
            every { lastUsedSource } returns preference(0)
        }

        val result = GetEnabledSources(repository, preferences).subscribe().first()

        result.single().supportsLatest shouldBe true
    }

    private fun source(
        id: Long,
        lang: String,
        name: String,
    ) = Source(
        id = id,
        lang = lang,
        name = name,
        supportsLatest = true,
        isStub = false,
    )

    private fun <T> preference(value: T): Preference<T> {
        return mockk {
            every { get() } returns value
            every { changes() } returns flowOf(value)
        }
    }

    private class FakeSourceRepository(
        private val sources: List<Source>,
        private val onlineSources: List<Source>,
    ) : SourceRepository {
        override fun getSources(): Flow<List<Source>> = flowOf(sources)

        override fun getOnlineSources(): Flow<List<Source>> = flowOf(onlineSources)

        override fun getSourcesWithFavoriteCount(): Flow<List<Pair<Source, Long>>> = notImplemented()

        override fun getSourcesWithNonLibraryManga(): Flow<List<SourceWithCount>> = notImplemented()

        override fun search(
            sourceId: Long,
            query: String,
            filterList: FilterList,
        ): SourcePagingSource = notImplemented()

        override fun getPopular(sourceId: Long): SourcePagingSource = notImplemented()

        override fun getLatest(sourceId: Long): SourcePagingSource = notImplemented()

        private fun <T> notImplemented(): T = throw NotImplementedError()
    }

    private class FakeSourceManager(
        sources: List<HttpSource>,
    ) : SourceManager {
        override val isInitialized = MutableStateFlow(true)

        override val catalogueSources = flowOf(sources)

        override fun get(sourceKey: Long) = null

        override fun getOrStub(sourceKey: Long) = throw NotImplementedError()

        override fun getOnlineSources(): List<HttpSource> = throw NotImplementedError()

        override fun getCatalogueSources() = throw NotImplementedError()

        override fun getStubSources(): List<StubSource> = throw NotImplementedError()
    }
}

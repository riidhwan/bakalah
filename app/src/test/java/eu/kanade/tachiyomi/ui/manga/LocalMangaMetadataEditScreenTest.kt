package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.ui.manga.local.toLocalMetadataRefreshSManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class LocalMangaMetadataEditScreenTest {

    @Test
    fun `local metadata refresh manga does not carry stale optional metadata`() {
        val manga = Manga.create().copy(
            url = "Local Series",
            title = "Local Series",
            author = "Original Author",
            artist = "Original Artist",
            description = "Original Description",
            genre = listOf("Original Genre"),
            initialized = true,
        )

        val refreshManga = manga.toLocalMetadataRefreshSManga()

        refreshManga.url shouldBe "Local Series"
        refreshManga.title shouldBe "Local Series"
        refreshManga.author shouldBe null
        refreshManga.artist shouldBe null
        refreshManga.description shouldBe null
        refreshManga.genre shouldBe null
        refreshManga.initialized shouldBe true
    }
}

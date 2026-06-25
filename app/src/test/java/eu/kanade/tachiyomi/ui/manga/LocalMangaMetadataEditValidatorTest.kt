package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.ui.manga.local.LocalMangaMetadataEditValidator
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LocalMangaMetadataEditValidatorTest {

    @Test
    fun `blank title is invalid after trimming`() {
        LocalMangaMetadataEditValidator.isValidTitle("   ") shouldBe false
    }

    @Test
    fun `non-blank title is valid after trimming`() {
        LocalMangaMetadataEditValidator.isValidTitle("  Series  ") shouldBe true
    }

    @Test
    fun `optional fields trim and convert blank values to null`() {
        LocalMangaMetadataEditValidator.normalizeOptional("  Author  ") shouldBe "Author"
        LocalMangaMetadataEditValidator.normalizeOptional("   ") shouldBe null
    }

    @Test
    fun `genres are split by comma trimmed and empty entries dropped`() {
        LocalMangaMetadataEditValidator.parseGenres(" Action,  Drama ,, Slice of Life, ")
            .shouldContainExactly("Action", "Drama", "Slice of Life")
    }
}

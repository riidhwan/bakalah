package tachiyomi.domain.manga.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class SameTitleLibraryMatchTest {

    @Test
    fun `title key ignores case whitespace and punctuation separators`() {
        "  One.Piece - Special  ".sameTitleLibraryMatchKey() shouldBe "one piece special"
    }

    @Test
    fun `title key removes diacritics`() {
        "Pokémon RéBurst".sameTitleLibraryMatchKey() shouldBe "pokemon reburst"
    }

    @Test
    fun `title key keeps non latin letters and numbers`() {
        "ドラゴンボール 42".sameTitleLibraryMatchKey() shouldBe "ドラゴンボール 42"
    }

    @Test
    fun `title key is blank when title has no letters or numbers`() {
        " - / ... ".sameTitleLibraryMatchKey() shouldBe ""
    }

    @Test
    fun `remote non favorite matches same library title`() {
        Manga.create()
            .copy(
                source = REMOTE_SOURCE_ID,
                favorite = false,
                title = "One Piece",
            )
            .hasSameTitleLibraryMatch(
                libraryTitleKeys = setOf("one piece"),
                localSourceId = LOCAL_SOURCE_ID,
            ) shouldBe true
    }

    @Test
    fun `local entry does not match same library title`() {
        Manga.create()
            .copy(
                source = LOCAL_SOURCE_ID,
                favorite = false,
                title = "One Piece",
            )
            .hasSameTitleLibraryMatch(
                libraryTitleKeys = setOf("one piece"),
                localSourceId = LOCAL_SOURCE_ID,
            ) shouldBe false
    }

    @Test
    fun `favorite entry does not match same library title`() {
        Manga.create()
            .copy(
                source = REMOTE_SOURCE_ID,
                favorite = true,
                title = "One Piece",
            )
            .hasSameTitleLibraryMatch(
                libraryTitleKeys = setOf("one piece"),
                localSourceId = LOCAL_SOURCE_ID,
            ) shouldBe false
    }

    private companion object {
        const val LOCAL_SOURCE_ID = 0L
        const val REMOTE_SOURCE_ID = 1L
    }
}

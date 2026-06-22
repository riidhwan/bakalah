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
}

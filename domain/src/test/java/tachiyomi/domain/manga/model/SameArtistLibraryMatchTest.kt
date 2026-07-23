package tachiyomi.domain.manga.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class SameArtistLibraryMatchTest {

    @Test
    fun `artist key ignores case whitespace and punctuation separators`() {
        "  John.Smith - Studio  ".sameArtistLibraryMatchKey() shouldBe "john smith studio"
    }

    @Test
    fun `artist key removes latin diacritics`() {
        "José Muñoz".sameArtistLibraryMatchKey() shouldBe "jose munoz"
    }

    @Test
    fun `artist keys split on commas only`() {
        "Jane Doe, John Smith / Studio, José Muñoz"
            .sameArtistLibraryMatchKeys() shouldBe setOf(
            "jane doe",
            "john smith studio",
            "jose munoz",
        )
    }

    @Test
    fun `blank and null artist fields produce no keys`() {
        null.sameArtistLibraryMatchKeys() shouldBe emptySet()
        " , - / ... , ".sameArtistLibraryMatchKeys() shouldBe emptySet()
    }

    @Test
    fun `remote non favorite matches same library artist token`() {
        Manga.create()
            .copy(
                source = REMOTE_SOURCE_ID,
                favorite = false,
                artist = "Jane Doe, John Smith",
            )
            .hasSameArtistLibraryMatch(
                libraryArtistKeys = setOf("john smith"),
                localSourceId = LOCAL_SOURCE_ID,
            ) shouldBe true
    }

    @Test
    fun `artist matching requires exact token`() {
        Manga.create()
            .copy(
                source = REMOTE_SOURCE_ID,
                favorite = false,
                artist = "John Smith",
            )
            .hasSameArtistLibraryMatch(
                libraryArtistKeys = setOf("smith"),
                localSourceId = LOCAL_SOURCE_ID,
            ) shouldBe false
    }

    @Test
    fun `blank artist does not match`() {
        Manga.create()
            .copy(
                source = REMOTE_SOURCE_ID,
                favorite = false,
                artist = null,
            )
            .hasSameArtistLibraryMatch(
                libraryArtistKeys = setOf("john smith"),
                localSourceId = LOCAL_SOURCE_ID,
            ) shouldBe false
    }

    @Test
    fun `local entry does not match same library artist`() {
        Manga.create()
            .copy(
                source = LOCAL_SOURCE_ID,
                favorite = false,
                artist = "John Smith",
            )
            .hasSameArtistLibraryMatch(
                libraryArtistKeys = setOf("john smith"),
                localSourceId = LOCAL_SOURCE_ID,
            ) shouldBe false
    }

    @Test
    fun `favorite entry does not match same library artist`() {
        Manga.create()
            .copy(
                source = REMOTE_SOURCE_ID,
                favorite = true,
                artist = "John Smith",
            )
            .hasSameArtistLibraryMatch(
                libraryArtistKeys = setOf("john smith"),
                localSourceId = LOCAL_SOURCE_ID,
            ) shouldBe false
    }

    private companion object {
        const val LOCAL_SOURCE_ID = 0L
        const val REMOTE_SOURCE_ID = 1L
    }
}

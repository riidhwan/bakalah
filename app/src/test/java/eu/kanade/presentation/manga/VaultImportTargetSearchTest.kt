package eu.kanade.presentation.manga

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRevision

class VaultImportTargetSearchTest {

    @Test
    fun `exact title match is ranked before containing match`() {
        val targets = listOf(
            target(id = 1, title = "One Piece Color"),
            target(id = 2, title = "One Piece"),
        )

        searchVaultImportTargets(
            targets = targets,
            query = "one  piece",
        ).map { it.metadata.title }.shouldContainExactly("One Piece", "One Piece Color")
    }

    @Test
    fun `selected target is kept before other matches`() {
        val targets = listOf(
            target(id = 1, title = "One Piece"),
            target(id = 2, title = "Romance Dawn"),
        )

        searchVaultImportTargets(
            targets = targets,
            query = "one",
            selectedTargetId = 2,
        ).map { it.metadata.title }.shouldContainExactly("Romance Dawn", "One Piece")
    }

    @Test
    fun `suggestions are capped`() {
        val targets = (1L..10L).map { id -> target(id = id, title = "Series $id") }

        searchVaultImportTargets(
            targets = targets,
            query = "series",
            limit = 3,
        ).map { it.metadata.title }.shouldContainExactly("Series 1", "Series 10", "Series 2")
    }

    private fun target(id: Long, title: String): VaultManga {
        return VaultManga(
            id = id,
            vaultId = 1,
            identity = VaultIdentity("manga-$id"),
            metadata = VaultMetadata(
                title = title,
                author = null,
                artist = null,
                description = null,
                status = VaultMangaStatus.UNKNOWN,
            ),
            sortKey = VaultMetadata.normalizeTitle(title),
            coverId = null,
            revision = VaultRevision("rev-$id", 1),
            createdAt = 0,
            updatedAt = 0,
        )
    }
}

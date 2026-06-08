package tachiyomi.domain.vault.interactor

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultMangaCollectionState
import tachiyomi.domain.vault.model.VaultMangaManifest
import tachiyomi.domain.vault.model.VaultMangaManifestPointer
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestCover
import tachiyomi.domain.vault.model.VaultManifestLabel
import tachiyomi.domain.vault.model.VaultManifestMetadata
import tachiyomi.domain.vault.model.VaultRootManifest

@Execution(ExecutionMode.CONCURRENT)
class BuildVaultCatalogueRefreshTest {

    private val codec = VaultManifestCodec(
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )
    private val builder = BuildVaultCatalogueRefresh(codec)

    @Test
    fun `build maps manifests into a catalogue refresh`() {
        val root = rootManifest()
        val manga = mangaManifest()
        val result = builder.build(
            rootManifestPath = "vault/content-vault.json",
            rootManifestBody = codec.encodeRoot(root),
            mangaManifestBodies = mapOf("manga/one-piece.json" to codec.encodeManga(manga)),
            existingVault = null,
            fetchedAt = 1_000,
        )

        (result is VaultCatalogueRefreshBuildResult.Success) shouldBe true
        val refresh = (result as VaultCatalogueRefreshBuildResult.Success).refresh

        refresh.vault.identity.value shouldBe "vault-1"
        refresh.vault.rootRevision.id shouldBe "root-rev"
        refresh.labels.single().name shouldBe "Favorites"
        refresh.manga.single().manga.metadata.status shouldBe VaultMangaStatus.ONGOING
        refresh.manga.single().cover?.checksumSha256 shouldBe "cover-checksum"
        refresh.manga.single().chapters.single().content.sizeBytes shouldBe 123
        refresh.snapshots.map { it.manifestPath } shouldBe listOf("vault/content-vault.json", "manga/one-piece.json")
    }

    @Test
    fun `build rejects manga manifests for a different vault identity`() {
        val result = builder.build(
            rootManifestPath = "vault/content-vault.json",
            rootManifestBody = codec.encodeRoot(rootManifest()),
            mangaManifestBodies = mapOf(
                "manga/one-piece.json" to codec.encodeManga(mangaManifest().copy(vaultIdentity = "other-vault")),
            ),
            existingVault = null,
            fetchedAt = 1_000,
        )

        result shouldBe VaultCatalogueRefreshBuildResult.IdentityMismatch("manga/one-piece.json")
    }

    @Test
    fun `build reports missing manga manifest from root pointer`() {
        val result = builder.build(
            rootManifestPath = "vault/content-vault.json",
            rootManifestBody = codec.encodeRoot(rootManifest()),
            mangaManifestBodies = emptyMap(),
            existingVault = null,
            fetchedAt = 1_000,
        )

        result shouldBe VaultCatalogueRefreshBuildResult.MissingMangaManifest("manga/one-piece.json")
    }

    @Test
    fun `build refuses unknown newer manga manifest version`() {
        val result = builder.build(
            rootManifestPath = "vault/content-vault.json",
            rootManifestBody = codec.encodeRoot(rootManifest()),
            mangaManifestBodies = mapOf(
                "manga/one-piece.json" to codec.encodeManga(
                    mangaManifest().copy(layoutVersion = CURRENT_VAULT_LAYOUT_VERSION + 1),
                ),
            ),
            existingVault = null,
            fetchedAt = 1_000,
        )

        result shouldBe VaultCatalogueRefreshBuildResult.UnsupportedNewerVersion(CURRENT_VAULT_LAYOUT_VERSION + 1)
    }

    @Test
    fun `build uses root pointer trash state as catalogue authority`() {
        val root = rootManifest().copy(
            manga = listOf(
                rootManifest().manga.single().copy(
                    collectionState = VaultMangaCollectionState.TRASHED,
                    trashedAt = 1_200,
                ),
            ),
        )
        val result = builder.build(
            rootManifestPath = "vault/content-vault.json",
            rootManifestBody = codec.encodeRoot(root),
            mangaManifestBodies = mapOf("manga/one-piece.json" to codec.encodeManga(mangaManifest())),
            existingVault = null,
            fetchedAt = 1_000,
        )

        (result is VaultCatalogueRefreshBuildResult.Success) shouldBe true
        val refresh = (result as VaultCatalogueRefreshBuildResult.Success).refresh

        refresh.manga.single().manga.collectionState shouldBe VaultMangaCollectionState.TRASHED
        refresh.manga.single().manga.trashedAt shouldBe 1_200
        refresh.activeManga shouldBe emptyList()
        refresh.labels shouldBe emptyList()
    }

    private fun rootManifest() = VaultRootManifest(
        identity = "vault-1",
        displayName = "My Vault",
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        revisionId = "root-rev",
        revisionNumber = 7,
        writerId = "writer-1",
        manga = listOf(
            VaultMangaManifestPointer(
                identity = "manga-1",
                path = "manga/one-piece.json",
                title = "One Piece",
                revisionId = "manga-rev",
                revisionNumber = 2,
                updatedAt = 900,
            ),
        ),
        createdAt = 10,
        updatedAt = 900,
    )

    private fun mangaManifest() = VaultMangaManifest(
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        vaultIdentity = "vault-1",
        mangaIdentity = "manga-1",
        revisionId = "manga-rev",
        revisionNumber = 2,
        metadata = VaultManifestMetadata(
            title = "One Piece",
            author = "Eiichiro Oda",
            artist = "Eiichiro Oda",
            description = "Pirate adventure",
            status = VaultMangaStatus.ONGOING,
        ),
        labels = listOf(
            VaultManifestLabel(
                identity = "label-1",
                name = "Favorites",
                sortKey = "favorites",
                createdAt = 20,
                updatedAt = 30,
            ),
        ),
        cover = VaultManifestCover(
            identity = "cover-1",
            path = "covers/cover-1.jpg",
            mediaType = "image/jpeg",
            integrity = VaultContentIntegrity(sizeBytes = 456, checksumSha256 = "cover-checksum"),
            revisionId = "cover-rev",
            revisionNumber = 1,
            updatedAt = 40,
        ),
        chapters = listOf(
            VaultManifestChapter(
                identity = "chapter-1",
                title = "Chapter 1",
                chapterNumber = 1.0,
                sourceOrder = 0,
                content = VaultManifestChapterContent(
                    path = "content/chapter-1.cbz",
                    format = VaultChapterContentFormat.CBZ,
                    integrity = VaultContentIntegrity(sizeBytes = 123, checksumSha256 = "chapter-checksum"),
                ),
                revisionId = "chapter-rev",
                revisionNumber = 1,
                dateUpload = 50,
                createdAt = 50,
                updatedAt = 60,
            ),
        ),
        createdAt = 20,
        updatedAt = 60,
    )
}

package tachiyomi.domain.vault.model

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class VaultManifestCodecTest {

    private val codec = VaultManifestCodec(
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    @Test
    fun `current root manifest is decoded`() {
        val result = codec.decodeRoot(codec.encodeRoot(rootManifest()))

        (result is VaultManifestReadResult.Success) shouldBe true
        (result as VaultManifestReadResult.Success).manifest.identity shouldBe "vault-1"
    }

    @Test
    fun `unknown newer layout is refused`() {
        val result = codec.decodeRoot(
            codec.encodeRoot(rootManifest().copy(layoutVersion = CURRENT_VAULT_LAYOUT_VERSION + 1)),
        )

        result shouldBe VaultManifestReadResult.UnsupportedNewerVersion(CURRENT_VAULT_LAYOUT_VERSION + 1)
    }

    @Test
    fun `non-vault root manifest is refused`() {
        val result = codec.decodeRoot(
            codec.encodeRoot(rootManifest().copy(app = "other-app")),
        )

        result shouldBe VaultManifestReadResult.NotVault
    }

    @Test
    fun `unknown newer manga manifest is refused`() {
        val result = codec.decodeManga(
            codec.encodeManga(mangaManifest().copy(layoutVersion = CURRENT_VAULT_LAYOUT_VERSION + 1)),
        )

        result shouldBe VaultManifestReadResult.UnsupportedNewerVersion(CURRENT_VAULT_LAYOUT_VERSION + 1)
    }

    @Test
    fun `older layout is refused until a migrator exists`() {
        val result = codec.decodeRoot(codec.encodeRoot(rootManifest().copy(layoutVersion = 0)))

        result shouldBe VaultManifestReadResult.UnsupportedOlderVersion(0)
    }

    @Test
    fun `malformed manifest is reported`() {
        val result = codec.decodeRoot("""{"app":"$CONTENT_VAULT_APP_ID"""")

        (result is VaultManifestReadResult.Malformed) shouldBe true
    }

    private fun rootManifest() = VaultRootManifest(
        identity = "vault-1",
        displayName = "My Vault",
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        revisionId = "rev-1",
        revisionNumber = 1,
        writerId = "writer-1",
        createdAt = 10,
        updatedAt = 20,
    )

    private fun mangaManifest() = VaultMangaManifest(
        layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
        vaultIdentity = "vault-1",
        mangaIdentity = "manga-1",
        revisionId = "manga-rev",
        revisionNumber = 1,
        metadata = VaultManifestMetadata(title = "One Piece"),
        createdAt = 10,
        updatedAt = 20,
    )
}

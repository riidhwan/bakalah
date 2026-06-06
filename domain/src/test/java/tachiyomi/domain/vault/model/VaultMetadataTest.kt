package tachiyomi.domain.vault.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class VaultMetadataTest {

    @Test
    fun `normalized title collapses whitespace and ignores case`() {
        VaultMetadata.normalizeTitle("  One   Piece \n Special  ") shouldBe "one piece special"
    }

    @Test
    fun `cached chapter is readable only when verified content path exists`() {
        cacheState(VaultCacheState.CACHED, localPath = "vault/cache/chapter.cbz").isReadable shouldBe true
        cacheState(VaultCacheState.CACHED, localPath = null).isReadable shouldBe false
        cacheState(VaultCacheState.VAULT_ONLY, localPath = "vault/cache/chapter.cbz").isReadable shouldBe false
    }

    private fun cacheState(
        state: VaultCacheState,
        localPath: String?,
    ) = VaultChapterCacheState(
        chapterId = 1,
        state = state,
        localPath = localPath,
        sizeBytes = 10,
        checksumSha256 = "checksum",
        lastVerifiedAt = 20,
        lastOpenedAt = null,
        updatedAt = 30,
        failureReason = null,
    )
}

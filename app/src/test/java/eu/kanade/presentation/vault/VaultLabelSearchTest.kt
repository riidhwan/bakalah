package eu.kanade.presentation.vault

import eu.kanade.presentation.vault.components.searchUnassignedVaultLabels
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultLabel

class VaultLabelSearchTest {

    @Test
    fun `exact label match is ranked before containing match`() {
        val labels = listOf(
            label(id = 1, name = "Ah", sortKey = "ah"),
            label(id = 2, name = "H", sortKey = "h"),
        )

        searchUnassignedVaultLabels(
            labels = labels,
            assignedLabelIdentities = emptySet(),
            query = "H",
        ).map { it.name }.shouldContainExactly("H")
    }

    private fun label(
        id: Long,
        name: String,
        sortKey: String = name.lowercase(),
    ): VaultLabel {
        return VaultLabel(
            id = id,
            vaultId = 1,
            identity = VaultIdentity("label-$id"),
            name = name,
            sortKey = sortKey,
            isSensitive = false,
            createdAt = 0,
            updatedAt = 0,
        )
    }
}

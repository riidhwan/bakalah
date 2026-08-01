package mihon.data.extension.model

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import mihon.domain.extension.model.ExtensionStore

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class NetworkLegacyExtensionRepo(
    val meta: Meta,
    @JsonNames("index_v2")
    val indexV2: String? = null,
) : BaseNetworkExtensionStore {
    @Serializable
    data class Meta(
        val name: String,
        val shortName: String? = null,
        val website: String,
        val signingKeyFingerprint: String,
    )

    override fun toExtensionStore(indexUrl: String): ExtensionStore {
        return ExtensionStore(
            indexUrl = indexUrl,
            name = meta.name,
            badgeLabel = meta.shortName ?: meta.name,
            signingKey = meta.signingKeyFingerprint,
            contact = ExtensionStore.Contact(
                website = meta.website,
                discord = null,
            ),
            isLegacy = true,
        )
    }
}

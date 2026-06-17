package eu.kanade.tachiyomi.data.vault.webdav

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.publishing.VaultContentUploadStorage
import eu.kanade.tachiyomi.data.vault.publishing.VaultManifestPublishStorage
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorage
import eu.kanade.tachiyomi.data.vault.remote.getBytesOrNull
import eu.kanade.tachiyomi.data.vault.remote.getTextOrNull
import eu.kanade.tachiyomi.data.vault.remote.isSuccess

internal interface VaultWebDav : VaultManifestPublishStorage, VaultContentUploadStorage {
    override suspend fun get(path: String): String?
    override suspend fun getBytes(path: String): ByteArray?
    override suspend fun put(path: String, body: String): Boolean
    override suspend fun putFile(path: String, file: UniFile): Boolean
    override suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): Boolean
    override suspend fun createDirectory(path: String): Boolean
    override suspend fun delete(path: String): Boolean
    override suspend fun promote(stagedPath: String, finalPath: String): Boolean
}

internal class RemoteVaultWebDav(
    private val storage: VaultRemoteStorage,
) : VaultWebDav {
    override suspend fun get(path: String): String? = storage.getTextOrNull(path)

    override suspend fun getBytes(path: String): ByteArray? = storage.getBytesOrNull(path)

    override suspend fun put(path: String, body: String): Boolean = storage.putText(path, body).isSuccess()

    override suspend fun putFile(path: String, file: UniFile): Boolean = storage.putFile(path, file).isSuccess()

    override suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): Boolean =
        storage.putBytes(path, bytes, mediaType).isSuccess()

    override suspend fun createDirectory(path: String): Boolean = storage.createDirectory(path).isSuccess()

    override suspend fun delete(path: String): Boolean = storage.delete(path).isSuccess()

    override suspend fun promote(stagedPath: String, finalPath: String): Boolean =
        storage.move(stagedPath, finalPath).isSuccess()
}

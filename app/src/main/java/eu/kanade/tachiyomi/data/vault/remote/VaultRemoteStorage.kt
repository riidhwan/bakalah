package eu.kanade.tachiyomi.data.vault.remote

import com.hippo.unifile.UniFile
import tachiyomi.domain.vault.model.WebDavVaultConfig

internal interface VaultRemoteStorage {
    suspend fun list(path: String): VaultRemoteListResult
    suspend fun getText(path: String): VaultRemoteReadResult<String>
    suspend fun getBytes(path: String): VaultRemoteReadResult<ByteArray>
    suspend fun putText(path: String, body: String): VaultRemoteWriteResult
    suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): VaultRemoteWriteResult
    suspend fun putFile(path: String, file: UniFile): VaultRemoteWriteResult
    suspend fun createDirectory(path: String): VaultRemoteWriteResult
    suspend fun delete(path: String): VaultRemoteWriteResult
    suspend fun move(stagedPath: String, finalPath: String): VaultRemoteWriteResult
}

internal interface VaultRemoteStorageFactory {
    fun create(config: WebDavVaultConfig): VaultRemoteStorage
}

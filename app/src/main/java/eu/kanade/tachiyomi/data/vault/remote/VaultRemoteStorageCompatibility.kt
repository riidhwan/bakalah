package eu.kanade.tachiyomi.data.vault.remote

internal suspend fun VaultRemoteStorage.getTextOrNull(path: String): String? {
    return when (val result = getText(path)) {
        is VaultRemoteReadResult.Found -> result.value
        VaultRemoteReadResult.NotFound,
        is VaultRemoteReadResult.Failed,
        -> null
    }
}

internal suspend fun VaultRemoteStorage.getBytesOrNull(path: String): ByteArray? {
    return when (val result = getBytes(path)) {
        is VaultRemoteReadResult.Found -> result.value
        VaultRemoteReadResult.NotFound,
        is VaultRemoteReadResult.Failed,
        -> null
    }
}

internal fun VaultRemoteWriteResult.isSuccess(): Boolean {
    return this is VaultRemoteWriteResult.Success
}

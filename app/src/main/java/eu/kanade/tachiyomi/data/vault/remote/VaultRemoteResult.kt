package eu.kanade.tachiyomi.data.vault.remote

internal sealed interface VaultRemoteListResult {
    data class Entries(val entries: List<VaultRemoteEntry>) : VaultRemoteListResult
    data object NotFound : VaultRemoteListResult
    data class Unauthorized(val statusCode: Int) : VaultRemoteListResult
    data class Failed(val statusCode: Int?, val detail: String? = null) : VaultRemoteListResult
}

internal sealed interface VaultRemoteReadResult<out T> {
    data class Found<T>(val value: T) : VaultRemoteReadResult<T>
    data object NotFound : VaultRemoteReadResult<Nothing>
    data class Failed(val statusCode: Int?, val detail: String? = null) : VaultRemoteReadResult<Nothing>
}

internal sealed interface VaultRemoteWriteResult {
    data object Success : VaultRemoteWriteResult
    data object NotFound : VaultRemoteWriteResult
    data class Failed(val statusCode: Int?, val detail: String? = null) : VaultRemoteWriteResult
}

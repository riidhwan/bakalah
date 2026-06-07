package eu.kanade.tachiyomi.data.vault

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.UUID

class VaultTransferService(
    private val repository: VaultRepository,
    private val remoteStorage: VaultTransferStorage,
    private val localStaging: VaultTransferLocalStaging,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun enqueue(
        vaultId: Long,
        type: VaultTransferType,
        chapterId: Long? = null,
        remotePath: String? = null,
        localPath: String? = null,
        sizeBytes: Long? = null,
        checksumSha256: String? = null,
    ): Long {
        val timestamp = now()
        val job = VaultTransferJob(
            id = -1,
            vaultId = vaultId,
            chapterId = chapterId,
            type = type,
            state = VaultTransferState.QUEUED,
            remotePath = remotePath,
            localPath = localPath,
            stagedPath = null,
            sizeBytes = sizeBytes,
            checksumSha256 = checksumSha256,
            failureReason = null,
            attempts = 0,
            createdAt = timestamp,
            updatedAt = timestamp,
            startedAt = null,
            completedAt = null,
        )
        chapterId?.let {
            repository.upsertCacheState(
                VaultChapterCacheState(
                    chapterId = it,
                    state = queuedCacheState(type),
                    localPath = localPath,
                    sizeBytes = sizeBytes,
                    checksumSha256 = checksumSha256,
                    lastVerifiedAt = null,
                    lastOpenedAt = null,
                    updatedAt = timestamp,
                    failureReason = null,
                ),
            )
        }
        return repository.upsertTransferJob(job)
    }

    suspend fun execute(jobId: Long): VaultTransferResult {
        val job = repository.getTransferJob(jobId) ?: return VaultTransferResult.NotFound
        if (job.isTerminal) return VaultTransferResult.AlreadyFinished(job.state)

        return when (job.type) {
            VaultTransferType.IMPORT_PUBLISH,
            VaultTransferType.METADATA_PUBLISH,
            -> executeUpload(job)
            VaultTransferType.CACHE_CHAPTER -> executeDownload(job)
            VaultTransferType.CATALOGUE_REFRESH -> finishSucceeded(start(job), stagedPath = null)
        }
    }

    suspend fun retry(jobId: Long): VaultTransferResult {
        val job = repository.getTransferJob(jobId) ?: return VaultTransferResult.NotFound
        if (job.state != VaultTransferState.FAILED && job.state != VaultTransferState.INTEGRITY_FAULT) {
            return VaultTransferResult.NotRetryable(job.state)
        }
        repository.upsertTransferJob(
            job.copy(
                state = VaultTransferState.QUEUED,
                failureReason = null,
                stagedPath = null,
                updatedAt = now(),
                startedAt = null,
                completedAt = null,
            ),
        )
        return execute(jobId)
    }

    suspend fun cancel(jobId: Long): VaultTransferResult {
        val job = repository.getTransferJob(jobId) ?: return VaultTransferResult.NotFound
        if (job.state == VaultTransferState.SUCCEEDED) return VaultTransferResult.AlreadyFinished(job.state)

        job.stagedPath?.let {
            remoteStorage.delete(it)
            localStaging.delete(it)
        }
        val timestamp = now()
        repository.upsertTransferJob(
            job.copy(
                state = VaultTransferState.CANCELLED,
                failureReason = null,
                stagedPath = null,
                updatedAt = timestamp,
                completedAt = timestamp,
            ),
        )
        return VaultTransferResult.Cancelled
    }

    private suspend fun executeUpload(job: VaultTransferJob): VaultTransferResult {
        val running = start(job)
        val localPath = running.localPath ?: return fail(running, "missing local path")
        val remotePath = running.remotePath ?: return fail(running, "missing remote path")
        val stagedPath = running.stagedPath ?: "$remotePath.staged-${running.id}-${UUID.randomUUID()}"
        return runCatching {
            val bytes = localStaging.read(localPath) ?: error("local content missing")
            val integrity = bytes.integrity()
            validateExpected(running, integrity)
            remoteStorage.put(stagedPath, bytes)
            val stagedBytes = remoteStorage.get(stagedPath) ?: error("staged remote content missing")
            val stagedIntegrity = stagedBytes.integrity()
            validateExpected(running, stagedIntegrity)
            remoteStorage.promote(stagedPath, remotePath)
            finishSucceeded(running, stagedPath = stagedPath, integrity = stagedIntegrity)
        }.getOrElse { error ->
            remoteStorage.delete(stagedPath)
            fail(running.copy(stagedPath = stagedPath), error.message ?: "transfer failed")
        }
    }

    private suspend fun executeDownload(job: VaultTransferJob): VaultTransferResult {
        val running = start(job)
        val remotePath = running.remotePath ?: return fail(running, "missing remote path")
        val localPath = running.localPath ?: return fail(running, "missing local path")
        val stagedPath = running.stagedPath ?: "$localPath.staged-${running.id}-${UUID.randomUUID()}"
        return runCatching {
            val bytes = remoteStorage.get(remotePath) ?: error("remote content missing")
            val integrity = bytes.integrity()
            validateExpected(running, integrity)
            localStaging.write(stagedPath, bytes)
            val stagedBytes = localStaging.read(stagedPath) ?: error("staged local content missing")
            val stagedIntegrity = stagedBytes.integrity()
            validateExpected(running, stagedIntegrity)
            localStaging.promote(stagedPath, localPath)
            running.chapterId?.let { chapterId ->
                repository.upsertCacheState(
                    VaultChapterCacheState(
                        chapterId = chapterId,
                        state = VaultCacheState.CACHED,
                        localPath = localPath,
                        sizeBytes = stagedIntegrity.sizeBytes,
                        checksumSha256 = stagedIntegrity.checksumSha256,
                        lastVerifiedAt = now(),
                        lastOpenedAt = null,
                        updatedAt = now(),
                        failureReason = null,
                    ),
                )
            }
            finishSucceeded(running, stagedPath = stagedPath, integrity = stagedIntegrity)
        }.getOrElse { error ->
            localStaging.delete(stagedPath)
            val reason = error.message ?: "transfer failed"
            running.chapterId?.let { chapterId ->
                repository.upsertCacheState(
                    VaultChapterCacheState(
                        chapterId = chapterId,
                        state = if (reason.contains("integrity", ignoreCase = true)) {
                            VaultCacheState.INTEGRITY_FAULT
                        } else {
                            VaultCacheState.FAILED
                        },
                        localPath = null,
                        sizeBytes = null,
                        checksumSha256 = null,
                        lastVerifiedAt = null,
                        lastOpenedAt = null,
                        updatedAt = now(),
                        failureReason = reason,
                    ),
                )
            }
            fail(running.copy(stagedPath = stagedPath), reason)
        }
    }

    private suspend fun start(job: VaultTransferJob): VaultTransferJob {
        val timestamp = now()
        val running = job.copy(
            state = VaultTransferState.RUNNING,
            failureReason = null,
            attempts = job.attempts + 1,
            updatedAt = timestamp,
            startedAt = timestamp,
            completedAt = null,
        )
        repository.upsertTransferJob(running)
        running.chapterId?.let { chapterId ->
            repository.upsertCacheState(
                VaultChapterCacheState(
                    chapterId = chapterId,
                    state = runningCacheState(running.type),
                    localPath = running.localPath,
                    sizeBytes = running.sizeBytes,
                    checksumSha256 = running.checksumSha256,
                    lastVerifiedAt = null,
                    lastOpenedAt = null,
                    updatedAt = timestamp,
                    failureReason = null,
                ),
            )
        }
        return running
    }

    private suspend fun finishSucceeded(
        job: VaultTransferJob,
        stagedPath: String?,
        integrity: VaultTransferIntegrity? = null,
    ): VaultTransferResult {
        stagedPath?.let {
            remoteStorage.delete(it)
            localStaging.delete(it)
        }
        val timestamp = now()
        repository.upsertTransferJob(
            job.copy(
                state = VaultTransferState.SUCCEEDED,
                stagedPath = null,
                sizeBytes = integrity?.sizeBytes ?: job.sizeBytes,
                checksumSha256 = integrity?.checksumSha256 ?: job.checksumSha256,
                failureReason = null,
                updatedAt = timestamp,
                completedAt = timestamp,
            ),
        )
        return VaultTransferResult.Succeeded
    }

    private suspend fun fail(job: VaultTransferJob, reason: String): VaultTransferResult {
        val timestamp = now()
        val state = if (reason.contains("integrity", ignoreCase = true)) {
            VaultTransferState.INTEGRITY_FAULT
        } else {
            VaultTransferState.FAILED
        }
        repository.upsertTransferJob(
            job.copy(
                state = state,
                stagedPath = null,
                failureReason = reason,
                updatedAt = timestamp,
                completedAt = timestamp,
            ),
        )
        return if (state == VaultTransferState.INTEGRITY_FAULT) {
            VaultTransferResult.IntegrityFault(reason)
        } else {
            VaultTransferResult.Failed(reason)
        }
    }

    private fun validateExpected(job: VaultTransferJob, integrity: VaultTransferIntegrity) {
        if (job.sizeBytes != null && job.sizeBytes != integrity.sizeBytes) {
            error("integrity size mismatch")
        }
        if (job.checksumSha256 != null && job.checksumSha256 != integrity.checksumSha256) {
            error("integrity checksum mismatch")
        }
    }

    private fun queuedCacheState(type: VaultTransferType): VaultCacheState {
        return when (type) {
            VaultTransferType.IMPORT_PUBLISH,
            VaultTransferType.METADATA_PUBLISH,
            -> VaultCacheState.PUBLISHING
            VaultTransferType.CACHE_CHAPTER -> VaultCacheState.QUEUED
            VaultTransferType.CATALOGUE_REFRESH -> VaultCacheState.QUEUED
        }
    }

    private fun runningCacheState(type: VaultTransferType): VaultCacheState {
        return when (type) {
            VaultTransferType.IMPORT_PUBLISH,
            VaultTransferType.METADATA_PUBLISH,
            -> VaultCacheState.PUBLISHING
            VaultTransferType.CACHE_CHAPTER -> VaultCacheState.CACHING
            VaultTransferType.CATALOGUE_REFRESH -> VaultCacheState.QUEUED
        }
    }
}

interface VaultTransferStorage {
    suspend fun get(path: String): ByteArray?
    suspend fun put(path: String, bytes: ByteArray)
    suspend fun promote(stagedPath: String, finalPath: String)
    suspend fun delete(path: String)
}

interface VaultTransferLocalStaging {
    suspend fun read(path: String): ByteArray?
    suspend fun write(path: String, bytes: ByteArray)
    suspend fun promote(stagedPath: String, finalPath: String)
    suspend fun delete(path: String)
}

class WebDavVaultTransferStorage(
    networkHelper: NetworkHelper,
    private val config: WebDavVaultConfig,
) : VaultTransferStorage {
    private val client = networkHelper.nonCloudflareClient

    override suspend fun get(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = request(path).get().build()
        client.newCall(request).await().use { response ->
            response.takeIf { it.isSuccessful }?.body?.bytes()
        }
    }

    override suspend fun put(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val request = request(path)
            .put(bytes.toRequestBody(OCTET_MEDIA_TYPE))
            .build()
        client.newCall(request).await().use { response ->
            check(response.isSuccessful) { "remote upload failed with ${response.code}" }
        }
    }

    override suspend fun promote(stagedPath: String, finalPath: String) = withContext(Dispatchers.IO) {
        val request = request(stagedPath)
            .method("MOVE", ByteArray(0).toRequestBody(null))
            .header("Destination", config.serverUrl.resolveWebDavPath(finalPath).toString())
            .header("Overwrite", "T")
            .build()
        client.newCall(request).await().use { response ->
            check(
                response.isSuccessful ||
                    response.code == HttpURLConnection.HTTP_CREATED ||
                    response.code == HttpURLConnection.HTTP_NO_CONTENT,
            ) { "remote promote failed with ${response.code}" }
        }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val request = request(path).delete().build()
        client.newCall(request).await().use { response ->
            check(response.isSuccessful || response.code == HttpURLConnection.HTTP_NOT_FOUND) {
                "remote cleanup failed with ${response.code}"
            }
        }
    }

    private fun request(path: String): Request.Builder {
        return Request.Builder()
            .url(config.serverUrl.resolveWebDavPath(path))
            .header("Authorization", Credentials.basic(config.username.trim(), config.password))
    }

    private companion object {
        val OCTET_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}

class FileVaultTransferLocalStaging(
    private val root: File,
) : VaultTransferLocalStaging {
    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        path.toFile().takeIf { it.isFile }?.readBytes()
    }

    override suspend fun write(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = path.toFile()
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override suspend fun promote(stagedPath: String, finalPath: String) = withContext(Dispatchers.IO) {
        val staged = stagedPath.toFile()
        val final = finalPath.toFile()
        final.parentFile?.mkdirs()
        if (final.exists()) final.delete()
        check(staged.renameTo(final)) { "local promote failed" }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        path.toFile().delete()
        Unit
    }

    private fun String.toFile(): File {
        val cleanPath = trim().trimStart('/')
        return File(root, cleanPath)
    }
}

sealed interface VaultTransferResult {
    data object Succeeded : VaultTransferResult
    data object Cancelled : VaultTransferResult
    data object NotFound : VaultTransferResult
    data class Failed(val reason: String) : VaultTransferResult
    data class IntegrityFault(val reason: String) : VaultTransferResult
    data class AlreadyFinished(val state: VaultTransferState) : VaultTransferResult
    data class NotRetryable(val state: VaultTransferState) : VaultTransferResult
}

data class VaultTransferIntegrity(
    val sizeBytes: Long,
    val checksumSha256: String,
)

fun ByteArray.vaultTransferIntegrity(): VaultTransferIntegrity = integrity()

private fun ByteArray.integrity(): VaultTransferIntegrity {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return VaultTransferIntegrity(
        sizeBytes = size.toLong(),
        checksumSha256 = digest.joinToString("") { "%02x".format(it) },
    )
}

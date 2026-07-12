package eu.kanade.tachiyomi.data.diagnostic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class PersistenceDiagnosticRecorder(
    private val file: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
    private val wallClock: () -> Instant = Instant::now,
    private val elapsedClock: () -> Long = System::nanoTime,
    private val stallThresholdMillis: Long = STALL_THRESHOLD_MILLIS,
    private val maxBytes: Long = MAX_BYTES,
) {

    private val lock = Any()
    private val operationIds = AtomicLong()

    suspend fun <T> trace(operation: String, block: suspend () -> T): T {
        val trace = begin(operation)

        try {
            return block().also {
                trace.finish()
            }
        } catch (error: CancellationException) {
            trace.cancel()
            throw error
        } catch (error: Throwable) {
            trace.fail()
            throw error
        }
    }

    fun begin(operation: String): Trace {
        require(operation in ALLOWED_OPERATIONS) { "Unknown diagnostic operation" }

        val operationId = operationIds.incrementAndGet()
        val startedAt = elapsedClock()
        append("START", operation, operationId, elapsedMillis = 0)
        return Trace(
            operation = operation,
            operationId = operationId,
            startedAt = startedAt,
            watchdog = startWatchdog(operation, operationId, startedAt),
        )
    }

    suspend fun snapshotTo(target: File): File = withContext(Dispatchers.IO) {
        flushWrites()
        synchronized(lock) {
            target.parentFile?.mkdirs()
            if (file.exists()) {
                file.copyTo(target, overwrite = true)
            } else {
                target.writeText("No persistence diagnostics have been recorded.\n")
            }
        }
        target
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        flushWrites()
        synchronized(lock) {
            file.delete()
        }
    }

    private fun startWatchdog(operation: String, operationId: Long, startedAt: Long): Job {
        return scope.launch {
            delay(stallThresholdMillis)
            append("STALLED", operation, operationId, elapsedMillis(startedAt))
        }
    }

    private fun append(
        event: String,
        operation: String,
        operationId: Long,
        elapsedMillis: Long,
    ) {
        val line = buildString {
            append("[DEBUG-db-stall] ")
            append(wallClock())
            append(" event=")
            append(event)
            append(" operation=")
            append(operation)
            append(" id=")
            append(operationId)
            append(" elapsed_ms=")
            append(elapsedMillis)
            append('\n')
        }

        scope.launch {
            synchronized(lock) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(line)
                    trimIfNeeded()
                }
            }
        }
    }

    private suspend fun flushWrites() {
        val flushed = CompletableDeferred<Unit>()
        scope.launch { flushed.complete(Unit) }
        flushed.await()
    }

    private fun elapsedMillis(startedAt: Long): Long {
        return (elapsedClock() - startedAt).coerceAtLeast(0) / NANOS_PER_MILLISECOND
    }

    private fun trimIfNeeded() {
        if (file.length() <= maxBytes) return

        val bytes = file.readBytes()
        val retainedStart = (bytes.size - (maxBytes / 2).toInt()).coerceAtLeast(0)
        val firstNewline = (retainedStart until bytes.size)
            .firstOrNull { bytes[it] == '\n'.code.toByte() }
            ?: -1
        val contentStart = if (firstNewline == -1) retainedStart else firstNewline + 1
        file.writeBytes(bytes.copyOfRange(contentStart, bytes.size))
    }

    inner class Trace internal constructor(
        private val operation: String,
        private val operationId: Long,
        private val startedAt: Long,
        private val watchdog: Job,
    ) {
        private var completed = false

        fun finish() = complete("FINISH")

        fun cancel() = complete("CANCEL")

        fun fail() = complete("FAIL")

        private fun complete(event: String) {
            synchronized(this) {
                if (completed) return
                completed = true
            }
            watchdog.cancel()
            append(event, operation, operationId, elapsedMillis(startedAt))
        }
    }

    companion object {
        const val READER_CHAPTER_PROGRESS = "reader.chapter_progress"
        const val READER_HISTORY = "reader.history"
        const val MANGA_INITIAL_SNAPSHOT = "manga.initial_snapshot"
        const val MANGA_DEFAULT_FLAGS = "manga.default_flags"
        const val MANGA_LIBRARY_UPDATE = "manga.library_update"
        const val MANGA_FAVORITE_WRITE = "manga.favorite_write"
        const val MANGA_CATEGORIES_WRITE = "manga.categories_write"

        private val ALLOWED_OPERATIONS = setOf(
            READER_CHAPTER_PROGRESS,
            READER_HISTORY,
            MANGA_INITIAL_SNAPSHOT,
            MANGA_DEFAULT_FLAGS,
            MANGA_LIBRARY_UPDATE,
            MANGA_FAVORITE_WRITE,
            MANGA_CATEGORIES_WRITE,
        )

        private const val STALL_THRESHOLD_MILLIS = 5_000L
        private const val MAX_BYTES = 256L * 1024L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

package eu.kanade.tachiyomi.data.diagnostic

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PersistenceDiagnosticRecorderTest {

    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `completed operation records static boundary data`() = runTest {
        val fixture = fixture(this)

        fixture.recorder.trace(PersistenceDiagnosticRecorder.READER_HISTORY) { Unit }
        advanceUntilIdle()

        fixture.file.readText().run {
            shouldContain("event=START operation=reader.history")
            shouldContain("event=FINISH operation=reader.history")
            shouldNotContain("STALLED")
        }
    }

    @Test
    fun `long operation records stalled marker before completion`() = runTest {
        val fixture = fixture(this)

        val job = backgroundScope.launch {
            fixture.recorder.trace(PersistenceDiagnosticRecorder.READER_CHAPTER_PROGRESS) {
                kotlinx.coroutines.delay(LONG_OPERATION_MILLIS)
            }
        }
        advanceTimeBy(STALL_ADVANCE_MILLIS)
        advanceUntilIdle()

        fixture.file.readText().shouldContain("event=STALLED operation=reader.chapter_progress")
        job.cancel()
    }

    @Suppress("UNUSED_EXPRESSION")
    @Test
    fun `log remains bounded`() = runTest {
        val fixture = fixture(this, maxBytes = BOUNDED_LOG_BYTES)

        repeat(EVENT_COUNT) {
            fixture.recorder.trace(PersistenceDiagnosticRecorder.MANGA_INITIAL_SNAPSHOT) { Unit }
        }
        advanceUntilIdle()

        check(fixture.file.length() <= BOUNDED_LOG_BYTES)
    }

    private fun fixture(scope: TestScope, maxBytes: Long = DEFAULT_MAX_BYTES): Fixture {
        var elapsedNanos = 0L
        val file = temporaryDirectory.resolve("persistence-diagnostics.log")
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return Fixture(
            file = file,
            recorder = PersistenceDiagnosticRecorder(
                file = file,
                scope = CoroutineScope(dispatcher),
                wallClock = { Instant.parse("2026-07-12T12:00:00Z") },
                elapsedClock = { elapsedNanos.also { elapsedNanos += NANOS_PER_MILLISECOND } },
                stallThresholdMillis = STALL_THRESHOLD_MILLIS,
                maxBytes = maxBytes,
            ),
        )
    }

    private data class Fixture(
        val file: File,
        val recorder: PersistenceDiagnosticRecorder,
    )

    private companion object {
        const val LONG_OPERATION_MILLIS = 10_000L
        const val STALL_THRESHOLD_MILLIS = 5_000L
        const val STALL_ADVANCE_MILLIS = STALL_THRESHOLD_MILLIS + 1L
        const val BOUNDED_LOG_BYTES = 512L
        const val DEFAULT_MAX_BYTES = 4_096L
        const val EVENT_COUNT = 20
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

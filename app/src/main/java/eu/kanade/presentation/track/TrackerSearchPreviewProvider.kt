package eu.kanade.presentation.track

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.tooling.preview.datasource.LoremIpsum
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.random.Random

internal class TrackerSearchPreviewProvider : PreviewParameterProvider<@Composable () -> Unit> {
    private val fullPageWithSecondSelected = @Composable {
        val items = someTrackSearches().take(PREVIEW_RESULT_COUNT).toList()
        TrackerSearch(
            state = TextFieldState(initialText = "search text"),
            onDispatchQuery = {},
            queryResult = Result.success(items),
            selected = items[1],
            onSelectedChange = {},
            onConfirmSelection = {},
            onDismissRequest = {},
            supportsPrivateTracking = false,
        )
    }
    private val fullPageWithoutSelected = @Composable {
        TrackerSearch(
            state = TextFieldState(),
            onDispatchQuery = {},
            queryResult = Result.success(someTrackSearches().take(PREVIEW_RESULT_COUNT).toList()),
            selected = null,
            onSelectedChange = {},
            onConfirmSelection = {},
            onDismissRequest = {},
            supportsPrivateTracking = false,
        )
    }
    private val loading = @Composable {
        TrackerSearch(
            state = TextFieldState(),
            onDispatchQuery = {},
            queryResult = null,
            selected = null,
            onSelectedChange = {},
            onConfirmSelection = {},
            onDismissRequest = {},
            supportsPrivateTracking = false,
        )
    }
    private val fullPageWithPrivateTracking = @Composable {
        val items = someTrackSearches().take(PREVIEW_RESULT_COUNT).toList()
        TrackerSearch(
            state = TextFieldState(initialText = "search text"),
            onDispatchQuery = {},
            queryResult = Result.success(items),
            selected = items[1],
            onSelectedChange = {},
            onConfirmSelection = {},
            onDismissRequest = {},
            supportsPrivateTracking = true,
        )
    }
    override val values: Sequence<@Composable () -> Unit> = sequenceOf(
        fullPageWithSecondSelected,
        fullPageWithoutSelected,
        loading,
        fullPageWithPrivateTracking,
    )

    private fun someTrackSearches(): Sequence<TrackSearch> = sequence {
        while (true) {
            yield(randTrackSearch())
        }
    }

    private val formatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun randTrackSearch() = TrackSearch().let {
        it.id = Random.nextLong()
        it.manga_id = Random.nextLong()
        it.tracker_id = Random.nextLong()
        it.remote_id = Random.nextLong()
        it.library_id = Random.nextLong()
        it.title = lorem((MIN_TITLE_WORDS..MAX_TITLE_WORDS).random()).joinToString()
        it.last_chapter_read = (MIN_CHAPTER_PROGRESS..MAX_CHAPTER_PROGRESS).random().toDouble()
        it.total_chapters = (MIN_TOTAL_CHAPTERS..MAX_TOTAL_CHAPTERS).random()
        it.score = (MIN_SCORE..MAX_SCORE).random().toDouble()
        it.status = Random.nextLong()
        it.started_reading_date = 0L
        it.finished_reading_date = 0L
        it.tracking_url = "https://example.com/tracker-example"
        it.cover_url = "https://example.com/cover.png"
        it.start_date =
            formatter.format(Date.from(Instant.now().minus((1L..MAX_START_DAYS_AGO).random(), ChronoUnit.DAYS)))
        it.summary = lorem((0..MAX_SUMMARY_WORDS).random()).joinToString()
        it.publishing_status = if (Random.nextBoolean()) "Finished" else ""
        it.publishing_type = if (Random.nextBoolean()) "Oneshot" else ""
        it.artists = randomNames()
        it.authors = randomNames()
        it
    }

    private fun randomNames(): List<String> = (0..(0..MAX_NAMES).random()).map {
        lorem((MIN_NAME_WORDS..MAX_NAME_WORDS).random()).joinToString()
    }

    private fun lorem(words: Int): Sequence<String> =
        LoremIpsum(words).values
}

private const val PREVIEW_RESULT_COUNT = 30
private const val MIN_TITLE_WORDS = 1
private const val MAX_TITLE_WORDS = 10
private const val MIN_CHAPTER_PROGRESS = 0
private const val MAX_CHAPTER_PROGRESS = 100
private const val MIN_TOTAL_CHAPTERS = 100L
private const val MAX_TOTAL_CHAPTERS = 1000L
private const val MIN_SCORE = 0
private const val MAX_SCORE = 10
private const val MAX_START_DAYS_AGO = 365L
private const val MAX_SUMMARY_WORDS = 40
private const val MAX_NAMES = 3
private const val MIN_NAME_WORDS = 3
private const val MAX_NAME_WORDS = 5

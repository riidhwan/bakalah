package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class MangaTrackingCoordinator(
    private val dependencies: Dependencies,
) {

    fun observeTrackingState(
        mangaId: Long,
        source: Source,
    ): Flow<MangaTrackingState> {
        return combine(
            dependencies.getTracks.subscribe(mangaId).catch { logcat(LogPriority.ERROR, it) },
            dependencies.trackerManager.loggedInTrackersFlow(),
        ) { mangaTracks, loggedInTrackers ->
            val supportedTrackers = loggedInTrackers.filter { tracker -> tracker.accepts(source) }
            val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
            val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
            MangaTrackingState(
                trackingCount = supportedTrackerTracks.size,
                hasLoggedInTrackers = supportedTrackers.isNotEmpty(),
            )
        }
    }

    suspend fun planMarkReadTrackingUpdate(
        mangaId: Long,
        chapters: List<Chapter>,
        hasLoggedInTrackers: Boolean,
        autoTrackState: AutoTrackState,
    ): MangaMarkReadTrackingResult {
        if (!hasLoggedInTrackers || autoTrackState == AutoTrackState.NEVER) {
            return MangaMarkReadTrackingResult()
        }

        val refreshFailures = refreshTrackers(mangaId)
        val tracks = dependencies.getTracks.await(mangaId)
        val maxChapterNumber = chapters.maxOf { it.chapterNumber }
        val shouldUpdateTracking = tracks.any { track -> maxChapterNumber > track.lastChapterRead }
        if (!shouldUpdateTracking) {
            return MangaMarkReadTrackingResult(refreshFailures = refreshFailures)
        }

        val update = when (autoTrackState) {
            AutoTrackState.ALWAYS -> MangaTrackingUpdate.Auto(mangaId, maxChapterNumber)
            AutoTrackState.ASK -> MangaTrackingUpdate.Prompt(mangaId, maxChapterNumber)
            AutoTrackState.NEVER -> null
        }
        return MangaMarkReadTrackingResult(
            refreshFailures = refreshFailures,
            update = update,
        )
    }

    suspend fun trackChapter(
        context: Context,
        update: MangaTrackingUpdate,
    ) {
        dependencies.trackChapter.await(context, update.mangaId, update.chapterNumber)
    }

    private suspend fun refreshTrackers(mangaId: Long): List<MangaTrackerRefreshFailure> {
        return dependencies.refreshTracks.await(mangaId)
            .mapNotNull { (tracker, error) ->
                tracker?.toRefreshFailure(error)
            }
    }

    data class Dependencies(
        val getTracks: GetTracks,
        val trackerManager: TrackerManager,
        val refreshTracks: RefreshTracks = Injekt.get(),
        val trackChapter: TrackChapter = Injekt.get(),
    )
}

internal data class MangaTrackingState(
    val trackingCount: Int,
    val hasLoggedInTrackers: Boolean,
)

internal data class MangaMarkReadTrackingResult(
    val refreshFailures: List<MangaTrackerRefreshFailure> = emptyList(),
    val update: MangaTrackingUpdate? = null,
)

internal data class MangaTrackerRefreshFailure(
    val trackerId: Long,
    val trackerName: String,
    val error: Throwable,
)

internal sealed interface MangaTrackingUpdate {
    val mangaId: Long
    val chapterNumber: Double

    data class Auto(
        override val mangaId: Long,
        override val chapterNumber: Double,
    ) : MangaTrackingUpdate

    data class Prompt(
        override val mangaId: Long,
        override val chapterNumber: Double,
    ) : MangaTrackingUpdate
}

private fun Tracker.accepts(source: Source): Boolean {
    return (this as? EnhancedTracker)?.accept(source) ?: true
}

private fun Tracker.toRefreshFailure(error: Throwable): MangaTrackerRefreshFailure {
    logcat(LogPriority.ERROR, error) {
        "Failed to refresh track data for service $id"
    }
    return MangaTrackerRefreshFailure(
        trackerId = id,
        trackerName = name,
        error = error,
    )
}

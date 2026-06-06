package eu.kanade.tachiyomi.ui.manga

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.presentation.manga.LocalMangaMetadataEditScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.metadata.LocalMangaMetadataEdit
import tachiyomi.source.local.metadata.LocalMangaMetadataWriteResult
import tachiyomi.source.local.metadata.LocalMangaMetadataWriter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalMangaMetadataEditScreen(
    private val mangaId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { LocalMangaMetadataEditScreenModel(mangaId) }
        val state by screenModel.state.collectAsState()

        LaunchedEffect(state.isSaved) {
            if (state.isSaved) {
                navigator.pop()
            }
        }

        LocalMangaMetadataEditScreen(
            state = state,
            snackbarHostState = screenModel.snackbarHostState,
            navigateUp = navigator::pop,
            onTitleChange = screenModel::setTitle,
            onAuthorChange = screenModel::setAuthor,
            onArtistChange = screenModel::setArtist,
            onDescriptionChange = screenModel::setDescription,
            onGenresChange = screenModel::setGenres,
            onStatusChange = screenModel::setStatus,
            onSave = screenModel::save,
        )
    }
}

class LocalMangaMetadataEditScreenModel(
    private val mangaId: Long,
    private val context: Application = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val fileSystem: LocalSourceFileSystem = Injekt.get(),
    private val writer: LocalMangaMetadataWriter = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<LocalMangaMetadataEditScreenModel.State>(State()) {

    private var manga: Manga? = null

    init {
        screenModelScope.launchIO {
            val loadedManga = getManga.await(mangaId)
            if (loadedManga == null) {
                showSaveError(MR.strings.unknown_error)
                return@launchIO
            }
            manga = loadedManga
            mutableState.update {
                it.copy(
                    mangaTitle = loadedManga.title,
                    title = loadedManga.title,
                    author = loadedManga.author.orEmpty(),
                    artist = loadedManga.artist.orEmpty(),
                    description = loadedManga.description.orEmpty(),
                    genres = loadedManga.genre.orEmpty().joinToString(),
                    status = loadedManga.status.toInt(),
                )
            }
        }
    }

    fun setTitle(value: String) {
        mutableState.update {
            it.copy(
                title = value,
                titleError = null,
            )
        }
    }

    fun setAuthor(value: String) {
        mutableState.update { it.copy(author = value) }
    }

    fun setArtist(value: String) {
        mutableState.update { it.copy(artist = value) }
    }

    fun setDescription(value: String) {
        mutableState.update { it.copy(description = value) }
    }

    fun setGenres(value: String) {
        mutableState.update { it.copy(genres = value) }
    }

    fun setStatus(value: Int) {
        mutableState.update { it.copy(status = value) }
    }

    fun save() {
        val currentManga = manga ?: return
        val currentState = state.value
        if (!LocalMangaMetadataEditValidator.isValidTitle(currentState.title)) {
            mutableState.update {
                it.copy(titleError = MR.strings.local_manga_metadata_title_required)
            }
            return
        }

        screenModelScope.launchIO {
            mutableState.update { it.copy(isSaving = true) }

            if (fileSystem.getMangaDirectory(currentManga.url) == null) {
                showSaveError(MR.strings.local_manga_metadata_error_missing_folder)
                mutableState.update { it.copy(isSaving = false) }
                return@launchIO
            }

            val localSource = sourceManager.get(LocalSource.ID) as? LocalSource
            if (localSource == null) {
                showSaveError(MR.strings.local_manga_metadata_error_missing_folder)
                mutableState.update { it.copy(isSaving = false) }
                return@launchIO
            }

            val edit = LocalMangaMetadataEdit(
                mangaUrl = currentManga.url,
                title = currentState.title,
                author = LocalMangaMetadataEditValidator.normalizeOptional(currentState.author),
                artist = LocalMangaMetadataEditValidator.normalizeOptional(currentState.artist),
                description = LocalMangaMetadataEditValidator.normalizeOptional(currentState.description),
                genres = LocalMangaMetadataEditValidator.parseGenres(currentState.genres),
                status = currentState.status,
            )

            when (val result = writer.write(edit)) {
                LocalMangaMetadataWriteResult.Success -> {
                    refreshAndPersist(currentManga, localSource)
                }
                LocalMangaMetadataWriteResult.BlankTitle -> {
                    mutableState.update {
                        it.copy(
                            isSaving = false,
                            titleError = MR.strings.local_manga_metadata_title_required,
                        )
                    }
                }
                LocalMangaMetadataWriteResult.MangaDirectoryNotFound -> {
                    showSaveError(MR.strings.local_manga_metadata_error_missing_folder)
                    mutableState.update { it.copy(isSaving = false) }
                }
                is LocalMangaMetadataWriteResult.MalformedExistingMetadata -> {
                    logcat(LogPriority.ERROR, result.cause) { "Malformed local manga metadata for mangaId=$mangaId" }
                    showSaveError(MR.strings.local_manga_metadata_error_malformed_metadata)
                    mutableState.update { it.copy(isSaving = false) }
                }
                is LocalMangaMetadataWriteResult.WriteFailure -> {
                    logcat(LogPriority.ERROR, result.cause) {
                        "Failed to write local manga metadata for mangaId=$mangaId"
                    }
                    showSaveError(MR.strings.local_manga_metadata_error_write_failed)
                    mutableState.update { it.copy(isSaving = false) }
                }
            }
        }
    }

    private suspend fun refreshAndPersist(
        currentManga: Manga,
        localSource: LocalSource,
    ) {
        try {
            val refreshedManga = localSource.getMangaDetails(currentManga.toLocalMetadataRefreshSManga())
            updateManga.awaitUpdateFromSource(
                localManga = currentManga,
                remoteManga = refreshedManga,
                manualFetch = true,
                forceTitleUpdate = true,
                clearMissingMetadata = true,
            )
            mutableState.update {
                it.copy(
                    isSaving = false,
                    isSaved = true,
                )
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to refresh local manga metadata for mangaId=$mangaId" }
            showSaveError(MR.strings.local_manga_metadata_error_refresh_failed)
            mutableState.update { it.copy(isSaving = false) }
        }
    }

    private suspend fun showSaveError(message: StringResource) {
        snackbarHostState.showSnackbar(message = context.stringResource(message))
    }

    @Immutable
    data class State(
        val mangaTitle: String = "",
        val title: String = "",
        val author: String = "",
        val artist: String = "",
        val description: String = "",
        val genres: String = "",
        val status: Int = eu.kanade.tachiyomi.source.model.SManga.UNKNOWN,
        val titleError: StringResource? = null,
        val isSaving: Boolean = false,
        val isSaved: Boolean = false,
    )
}

internal fun Manga.toLocalMetadataRefreshSManga(): SManga {
    return SManga.create().also {
        it.url = url
        it.title = title
        it.initialized = initialized
    }
}

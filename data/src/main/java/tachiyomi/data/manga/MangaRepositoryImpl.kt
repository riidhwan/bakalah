package tachiyomi.data.manga

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOne
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.LibraryMangaGroup
import tachiyomi.domain.manga.model.LibraryMangaGroupCandidate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

class MangaRepositoryImpl(
    private val database: Database,
) : MangaRepository {

    override suspend fun getMangaById(id: Long): Manga {
        return database.mangasQueries
            .getMangaById(id, MangaMapper::mapManga)
            .awaitAsOne()
    }

    override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> {
        return database.mangasQueries
            .getMangaById(id, MangaMapper::mapManga)
            .subscribeToOne()
    }

    override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
        return database.mangasQueries
            .getMangaByUrlAndSource(url, sourceId, MangaMapper::mapManga)
            .awaitAsOneOrNull()
    }

    override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> {
        return database.mangasQueries
            .getMangaByUrlAndSource(url, sourceId, MangaMapper::mapManga)
            .subscribeToOneOrNull()
    }

    override suspend fun getFavorites(): List<Manga> {
        return database.mangasQueries
            .getFavorites(MangaMapper::mapManga)
            .awaitAsList()
    }

    override suspend fun getReadMangaNotInLibrary(): List<Manga> {
        return database.mangasQueries
            .getReadMangaNotInLibrary(MangaMapper::mapManga)
            .awaitAsList()
    }

    override suspend fun getLibraryManga(): List<LibraryManga> {
        return database.libraryViewQueries
            .library(MangaMapper::mapLibraryManga)
            .awaitAsList()
    }

    override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> {
        return database.libraryViewQueries
            .library(MangaMapper::mapLibraryManga)
            .subscribeToList()
    }

    override suspend fun getLibraryMangaGroupForManga(mangaId: Long): LibraryMangaGroup? {
        val groupId = database.libraryMangaGroupsQueries
            .getGroupIdForManga(mangaId)
            .awaitAsOneOrNull()
            ?: return null

        val members = database.libraryMangaGroupsQueries
            .getGroupMembers(groupId, MangaMapper::mapLibraryMangaGroupMember)
            .awaitAsList()
            .filter { it.manga.favorite }

        return LibraryMangaGroup(groupId, members)
            .takeIf { it.members.size >= MIN_LIBRARY_MANGA_GROUP_MEMBERS }
    }

    override suspend fun getLibraryMangaGroupCandidates(
        anchorMangaId: Long,
        groupId: Long?,
    ): List<LibraryMangaGroupCandidate> {
        return database.libraryMangaGroupsQueries
            .getCandidateManga(
                anchorMangaId,
                groupId ?: NO_LIBRARY_MANGA_GROUP,
                MangaMapper::mapLibraryMangaGroupCandidate,
            )
            .awaitAsList()
    }

    override suspend fun createLibraryMangaGroup(primaryMangaId: Long, memberMangaIds: List<Long>): Long {
        val uniqueMemberIds = (listOf(primaryMangaId) + memberMangaIds.filterNot { it == primaryMangaId }).distinct()
        require(uniqueMemberIds.size >= MIN_LIBRARY_MANGA_GROUP_MEMBERS) {
            "A library manga group requires at least two members"
        }
        validateNewGroupMembers(uniqueMemberIds, groupId = null)

        return database.transactionWithResult {
            val groupId = database.libraryMangaGroupsQueries.createGroup().awaitAsOne()
            uniqueMemberIds.forEach { mangaId ->
                database.libraryMangaGroupsQueries.insertMember(
                    groupId = groupId,
                    mangaId = mangaId,
                    isPrimary = mangaId == primaryMangaId,
                )
            }
            groupId
        }
    }

    override suspend fun addMangaToLibraryMangaGroup(groupId: Long, memberMangaIds: List<Long>) {
        val existingMemberIds = getLibraryMangaGroup(groupId).memberMangaIds.toSet()
        val uniqueMemberIds = memberMangaIds
            .filterNot { it in existingMemberIds }
            .distinct()
        if (uniqueMemberIds.isEmpty()) return
        validateNewGroupMembers(uniqueMemberIds, groupId = groupId)

        database.transaction {
            uniqueMemberIds.forEach { mangaId ->
                database.libraryMangaGroupsQueries.insertMember(
                    groupId = groupId,
                    mangaId = mangaId,
                    isPrimary = false,
                )
            }
        }
    }

    override suspend fun setLibraryMangaGroupPrimary(groupId: Long, mangaId: Long) {
        val group = getLibraryMangaGroup(groupId)
        require(group.members.any { it.manga.id == mangaId }) {
            "Primary manga must be a member of the library manga group"
        }

        database.transaction {
            database.libraryMangaGroupsQueries.clearPrimary(groupId)
            database.libraryMangaGroupsQueries.setPrimary(groupId, mangaId)
        }
    }

    override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> {
        return database.mangasQueries
            .getFavoriteBySourceId(sourceId, MangaMapper::mapManga)
            .subscribeToList()
    }

    override suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount> {
        return database.mangasQueries
            .getDuplicateLibraryManga(id, title, MangaMapper::mapMangaWithChapterCount)
            .awaitAsList()
    }

    override suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>> {
        val epochMillis = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
        return database.mangasQueries
            .getUpcomingManga(epochMillis, statuses, MangaMapper::mapManga)
            .subscribeToList()
    }

    override suspend fun resetViewerFlags(): Boolean {
        return try {
            database.mangasQueries.resetViewerFlags()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
        database.transaction {
            database.mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
            categoryIds.forEach { categoryId ->
                database.mangas_categoriesQueries.insert(mangaId, categoryId)
            }
        }
    }

    override suspend fun deleteById(id: Long): Boolean {
        return try {
            database.mangasQueries.deleteMangaById(id)
            cleanupLibraryMangaGroups()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun update(update: MangaUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return try {
            partialUpdate(*mangaUpdates.toTypedArray())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> {
        return database.transactionWithResult {
            manga.map {
                database.mangasQueries.insertNetworkManga(
                    source = it.source,
                    url = it.url,
                    artist = it.artist,
                    author = it.author,
                    description = it.description,
                    genre = it.genre,
                    title = it.title,
                    status = it.status,
                    thumbnailUrl = it.thumbnailUrl,
                    favorite = it.favorite,
                    lastUpdate = it.lastUpdate,
                    nextUpdate = it.nextUpdate,
                    calculateInterval = it.fetchInterval.toLong(),
                    initialized = it.initialized,
                    viewerFlags = it.viewerFlags,
                    chapterFlags = it.chapterFlags,
                    coverLastModified = it.coverLastModified,
                    dateAdded = it.dateAdded,
                    updateStrategy = it.updateStrategy,
                    version = it.version,
                    updateTitle = it.title.isNotBlank(),
                    updateCover = !it.thumbnailUrl.isNullOrBlank(),
                    updateDetails = it.initialized,
                    mapper = MangaMapper::mapManga,
                )
                    .awaitAsOne()
            }
        }
    }

    private suspend fun partialUpdate(vararg mangaUpdates: MangaUpdate) {
        database.transaction {
            mangaUpdates.forEach { value ->
                database.mangasQueries.update(
                    source = value.source,
                    url = value.url,
                    artist = value.artist,
                    author = value.author,
                    description = value.description,
                    genre = value.genre?.let(StringListColumnAdapter::encode),
                    title = value.title,
                    status = value.status,
                    thumbnailUrl = value.thumbnailUrl,
                    favorite = value.favorite,
                    lastUpdate = value.lastUpdate,
                    nextUpdate = value.nextUpdate,
                    calculateInterval = value.fetchInterval?.toLong(),
                    initialized = value.initialized,
                    viewer = value.viewerFlags,
                    chapterFlags = value.chapterFlags,
                    coverLastModified = value.coverLastModified,
                    dateAdded = value.dateAdded,
                    mangaId = value.id,
                    updateStrategy = value.updateStrategy?.let(UpdateStrategyColumnAdapter::encode),
                    version = value.version,
                    isSyncing = 0,
                    notes = value.notes,
                )
            }
        }
        cleanupLibraryMangaGroups()
    }

    private suspend fun getLibraryMangaGroup(groupId: Long): LibraryMangaGroup {
        val members = database.libraryMangaGroupsQueries
            .getGroupMembers(groupId, MangaMapper::mapLibraryMangaGroupMember)
            .awaitAsList()
        require(members.isNotEmpty()) {
            "Library manga group does not exist"
        }
        return LibraryMangaGroup(groupId, members)
    }

    private suspend fun validateNewGroupMembers(mangaIds: List<Long>, groupId: Long?) {
        val existingGroup = groupId?.let { getLibraryMangaGroup(it) }
        val existingSourceIds = existingGroup
            ?.members
            .orEmpty()
            .map { it.manga.source }
            .toSet()

        val newManga = mangaIds.map { id ->
            database.mangasQueries.getMangaById(id, MangaMapper::mapManga).awaitAsOne()
        }
        require(newManga.all { it.favorite && it.source != LOCAL_SOURCE_ID }) {
            "Library manga groups can only contain source-backed library manga"
        }

        val duplicateSourceId = newManga
            .groupingBy { it.source }
            .eachCount()
            .any { (sourceId, count) -> count > 1 || sourceId in existingSourceIds }
        require(!duplicateSourceId) {
            "Library manga groups can only contain one member per source"
        }

        newManga.forEach { manga ->
            val memberGroupId = database.libraryMangaGroupsQueries
                .getGroupIdForManga(manga.id)
                .awaitAsOneOrNull()
            require(memberGroupId == null || memberGroupId == groupId) {
                "Library manga group members cannot belong to another group"
            }
        }
    }

    private suspend fun cleanupLibraryMangaGroups() {
        database.libraryMangaGroupsQueries.deleteMembershipsForNonLibraryManga()
        database.libraryMangaGroupsQueries.deleteDissolvedGroups()
    }

    private companion object {
        const val NO_LIBRARY_MANGA_GROUP = -1L
        const val LOCAL_SOURCE_ID = 0L
        const val MIN_LIBRARY_MANGA_GROUP_MEMBERS = 2
    }
}

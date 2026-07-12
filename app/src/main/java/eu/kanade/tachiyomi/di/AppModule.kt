package eu.kanade.tachiyomi.di

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseViewerService
import eu.kanade.tachiyomi.data.diagnostic.PersistenceDiagnosticRecorder
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.local.ActiveLocalReaderSessions
import eu.kanade.tachiyomi.data.local.LocalMangaDeletionService
import eu.kanade.tachiyomi.data.local.LocalSourceChangeNotifier
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.vault.capture.DefaultLibraryVaultChapterStager
import eu.kanade.tachiyomi.data.vault.capture.LibraryVaultCaptureService
import eu.kanade.tachiyomi.data.vault.capture.LibraryVaultChapterPublisher
import eu.kanade.tachiyomi.data.vault.capture.LibraryVaultChapterPublisherBoundary
import eu.kanade.tachiyomi.data.vault.capture.LibraryVaultChapterStager
import eu.kanade.tachiyomi.data.vault.export.VaultChapterExportService
import eu.kanade.tachiyomi.data.vault.localimport.LocalVaultChapterPublisher
import eu.kanade.tachiyomi.data.vault.localimport.LocalVaultChapterPublisherBoundary
import eu.kanade.tachiyomi.data.vault.localimport.LocalVaultChapterStager
import eu.kanade.tachiyomi.data.vault.localimport.LocalVaultImportService
import eu.kanade.tachiyomi.data.vault.localimport.LocalVaultMangaScanner
import eu.kanade.tachiyomi.data.vault.localimport.LocalVaultMangaScannerBoundary
import eu.kanade.tachiyomi.data.vault.operation.VaultChapterDeleteOperationHandler
import eu.kanade.tachiyomi.data.vault.operation.VaultChapterRenameOperationHandler
import eu.kanade.tachiyomi.data.vault.operation.VaultMetadataPublishOperationHandler
import eu.kanade.tachiyomi.data.vault.operation.VaultOperationHandler
import eu.kanade.tachiyomi.data.vault.operation.VaultOperationManager
import eu.kanade.tachiyomi.data.vault.operation.VaultOperationNotificationCoordinator
import eu.kanade.tachiyomi.data.vault.operation.VaultOperationNotifier
import eu.kanade.tachiyomi.data.vault.operation.VaultOperationQueueDrainService
import eu.kanade.tachiyomi.data.vault.operation.VaultOperationQueueDrainer
import eu.kanade.tachiyomi.data.vault.operation.VaultOperationQueueWakeup
import eu.kanade.tachiyomi.data.vault.publishing.DefaultVaultChapterThumbnailCacheStore
import eu.kanade.tachiyomi.data.vault.publishing.DefaultVaultChapterThumbnailDisplayLoader
import eu.kanade.tachiyomi.data.vault.publishing.DefaultVaultChapterThumbnailPublishService
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterDeletionService
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterRenameService
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterThumbnailCacheStore
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterThumbnailDisplayLoader
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterThumbnailPublishService
import eu.kanade.tachiyomi.data.vault.publishing.VaultCoverPublishService
import eu.kanade.tachiyomi.data.vault.publishing.VaultMangaDeletionService
import eu.kanade.tachiyomi.data.vault.publishing.VaultManifestPublishGate
import eu.kanade.tachiyomi.data.vault.publishing.VaultMetadataPublishService
import eu.kanade.tachiyomi.data.vault.reader.ActiveVaultReaderSessions
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefreshService
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefresher
import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.remote.webdav.WebDavVaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.setup.ContentVaultSetupService
import eu.kanade.tachiyomi.data.vault.webdav.LibraryVaultCaptureWebDavFactory
import eu.kanade.tachiyomi.data.vault.webdav.LibraryVaultCaptureWebDavFactoryBoundary
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.AndroidSourceManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.storage.AndroidStorageFolderProvider
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.VaultCacheStateColumnAdapter
import tachiyomi.data.VaultChapterContentFormatColumnAdapter
import tachiyomi.data.VaultMangaStatusColumnAdapter
import tachiyomi.data.VaultTransferStateColumnAdapter
import tachiyomi.data.VaultTransferTypeColumnAdapter
import tachiyomi.data.Vault_chapter_cache_state
import tachiyomi.data.Vault_chapters
import tachiyomi.data.Vault_mangas
import tachiyomi.data.Vault_transfer_jobs
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.metadata.LocalMangaMetadataWriter
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
import java.lang.ref.WeakReference

private val lock = Any()

class AppModule(val app: Application) : InjektModule {

    private var sqlDriverRef: WeakReference<SqlDriver>? = null

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)

        addSingletonFactory<SqlDriver> {
            synchronized(lock) {
                sqlDriverRef?.get()?.let { return@synchronized it }

                AndroidxSqliteDriver(
                    driver = BundledSQLiteDriver(),
                    databaseType = AndroidxSqliteDatabaseType.FileProvider(app, "tachiyomi.db"),
                    schema = Database.Schema,
                    configuration = AndroidxSqliteConfiguration(
                        isForeignKeyConstraintsEnabled = true,
                    ),
                )
                    .also { sqlDriverRef = WeakReference(it) }
            }
        }
        addSingletonFactory {
            Database(
                driver = get(),
                historyAdapter = History.Adapter(
                    last_readAdapter = DateColumnAdapter,
                ),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
                vault_chapter_cache_stateAdapter = Vault_chapter_cache_state.Adapter(
                    stateAdapter = VaultCacheStateColumnAdapter,
                ),
                vault_chaptersAdapter = Vault_chapters.Adapter(
                    content_formatAdapter = VaultChapterContentFormatColumnAdapter,
                ),
                vault_mangasAdapter = Vault_mangas.Adapter(
                    statusAdapter = VaultMangaStatusColumnAdapter,
                ),
                vault_transfer_jobsAdapter = Vault_transfer_jobs.Adapter(
                    typeAdapter = VaultTransferTypeColumnAdapter,
                    stateAdapter = VaultTransferStateColumnAdapter,
                ),
            )
        }

        addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        addSingletonFactory {
            XML {
                defaultPolicy {
                    ignoreUnknownChildren()
                }
                autoPolymorphic = true
                xmlDeclMode = XmlDeclMode.Charset
                indent = 2
                xmlVersion = XmlVersion.XML10
            }
        }
        addSingletonFactory<ProtoBuf> {
            ProtoBuf
        }

        addSingletonFactory { DatabaseViewerService(get()) }
        addSingletonFactory {
            PersistenceDiagnosticRecorder(app.filesDir.resolve("diagnostics/persistence.log"))
        }

        addSingletonFactory { ChapterCache(app, get()) }
        addSingletonFactory { CoverCache(app) }
        addSingletonFactory { ActiveLocalReaderSessions() }
        addSingletonFactory { LocalSourceChangeNotifier() }
        addSingletonFactory { LocalMangaDeletionService(get(), get(), get(), get(), get(), get(), get()) }

        addSingletonFactory { NetworkHelper(app, get()) }
        addSingletonFactory<VaultRemoteStorageFactory> { WebDavVaultRemoteStorageFactory(get()) }
        addSingletonFactory { ContentVaultSetupService(get(), get(), get(), get()) }
        addSingletonFactory { VaultCatalogueRefreshService(get<NetworkHelper>(), get(), get(), get()) }
        addSingletonFactory<VaultCatalogueRefresher> { get<VaultCatalogueRefreshService>() }
        addSingletonFactory { ActiveVaultReaderSessions() }
        addSingletonFactory {
            VaultMangaDeletionService(get<NetworkHelper>(), get(), get(), get(), get(), get(), get())
        }
        addSingletonFactory {
            VaultChapterDeletionService(get<NetworkHelper>(), get(), get(), get(), get(), get())
        }
        addSingletonFactory { VaultChapterRenameService(get<NetworkHelper>(), get(), get(), get()) }
        addSingletonFactory { VaultCoverPublishService(get<NetworkHelper>(), get(), get(), get(), get(), get()) }
        addSingletonFactory { VaultMetadataPublishService(get<NetworkHelper>(), get(), get(), get()) }
        addSingletonFactory { VaultManifestPublishGate() }
        addSingletonFactory { VaultOperationNotifier(app) }
        addSingletonFactory { VaultOperationNotificationCoordinator(get(), get()) }
        addSingletonFactory { VaultMetadataPublishOperationHandler(get(), get()) }
        addSingletonFactory { VaultChapterDeleteOperationHandler(get(), get()) }
        addSingletonFactory { VaultChapterRenameOperationHandler(get(), get()) }
        addSingletonFactory<List<VaultOperationHandler>> {
            listOf(
                get<VaultMetadataPublishOperationHandler>(),
                get<VaultChapterDeleteOperationHandler>(),
                get<VaultChapterRenameOperationHandler>(),
            )
        }
        addSingletonFactory { VaultOperationManager(app, get(), get(), get()) }
        addSingletonFactory<VaultOperationQueueWakeup> { get<VaultOperationManager>() }
        addSingletonFactory<VaultOperationQueueDrainer> { VaultOperationQueueDrainService(get(), get()) }
        addSingletonFactory<VaultChapterThumbnailCacheStore> { DefaultVaultChapterThumbnailCacheStore(get()) }
        addSingletonFactory<VaultChapterThumbnailPublishService> {
            DefaultVaultChapterThumbnailPublishService(get(), get(), get(), get(), get(), get())
        }
        addSingletonFactory<VaultChapterThumbnailDisplayLoader> {
            DefaultVaultChapterThumbnailDisplayLoader(get(), get(), get(), get())
        }
        addSingletonFactory<LocalVaultMangaScannerBoundary> { LocalVaultMangaScanner(get(), get(), get(), get()) }
        addSingletonFactory { LocalVaultChapterStager() }
        addSingletonFactory<LocalVaultChapterPublisherBoundary> {
            LocalVaultChapterPublisher(get(), get(), get(), get(), get(), get())
        }
        addSingletonFactory<LibraryVaultCaptureWebDavFactoryBoundary> { LibraryVaultCaptureWebDavFactory(get()) }
        addSingletonFactory<LibraryVaultChapterStager> { DefaultLibraryVaultChapterStager(app, get(), get()) }
        addSingletonFactory<LibraryVaultChapterPublisherBoundary> {
            LibraryVaultChapterPublisher(get(), get(), get(), get(), get(), get())
        }
        addSingletonFactory {
            LocalVaultImportService(app, get(), get(), get(), get(), get(), get())
        }
        addSingletonFactory {
            LibraryVaultCaptureService(app, get(), get(), get(), get(), get(), get(), get())
        }
        addSingletonFactory { JavaScriptEngine(app) }

        addSingletonFactory<SourceManager> { AndroidSourceManager(app, get(), get()) }
        addSingletonFactory { ExtensionManager(app) }

        addSingletonFactory { DownloadProvider(app) }
        addSingletonFactory { DownloadManager(app) }
        addSingletonFactory { DownloadCache(app) }

        addSingletonFactory { TrackerManager() }
        addSingletonFactory { DelayedTrackingStore(app) }

        addSingletonFactory { ImageSaver(app) }
        addSingletonFactory { VaultChapterExportService(app, get(), get()) }

        addSingletonFactory { AndroidStorageFolderProvider(app) }
        addSingletonFactory { LocalSourceFileSystem(get()) }
        addSingletonFactory { LocalCoverManager(app, get()) }
        addSingletonFactory { LocalMangaMetadataWriter(get(), get()) }
        addSingletonFactory { StorageManager(app, get()) }

        // Asynchronously init expensive components for a faster cold start
        ContextCompat.getMainExecutor(app).execute {
            get<NetworkHelper>()

            get<SourceManager>()

            get<Database>()

            get<DownloadManager>()
        }
    }
}

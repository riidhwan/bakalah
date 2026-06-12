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
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.vault.ActiveVaultReaderSessions
import eu.kanade.tachiyomi.data.vault.ContentVaultSetupService
import eu.kanade.tachiyomi.data.vault.LibraryVaultCaptureService
import eu.kanade.tachiyomi.data.vault.LocalVaultImportService
import eu.kanade.tachiyomi.data.vault.VaultCatalogueRefreshService
import eu.kanade.tachiyomi.data.vault.VaultCoverPublishService
import eu.kanade.tachiyomi.data.vault.VaultMangaDeletionService
import eu.kanade.tachiyomi.data.vault.VaultMetadataPublishService
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

        addSingletonFactory { ChapterCache(app, get()) }
        addSingletonFactory { CoverCache(app) }

        addSingletonFactory { NetworkHelper(app, get()) }
        addSingletonFactory { ContentVaultSetupService(get(), get(), get(), get()) }
        addSingletonFactory { VaultCatalogueRefreshService(get(), get(), get(), get()) }
        addSingletonFactory { ActiveVaultReaderSessions() }
        addSingletonFactory { VaultMangaDeletionService(get(), get(), get(), get(), get(), get(), get()) }
        addSingletonFactory { VaultCoverPublishService(get(), get(), get(), get(), get(), get()) }
        addSingletonFactory { VaultMetadataPublishService(get(), get(), get(), get(), get()) }
        addSingletonFactory {
            LocalVaultImportService(app, get(), get(), get(), get(), get(), get(), get(), get(), get())
        }
        addSingletonFactory {
            LibraryVaultCaptureService(app, get(), get(), get(), get(), get(), get(), get(), get(), get())
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

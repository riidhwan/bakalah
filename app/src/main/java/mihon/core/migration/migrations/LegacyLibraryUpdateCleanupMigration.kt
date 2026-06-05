package mihon.core.migration.migrations

import android.app.Application
import androidx.work.WorkManager
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext

class LegacyLibraryUpdateCleanupMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(WORK_NAME_AUTO)
        workManager.cancelUniqueWork(WORK_NAME_MANUAL)
        workManager.cancelAllWorkByTag(TAG)
        return true
    }

    private companion object {
        const val TAG = "LibraryUpdate"
        const val WORK_NAME_AUTO = "LibraryUpdate-auto"
        const val WORK_NAME_MANUAL = "LibraryUpdate-manual"
    }
}

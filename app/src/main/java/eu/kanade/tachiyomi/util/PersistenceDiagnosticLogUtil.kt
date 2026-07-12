package eu.kanade.tachiyomi.util

import android.content.Context
import eu.kanade.tachiyomi.data.diagnostic.PersistenceDiagnosticRecorder
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PersistenceDiagnosticLogUtil(
    private val context: Context,
    private val recorder: PersistenceDiagnosticRecorder = Injekt.get(),
) {

    suspend fun share() {
        runCatching {
            val snapshot = context.createFileInCacheDir("bakalah_persistence_diagnostics.txt")
            recorder.snapshotTo(snapshot)
            val uri = snapshot.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, "text/plain"))
        }.onFailure {
            withUIContext { context.toast(MR.strings.persistence_diagnostics_export_failed) }
        }
    }

    suspend fun clear() {
        recorder.clear()
    }
}

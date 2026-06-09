package eu.kanade.tachiyomi.data.vault

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.math.RoundingMode
import java.text.NumberFormat

class LocalVaultImportNotifier(
    private val context: Context,
) {
    private val percentFormatter = NumberFormat.getPercentInstance().apply {
        roundingMode = RoundingMode.DOWN
        maximumFractionDigits = 0
    }

    private val progressNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_VAULT_PROGRESS,
    ) {
        setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        setSmallIcon(R.drawable.ic_mihon)
        setAutoCancel(false)
        setOngoing(true)
        setOnlyAlertOnce(true)
    }

    fun showPreparing(mangaTitle: String): NotificationCompat.Builder {
        val builder = progressNotificationBuilder
            .setContentTitle(context.stringResource(MR.strings.vault_importing))
            .setContentText(mangaTitle)
            .setProgress(0, 0, true)

        context.notify(Notifications.ID_VAULT_IMPORT_PROGRESS, builder.build())
        return builder
    }

    fun showProgress(progress: LocalVaultImportProgress) {
        val percent = percentFormatter.format(progress.current.toFloat() / progress.total.coerceAtLeast(1))
        val text = progress.chapterTitle
            ?.let { context.stringResource(MR.strings.vault_import_progress_detail, it) }
            ?: context.stringResource(MR.strings.vault_import_phase_text)

        context.notify(
            Notifications.ID_VAULT_IMPORT_PROGRESS,
            progressNotificationBuilder
                .setContentTitle(context.stringResource(MR.strings.vault_import_progress_title, percent))
                .setContentText(text)
                .setProgress(progress.total, progress.current, false)
                .build(),
        )
    }

    fun showComplete(importedChapterCount: Int, skippedExactDuplicateCount: Int) {
        context.notify(
            Notifications.ID_VAULT_IMPORT_PROGRESS,
            progressNotificationBuilder
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentTitle(context.stringResource(MR.strings.vault_import_complete))
                .setContentText(
                    context.stringResource(
                        MR.strings.vault_import_success,
                        importedChapterCount,
                        skippedExactDuplicateCount,
                    ),
                )
                .setProgress(0, 0, false)
                .build(),
        )
    }

    fun showError() {
        context.notify(
            Notifications.ID_VAULT_IMPORT_PROGRESS,
            progressNotificationBuilder
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentTitle(context.stringResource(MR.strings.vault_import_error_upload_failed))
                .setContentText(context.stringResource(MR.strings.vault_import_error_background_failed))
                .setProgress(0, 0, false)
                .build(),
        )
    }

    fun cancel() {
        context.cancelNotification(Notifications.ID_VAULT_IMPORT_PROGRESS)
    }
}

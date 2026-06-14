package eu.kanade.tachiyomi.data.vault.localimport

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
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
        setSmallIcon(R.drawable.ic_vault_progress_0_24dp)
        setOnlyAlertOnce(true)
    }

    private val completeNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_VAULT_PROGRESS,
    ) {
        setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        setSmallIcon(R.drawable.ic_mihon)
        setAutoCancel(true)
        setOngoing(false)
        setOnlyAlertOnce(true)
    }

    fun showPreparing(mangaTitle: String): NotificationCompat.Builder {
        context.cancelNotification(Notifications.ID_VAULT_IMPORT_COMPLETE)
        val builder = preparingBuilder(mangaTitle)

        context.notify(Notifications.ID_VAULT_IMPORT_PROGRESS, builder.build())
        return builder
    }

    fun showProgress(progress: LocalVaultImportProgress) {
        val title = progress.progressTitle()
        if (progress.indeterminate) {
            val text = progress.phaseText()

            context.notify(
                Notifications.ID_VAULT_IMPORT_PROGRESS,
                progressNotificationBuilder
                    .asLockedProgress()
                    .setSmallIcon(progress.statusBarIcon())
                    .setContentTitle(title)
                    .setContentText(text)
                    .setProgress(0, 0, true)
                    .withCancelAction()
                    .build(),
            )
            return
        }

        val text = progress.phaseText()

        context.notify(
            Notifications.ID_VAULT_IMPORT_PROGRESS,
            progressNotificationBuilder
                .asLockedProgress()
                .setSmallIcon(progress.statusBarIcon())
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(progress.total, progress.current, false)
                .withCancelAction()
                .build(),
        )
    }

    private fun LocalVaultImportProgress.progressTitle(): String {
        if (total <= 0) return context.stringResource(MR.strings.vault_importing)

        val percent = percentFormatter.format(current.toFloat() / total.coerceAtLeast(1))
        return context.stringResource(MR.strings.vault_import_progress_title, percent)
    }

    private fun LocalVaultImportProgress.statusBarIcon(): Int {
        if (total <= 0) return R.drawable.ic_vault_progress_0_24dp

        return when ((current.coerceAtLeast(0) * 100) / total.coerceAtLeast(1)) {
            in 75..Int.MAX_VALUE -> R.drawable.ic_vault_progress_75_24dp
            in 50..74 -> R.drawable.ic_vault_progress_50_24dp
            in 25..49 -> R.drawable.ic_vault_progress_25_24dp
            else -> R.drawable.ic_vault_progress_0_24dp
        }
    }

    private fun preparingBuilder(mangaTitle: String): NotificationCompat.Builder {
        return progressNotificationBuilder
            .asLockedProgress()
            .setSmallIcon(R.drawable.ic_vault_progress_0_24dp)
            .setContentTitle(context.stringResource(MR.strings.vault_importing))
            .setContentText(mangaTitle)
            .setProgress(0, 0, true)
            .withCancelAction()
    }

    private fun LocalVaultImportProgress.phaseText(): String {
        val title = chapterTitle
        return when (phase) {
            VaultImportProgressPhase.PREPARING -> title?.let {
                context.stringResource(MR.strings.vault_import_phase_preparing, it)
            }
            VaultImportProgressPhase.COPYING_DOWNLOADED -> title?.let {
                context.stringResource(MR.strings.vault_import_phase_copying_downloaded, it)
            }
            VaultImportProgressPhase.DOWNLOADING -> title?.let {
                context.stringResource(MR.strings.vault_import_phase_downloading, it)
            }
            VaultImportProgressPhase.COMPRESSING -> title?.let {
                context.stringResource(MR.strings.vault_import_phase_compressing, it)
            }
            VaultImportProgressPhase.UPLOADING -> title?.let {
                context.stringResource(MR.strings.vault_import_phase_uploading, it)
            }
            VaultImportProgressPhase.PUBLISHING -> title?.let {
                context.stringResource(MR.strings.vault_import_phase_publishing, it)
            }
            VaultImportProgressPhase.REFRESHING -> title?.let {
                context.stringResource(MR.strings.vault_import_phase_refreshing, it)
            }
            null -> null
        } ?: title
            ?.let { context.stringResource(MR.strings.vault_import_progress_detail, it) }
            ?: context.stringResource(MR.strings.vault_import_phase_text)
    }

    fun showComplete(importedChapterCount: Int) {
        context.cancelNotification(Notifications.ID_VAULT_IMPORT_PROGRESS)
        context.notify(
            Notifications.ID_VAULT_IMPORT_COMPLETE,
            completeNotificationBuilder
                .asDismissibleResult()
                .setSmallIcon(R.drawable.ic_vault_progress_100_24dp)
                .setContentTitle(context.stringResource(MR.strings.vault_import_complete))
                .setContentText(
                    context.stringResource(
                        MR.strings.vault_import_success,
                        importedChapterCount,
                    ),
                )
                .setProgress(0, 0, false)
                .build(),
        )
    }

    fun showCaptureComplete(
        addedChapterCount: Int,
        replacedChapterCount: Int,
        failedChapterCount: Int,
    ) {
        context.cancelNotification(Notifications.ID_VAULT_IMPORT_PROGRESS)
        context.notify(
            Notifications.ID_VAULT_IMPORT_COMPLETE,
            completeNotificationBuilder
                .asDismissibleResult()
                .setSmallIcon(R.drawable.ic_vault_progress_100_24dp)
                .setContentTitle(context.stringResource(MR.strings.vault_import_complete))
                .setContentText(
                    context.stringResource(
                        MR.strings.vault_capture_success,
                        addedChapterCount,
                        replacedChapterCount,
                        failedChapterCount,
                    ),
                )
                .setProgress(0, 0, false)
                .build(),
        )
    }

    fun showError() {
        context.cancelNotification(Notifications.ID_VAULT_IMPORT_PROGRESS)
        context.notify(
            Notifications.ID_VAULT_IMPORT_COMPLETE,
            completeNotificationBuilder
                .asDismissibleResult()
                .setSmallIcon(R.drawable.ic_warning_white_24dp)
                .setContentTitle(context.stringResource(MR.strings.vault_import_error_upload_failed))
                .setContentText(context.stringResource(MR.strings.vault_import_error_background_failed))
                .setProgress(0, 0, false)
                .build(),
        )
    }

    fun showCancelled() {
        context.cancelNotification(Notifications.ID_VAULT_IMPORT_PROGRESS)
        context.notify(
            Notifications.ID_VAULT_IMPORT_COMPLETE,
            completeNotificationBuilder
                .asDismissibleResult()
                .setSmallIcon(R.drawable.ic_close_24dp)
                .setContentTitle(context.stringResource(MR.strings.vault_import_cancelled))
                .setContentText(context.stringResource(MR.strings.vault_import_cancelled_detail))
                .setProgress(0, 0, false)
                .build(),
        )
    }

    fun cancel() {
        context.cancelNotification(Notifications.ID_VAULT_IMPORT_PROGRESS)
        context.cancelNotification(Notifications.ID_VAULT_IMPORT_COMPLETE)
    }

    private fun NotificationCompat.Builder.asLockedProgress(): NotificationCompat.Builder {
        return setOngoing(true)
            .setAutoCancel(false)
    }

    private fun NotificationCompat.Builder.asDismissibleResult(): NotificationCompat.Builder {
        return setOngoing(false)
            .setAutoCancel(true)
    }

    private fun NotificationCompat.Builder.withCancelAction(): NotificationCompat.Builder {
        return clearActions()
            .addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel),
                NotificationReceiver.cancelVaultImportPendingBroadcast(context),
            )
    }
}

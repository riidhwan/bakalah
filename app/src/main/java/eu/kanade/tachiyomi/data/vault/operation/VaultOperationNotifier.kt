package eu.kanade.tachiyomi.data.vault.operation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.vault.model.VaultTransferType
import tachiyomi.i18n.MR

class VaultOperationNotifier(
    private val context: Context,
) {

    private val progressNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_VAULT_PROGRESS,
    ) {
        setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        setSmallIcon(R.drawable.ic_vault_progress_0_24dp)
        setContentTitle(context.stringResource(MR.strings.vault_operation_publishing_changes))
        setContentText(context.stringResource(MR.strings.vault_operation_publishing_changes))
        setOnlyAlertOnce(true)
        setOngoing(true)
        setAutoCancel(false)
        setProgress(0, 0, true)
        setCategory(NotificationCompat.CATEGORY_PROGRESS)
        setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    private val failedNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_VAULT_COMPLETE,
    ) {
        setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        setSmallIcon(R.drawable.ic_warning_white_24dp)
        setContentTitle(context.stringResource(MR.strings.vault_operation_failed_title))
        setOnlyAlertOnce(true)
        setOngoing(false)
        setAutoCancel(true)
        setContentIntent(openAppPendingIntent())
    }

    fun foregroundNotification(): NotificationCompat.Builder {
        return progressNotificationBuilder
    }

    fun showQueueRunning() {
        context.cancelNotification(Notifications.ID_VAULT_OPERATION_COMPLETE)
        showProgress(context.stringResource(MR.strings.vault_operation_publishing_changes))
    }

    fun showOperationRunning(type: VaultTransferType) {
        showProgress(type.progressText())
    }

    fun showRefreshing() {
        showProgress(context.stringResource(MR.strings.vault_operation_refreshing))
    }

    fun showFailures(failureCount: Int) {
        context.cancelNotification(Notifications.ID_VAULT_OPERATION_PROGRESS)
        context.notify(
            Notifications.ID_VAULT_OPERATION_COMPLETE,
            failedNotificationBuilder
                .setContentText(context.stringResource(MR.strings.vault_operation_failed_text, failureCount))
                .setProgress(0, 0, false)
                .build(),
        )
    }

    fun cancel() {
        context.cancelNotification(Notifications.ID_VAULT_OPERATION_PROGRESS)
    }

    private fun showProgress(text: String) {
        context.notify(
            Notifications.ID_VAULT_OPERATION_PROGRESS,
            progressNotificationBuilder
                .setContentText(text)
                .setProgress(0, 0, true)
                .build(),
        )
    }

    private fun VaultTransferType.progressText(): String {
        return when (this) {
            VaultTransferType.METADATA_PUBLISH -> context.stringResource(MR.strings.vault_operation_saving_metadata)
            VaultTransferType.CHAPTER_DELETE -> context.stringResource(MR.strings.vault_operation_deleting_chapter)
            VaultTransferType.CHAPTER_RENAME -> context.stringResource(MR.strings.vault_operation_renaming_chapter)
            else -> context.stringResource(MR.strings.vault_operation_publishing_changes)
        }
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

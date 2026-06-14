package eu.kanade.tachiyomi.data.vault.export

import android.content.Context
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

class VaultChapterExportNotifier(
    private val context: Context,
) {

    fun showChapterComplete(filename: String) {
        val notification = context.notificationBuilder(Notifications.CHANNEL_VAULT_COMPLETE) {
            setContentTitle(context.stringResource(MR.strings.vault_chapter_download_notification_title))
            setContentText(context.stringResource(MR.strings.vault_chapter_download_notification_text, filename))
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setAutoCancel(true)
        }.build()

        context.notify(Notifications.ID_VAULT_CHAPTER_EXPORT_COMPLETE, notification)
    }

    fun showThumbnailComplete(filename: String) {
        val notification = context.notificationBuilder(Notifications.CHANNEL_VAULT_COMPLETE) {
            setContentTitle(context.stringResource(MR.strings.vault_thumbnail_download_notification_title))
            setContentText(context.stringResource(MR.strings.vault_chapter_download_notification_text, filename))
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setAutoCancel(true)
        }.build()

        context.notify(Notifications.ID_VAULT_CHAPTER_EXPORT_COMPLETE, notification)
    }
}

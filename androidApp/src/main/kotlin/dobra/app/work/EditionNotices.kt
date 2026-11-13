package dobra.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import dobra.app.R
import dobra.app.shell.MainActivity
import dobra.make.MakeProgress
import dobra.make.MakeStage
import dobra.model.Edition
import dobra.model.Magazine

/** The two notifications of a background edition: the progress while it is made, and "Edição pronta" opening it. */
class EditionNotices(private val context: Context) {

    /** The notification the job's foreground service shows while [magazine]'s edition is made. */
    fun progress(magazine: Magazine, progress: MakeProgress?): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.work_progress_title, magazine.name))
            .setContentText(progressText(progress))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(progress?.total ?: 0, progress?.done ?: 0, progress == null || progress.total == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, notification)
        }
    }

    fun progressText(progress: MakeProgress?): String = when (progress?.stage) {
        null -> context.getString(R.string.work_progress_start)
        MakeStage.COLLECTING -> context.getString(R.string.work_progress_collecting, progress.done, progress.total)
        MakeStage.CHOOSING -> context.getString(R.string.work_progress_choosing)
        MakeStage.WRITING -> context.getString(R.string.work_progress_writing, progress.done, progress.total)
        MakeStage.SAVING -> context.getString(R.string.work_progress_saving)
    }

    /** Posts "Edição pronta"; touching it opens [edition] in the reader. Silent when notifying is not allowed. */
    fun ready(magazine: Magazine, edition: Edition) {
        if (!allowed(context)) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.work_ready_title))
            .setContentText(context.getString(R.string.work_ready_text, magazine.name, edition.number))
            .setContentIntent(openIntent(edition.key))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(readyId(edition.key), notification)
        } catch (_: SecurityException) {
            // The reader withdrew the permission between the check and the call: nothing to show.
        }
    }

    private fun openIntent(editionKey: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_EDITION, editionKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(
            context, readyId(editionKey), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.work_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.work_channel_about)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL = "editions"
        const val PROGRESS_ID = 4100
        const val EXTRA_OPEN_EDITION = "dobra.app.OPEN_EDITION"

        fun readyId(editionKey: String) = 5000 + (editionKey.hashCode() and 0xFFFF)

        /** Whether the app may post notifications now (runtime permission from Android 13 on). */
        fun allowed(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        /** True only the first time notifying is needed and not yet allowed: the moment to ask the reader once. */
        fun shouldAsk(context: Context, generation: Generation): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !generation.notifyAsked && !allowed(context)
    }
}

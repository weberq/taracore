package dev.taracore.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.taracore.api.ServiceStatus

/**
 * The service's notifications.
 *
 * Android does not permit a foreground service without a visible notification, so
 * the question is never "can we hide it" but "when are we foreground at all". The
 * service answers that in `foregroundIsJustified()`; this class only decides how the
 * notification looks once one is required.
 *
 * Two channels, because a channel's importance is fixed once created and the two
 * cases genuinely differ:
 *
 * - [CHANNEL_ACTIVITY] at `IMPORTANCE_MIN` for the transient case. MIN keeps it out
 *   of the status bar entirely: no icon, just a quiet line in the shade for as long
 *   as the work lasts. This is what most users will ever see, and only while the
 *   engine is actually doing something.
 * - [CHANNEL_STATUS] at `IMPORTANCE_LOW` for the live status the user opted into.
 *   Still silent, but it earns a status-bar icon because the user asked to be able
 *   to see it at a glance.
 */
class ServiceNotifications(private val context: Context) {

    companion object {
        /** Transient: shown only while the engine is working. */
        const val CHANNEL_ACTIVITY = "tara_core_activity"

        /** Opt-in: shown continuously when the user turns live status on. */
        const val CHANNEL_STATUS = "tara_core_status"

        const val NOTIFICATION_ID = 0x7A4A  // arbitrary, stable

        const val ACTION_STOP = "dev.taracore.action.STOP_SERVICE"
        const val ACTION_UNLOAD = "dev.taracore.action.UNLOAD_MODEL"
    }

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ACTIVITY,
                context.getString(R.string.channel_activity_name),
                // MIN: no status-bar icon and no sound. The service is foreground
                // only while it is genuinely working, and a user who never enabled
                // live status should barely notice it.
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.channel_activity_description)
                setShowBadge(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_status_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_status_description)
                setShowBadge(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )

        // The single channel shipped before this split is gone; leaving it behind
        // would show the user a dead toggle in system settings.
        runCatching { manager.deleteNotificationChannel("tara_core_service") }
    }

    /**
     * @param live true when the user asked for a permanent status notification, which
     *   changes both the channel and how much detail is worth showing.
     */
    fun build(
        status: ServiceStatus,
        lastTokensPerSecond: Double,
        live: Boolean,
    ): Notification {
        val channel = if (live) CHANNEL_STATUS else CHANNEL_ACTIVITY

        val title = when (status.state) {
            ServiceStatus.State.LOADING ->
                context.getString(R.string.notification_title_loading, status.loadedModelId
                    ?: status.activeModelId ?: "model")
            ServiceStatus.State.GENERATING ->
                context.getString(R.string.notification_title_generating,
                    status.loadedModelId ?: "model")
            ServiceStatus.State.READY ->
                context.getString(R.string.notification_title_ready,
                    status.loadedModelId ?: "model")
            else -> context.getString(R.string.notification_title_idle)
        }

        val server = if (status.httpServerRunning) "127.0.0.1:${status.httpPort}" else null
        val text = buildString {
            append(status.backend)
            server?.let { append(" · ").append(it) }
            if (lastTokensPerSecond > 0) {
                append(" · ").append(String.format(java.util.Locale.US, "%.1f tok/s",
                    lastTokensPerSecond))
            }
        }

        val stopIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, TaraCoreService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val contentIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.let {
                PendingIntent.getActivity(
                    context, 1, it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        return NotificationCompat.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Nothing here is private: a model name on the user's own lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(
                if (live) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN
            )
            .apply {
                contentIntent?.let(::setContentIntent)
                // A Stop action only makes sense when the thing is going to stay put.
                // On the transient notification it would usually vanish before it
                // could be tapped.
                if (live) {
                    addAction(0, context.getString(R.string.notification_action_stop), stopIntent)
                }
            }
            .build()
    }

    fun update(status: ServiceStatus, lastTokensPerSecond: Double, live: Boolean) {
        manager.notify(NOTIFICATION_ID, build(status, lastTokensPerSecond, live))
    }
}

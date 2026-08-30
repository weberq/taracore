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
 * The persistent notification. It is not decoration: a `specialUse` foreground
 * service is required to be visible, and this is the only place a user sees that a
 * model is resident and what it is costing them. So it shows the model, the backend
 * that actually initialised, the last measured speed, and a way to stop.
 */
class ServiceNotifications(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "tara_core_service"
        const val NOTIFICATION_ID = 0x7A4A  // arbitrary, stable

        const val ACTION_STOP = "dev.taracore.action.STOP_SERVICE"
        const val ACTION_UNLOAD = "dev.taracore.action.UNLOAD_MODEL"
    }

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            // LOW: it must be visible, but it is ambient status, not an event. No
            // sound, no vibration, no heads-up.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(status: ServiceStatus, lastTokensPerSecond: Double): Notification {
        val title = when (status.state) {
            ServiceStatus.State.LOADING ->
                context.getString(R.string.notification_title_loading, status.loadedModelId ?: "model")
            ServiceStatus.State.GENERATING ->
                context.getString(R.string.notification_title_generating, status.loadedModelId ?: "model")
            ServiceStatus.State.READY ->
                context.getString(R.string.notification_title_ready, status.loadedModelId ?: "model")
            else -> context.getString(R.string.notification_title_idle)
        }

        val server = if (status.httpServerRunning) "127.0.0.1:${status.httpPort}" else "AIDL only"
        val text = if (lastTokensPerSecond > 0) {
            context.getString(R.string.notification_text_speed, status.backend, server, lastTokensPerSecond)
        } else {
            context.getString(R.string.notification_text_backend, status.backend, server)
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

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Everything shown here is already visible on the lock screen's owner
            // device only; there is nothing private in a model name.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { contentIntent?.let(::setContentIntent) }
            .addAction(0, context.getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    fun update(status: ServiceStatus, lastTokensPerSecond: Double) {
        manager.notify(NOTIFICATION_ID, build(status, lastTokensPerSecond))
    }
}

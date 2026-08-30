package dev.taracore.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Starts the service at boot, when the user has asked for it.
 *
 * Declared `android:enabled="false"` in the manifest and enabled at runtime by
 * [setEnabled], so a user who never turns the setting on never has a receiver woken
 * at boot. The setting is checked again here anyway: the component-enabled state and
 * the preference can drift after a restore from backup.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TaraCore/Boot"

        /** Enable or disable the receiver component to match the user's setting. */
        fun setEnabled(context: Context, enabled: Boolean) {
            val component = android.content.ComponentName(context, BootReceiver::class.java)
            context.packageManager.setComponentEnabledSetting(
                component,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            Log.i(TAG, "boot receiver ${if (enabled) "enabled" else "disabled"}")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        // A BroadcastReceiver gets ~10 seconds; reading one preference is well inside
        // that, and getting it wrong would start a foreground service unasked.
        val snapshot = runCatching {
            runBlocking { TaraSettings(context).flow.first() }
        }.getOrNull()

        if (snapshot?.startOnBoot != true) {
            Log.i(TAG, "start-on-boot is off; not starting")
            return
        }

        Log.i(TAG, "starting the service after boot")
        runCatching {
            ContextCompat.startForegroundService(
                context,
                TaraCoreService.startIntent(context, snapshot.activeModelId),
            )
        }.onFailure { Log.e(TAG, "could not start the service after boot", it) }
    }
}

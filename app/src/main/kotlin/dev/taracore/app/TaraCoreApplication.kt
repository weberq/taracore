package dev.taracore.app

import android.app.Application
import androidx.work.Configuration

/**
 * NOTE: named `TaraCoreApplication`, not `TaraCoreApp`, and the root composable is
 * `TaraCoreRoot`. A class and a function may share a name in Kotlin, and when they do,
 * `TaraCoreApp()` in a composable body resolves to the *constructor* -- silently
 * building and discarding an Application object while emitting no UI, with no error
 * anywhere. The app launched to a blank screen until these were renamed apart.
 *
 * WorkManager is initialised here rather than by its default provider so downloads
 * log at a level we can actually read in a bug report.
 */
class TaraCoreApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}

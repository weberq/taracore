package dev.taracore.app

import android.app.Application
import androidx.work.Configuration

/**
 * WorkManager is initialised here rather than by its default provider so downloads
 * log at a level we can actually read in a bug report.
 */
class TaraCoreApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}

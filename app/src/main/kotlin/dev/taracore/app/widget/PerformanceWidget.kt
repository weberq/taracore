package dev.taracore.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import dev.taracore.api.ServiceStatus
import dev.taracore.app.MainActivity
import dev.taracore.app.R
import dev.taracore.app.ui.formatBytes
import dev.taracore.app.ui.formatTokensPerSecond

/**
 * Home screen widget showing what the engine is doing.
 *
 * Deliberately push-driven rather than polling. `updatePeriodMillis` is capped at 30
 * minutes by the system, which is useless for tokens-per-second, and a widget that
 * woke the engine process to ask would cost more battery than it could ever justify.
 * Instead the service pushes a snapshot through [update] whenever its state changes,
 * and the widget is otherwise inert -- it holds no binding and starts nothing.
 *
 * The consequence, which is the right trade: when nothing is running, the widget
 * shows the last state it was told about rather than live data. That is what a
 * glanceable widget should do anyway.
 */
class PerformanceWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == dev.taracore.service.TaraCoreService.ACTION_STATUS_CHANGED) {
            val snapshot = WidgetState(
                state = intent.getIntExtra(
                    dev.taracore.service.TaraCoreService.EXTRA_STATE,
                    ServiceStatus.State.IDLE,
                ),
                modelId = intent.getStringExtra(
                    dev.taracore.service.TaraCoreService.EXTRA_MODEL_NAME
                ) ?: intent.getStringExtra(
                    dev.taracore.service.TaraCoreService.EXTRA_MODEL
                ),
                backend = intent.getStringExtra(
                    dev.taracore.service.TaraCoreService.EXTRA_BACKEND
                ) ?: "—",
                tokensPerSecond = intent.getDoubleExtra(
                    dev.taracore.service.TaraCoreService.EXTRA_TOKENS_PER_SECOND, 0.0
                ),
                modelRamBytes = intent.getLongExtra(
                    dev.taracore.service.TaraCoreService.EXTRA_MODEL_RAM, 0L
                ),
                connected = true,
            )
            WidgetState.write(context, snapshot)
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, PerformanceWidget::class.java)
                )
                val views = render(context, snapshot)
                ids.forEach { manager.updateAppWidget(it, views) }
            }.onFailure { Log.w(TAG, "widget render failed", it) }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Fired on add and every updatePeriodMillis. We have no live status here --
        // asking for one would mean binding the engine from a broadcast receiver --
        // so render whatever the service last cached.
        val cached = WidgetState.read(context)
        appWidgetIds.forEach { id -> manager.updateAppWidget(id, render(context, cached)) }
    }

    companion object {
        private const val TAG = "TaraCore/Widget"

        /** Push a fresh snapshot to every placed widget. Safe to call often. */
        fun update(context: Context, status: ServiceStatus, tokensPerSecond: Double) {
            val snapshot = WidgetState(
                state = status.state,
                modelId = status.loadedModelId ?: status.activeModelId,
                backend = status.backend,
                tokensPerSecond = tokensPerSecond,
                modelRamBytes = status.modelRamBytes,
                connected = true,
            )
            WidgetState.write(context, snapshot)

            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, PerformanceWidget::class.java)
                )
                if (ids.isEmpty()) return  // nobody placed one; do no work
                val views = render(context, snapshot)
                ids.forEach { manager.updateAppWidget(it, views) }
            }.onFailure { Log.w(TAG, "widget update failed", it) }
        }

        private fun render(context: Context, s: WidgetState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_performance)

            val stateLabel = when {
                !s.connected -> context.getString(R.string.widget_state_disconnected)
                s.state == ServiceStatus.State.LOADING ->
                    context.getString(R.string.widget_state_loading)
                s.state == ServiceStatus.State.GENERATING ->
                    context.getString(R.string.widget_state_generating)
                s.state == ServiceStatus.State.READY ->
                    context.getString(R.string.widget_state_ready)
                else -> context.getString(R.string.widget_state_idle)
            }

            views.setTextViewText(R.id.widget_state, "$stateLabel · ${s.backend}")
            views.setTextViewText(
                R.id.widget_model,
                s.modelId ?: context.getString(R.string.widget_no_model),
            )
            views.setTextViewText(
                R.id.widget_speed,
                if (s.tokensPerSecond > 0) formatTokensPerSecond(s.tokensPerSecond)
                else context.getString(R.string.widget_dash),
            )
            views.setTextViewText(
                R.id.widget_memory,
                if (s.modelRamBytes > 0) "${formatBytes(s.modelRamBytes)} in memory"
                else context.getString(R.string.widget_dash),
            )

            // Tapping opens the app. Nothing else is interactive: a widget that could
            // start inference would be a way to burn a battery from the home screen.
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            return views
        }
    }
}

/**
 * The last snapshot the service pushed.
 *
 * Plain SharedPreferences rather than the settings DataStore: this is read from a
 * broadcast receiver on the main thread, where a suspending read is not available,
 * and it is disposable cached state rather than user configuration.
 */
data class WidgetState(
    val state: Int = ServiceStatus.State.IDLE,
    val modelId: String? = null,
    val backend: String = "—",
    val tokensPerSecond: Double = 0.0,
    val modelRamBytes: Long = 0,
    val connected: Boolean = false,
) {
    companion object {
        private const val FILE = "taracore_widget"

        fun read(context: Context): WidgetState {
            val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return WidgetState(
                state = p.getInt("state", ServiceStatus.State.IDLE),
                modelId = p.getString("model", null),
                backend = p.getString("backend", "—") ?: "—",
                tokensPerSecond = p.getFloat("tps", 0f).toDouble(),
                modelRamBytes = p.getLong("ram", 0L),
                connected = p.getBoolean("connected", false),
            )
        }

        fun write(context: Context, s: WidgetState) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt("state", s.state)
                .putString("model", s.modelId)
                .putString("backend", s.backend)
                .putFloat("tps", s.tokensPerSecond.toFloat())
                .putLong("ram", s.modelRamBytes)
                .putBoolean("connected", s.connected)
                .apply()
        }
    }
}

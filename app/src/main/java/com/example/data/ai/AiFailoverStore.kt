package com.example.data.ai

import android.content.Context

/** Failover settings (toggle + backup model order). Stores only model ids - no keys, no content. */
object AiFailoverSettings {
    private const val PREFS = "nexa_ai_failover"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ORDER = "backup_order"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getBackupOrder(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, "").orEmpty().split(",").filter { it.isNotBlank() }

    fun setBackupOrder(context: Context, ids: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ORDER, ids.joinToString(",")).apply()
    }

    /** Pure helper (testable): move [id] one step up/down inside [order]. */
    fun move(order: List<String>, id: String, up: Boolean): List<String> {
        val i = order.indexOf(id)
        if (i < 0) return order
        val j = if (up) i - 1 else i + 1
        if (j !in order.indices) return order
        return order.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
    }
}

/** Persists [AiRuntime.usage] counters (numbers only). */
object AiUsageStore {
    private const val PREFS = "nexa_ai_usage"
    private const val KEY = "usage"
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        AiRuntime.usage.restore(AiUsageTracker.parse(raw))
        loaded = true
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, AiRuntime.usage.serialize()).apply()
    }

    fun reset(context: Context) {
        AiRuntime.usage.reset()
        save(context)
    }
}

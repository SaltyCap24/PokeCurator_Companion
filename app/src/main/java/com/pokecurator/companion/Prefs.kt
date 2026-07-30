package com.pokecurator.companion

import android.content.Context
import android.net.Uri

/**
 * Small persisted settings: the trainer's sync URL (which carries the per-user
 * token) and per-session cleanup progress (which species are done).
 */
object Prefs {
    private const val FILE = "pokecurator"
    private const val KEY_SYNC_URL = "sync_url"
    private const val KEY_DONE = "cleanup_done"
    private const val KEY_MODE = "mode"
    private const val KEY_ORDER = "order"

    fun syncUrl(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SYNC_URL, "") ?: ""

    fun setSyncUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_SYNC_URL, url.trim()).apply()
    }

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODE, "auto") ?: "auto"

    fun setMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
    }

    /** Step order: "impact" (most space freed first) or "recent" (newest catches first). */
    fun order(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ORDER, "impact") ?: "impact"

    fun setOrder(ctx: Context, order: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_ORDER, order).apply()
    }

    fun done(ctx: Context): MutableSet<String> =
        HashSet(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_DONE, emptySet()) ?: emptySet())

    fun setDone(ctx: Context, done: Set<String>) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_DONE, done).apply()
    }

    fun clearDone(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY_DONE).apply()
    }

    /**
     * Turn the trainer's ingest/sync URL into the read-only cleanup-plan URL.
     * Accepts either the ".../api/ingest/pgsharp?token=..." sync URL or an
     * already-correct export URL; preserves scheme, host and the token.
     */
    fun planUrl(ctx: Context, includeAll: Boolean = false): String? {
        val raw = syncUrl(ctx)
        if (raw.isBlank()) return null
        val uri = Uri.parse(raw)
        val scheme = uri.scheme ?: return null
        val host = uri.authority ?: return null
        val token = uri.getQueryParameter("token")?.trim().orEmpty()
        if (token.isEmpty()) return null
        val base = "$scheme://$host/api/export/transfer-session?token=$token&mode=${mode(ctx)}&order=${order(ctx)}"
        return if (includeAll) "$base&include_all=1" else base
    }
}

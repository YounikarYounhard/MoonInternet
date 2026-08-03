package cc.moon.internet.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * UI language. Kept in its own SharedPreferences rather than in [AppState], because
 * attachBaseContext runs long before the state file has been read off disk and the very first
 * resource lookup already has to be in the right language.
 *
 * The desktop build does the same thing with its own settings key; the two switchers behave alike.
 */
object Lang {
    private const val PREFS = "moon_lang"
    private const val KEY = "lang"
    private const val KEY_HEADSUP = "heads_up"

    /** "" = follow the system. Otherwise a tag we ship a values-<tag> folder for. */
    fun saved(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun save(ctx: Context, tag: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        // The activity is recreated right after this and re-wraps itself, but the long-lived
        // application context is not — and the VPN notification is built from that one.
        if (tag.isNotEmpty()) applyTo(ctx.applicationContext, Locale.forLanguageTag(tag))
    }

    @Suppress("DEPRECATION")
    private fun applyTo(app: Context, locale: Locale) {
        Locale.setDefault(locale)
        val res = app.resources
        val cfg = Configuration(res.configuration).apply { setLocale(locale) }
        res.updateConfiguration(cfg, res.displayMetrics)
    }

    /** What the switcher should show as active: the saved tag, or what the system resolves to. */
    fun effective(ctx: Context): String =
        saved(ctx).ifEmpty { if (Locale.getDefault().language == "ru") "ru" else "en" }

    /**
     * "Pop up over the screen", read straight from prefs.
     *
     * The service builds its notification long before the state file is loaded, and mirroring one
     * boolean here is cheaper than teaching it to wait for the Store.
     */
    fun headsUp(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HEADSUP, false)

    fun setHeadsUp(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HEADSUP, on).apply()
    }

    /** Wraps a context so every resource lookup through it uses the chosen language. */
    fun wrap(ctx: Context): Context {
        val tag = saved(ctx)
        if (tag.isEmpty()) return ctx
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(ctx.resources.configuration).apply { setLocale(locale) }
        return ctx.createConfigurationContext(cfg)
    }
}

package app.booxultimatum.kit.core

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.XmlRes
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * The languages the suite's words are in.
 *
 * - [folder] is the suffix of the resource folder that holds the translation, `values-zh-rCN`. English is
 *   the default folder, `values`, and carries no suffix of its own.
 * - [locale] is the BCP 47 name Android uses for the same language, `zh-CN`. It goes in the app's
 *   `locales_config.xml`, which is what the system's own App languages screen reads, and in
 *   `Locale.forLanguageTag`.
 * - [endonym] is the language in its own words, so a reader finds it whichever language the app is in.
 *
 * `tools/dev/check_translations.py` keeps this list, the two `locales_config.xml` files and the resource
 * folders in step: a language is listed here once the suite has it somewhere, and an app offers only the
 * languages it has words for itself.
 */
enum class SuiteLanguage(val folder: String, val locale: String, val endonym: String) {
    English("en", "en", "English"),
    ChineseSimplified("zh-rCN", "zh-CN", "简体中文"),
    ;

    companion object {
        /** The language of a resource folder's suffix, `zh-rCN`, or null when it is not one of ours. */
        fun ofFolder(folder: String): SuiteLanguage? = entries.firstOrNull { it.folder.equals(folder, true) }

        /** The language of a BCP 47 name, `zh-CN`, or null when it is not one of ours. */
        fun ofLocale(locale: String): SuiteLanguage? = entries.firstOrNull { it.locale.equals(locale, true) }
    }
}

/**
 * The language the app is in. The app follows the tablet's language until the reader chooses another
 * one, and the choice is kept for this app alone.
 *
 * On Android 13 and later the platform keeps the choice, in `LocaleManager`, so the system's own App
 * languages screen and the app's own picker are one setting and cannot disagree. On Android 11 and 12
 * the app keeps it in its own preferences and applies it to the activity's base context.
 */
object AppLanguage {
    private const val PREFS = "language"
    private const val KEY = "tag"

    /** Android's own namespace, which the name in `locales_config.xml` is in. */
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** The language the app is in now, or null when it follows the tablet. */
    fun current(context: Context): SuiteLanguage? {
        val tag = storedTag(context) ?: return null
        return SuiteLanguage.ofLocale(tag) ?: SuiteLanguage.ofFolder(tag)
    }

    /**
     * The languages this app has words for, in the order they are offered. They are the ones the app
     * declares in its own `locales_config.xml`, which is also what the system's App languages screen
     * shows, so the two can never offer different things.
     */
    fun supported(context: Context, @XmlRes config: Int): List<SuiteLanguage> =
        declared(context, config).mapNotNull { SuiteLanguage.ofLocale(it) }

    /**
     * Sets the app's language, or null to follow the tablet again, and shows the app in it. On Android 13
     * and later the platform restarts the app's screens by itself; on Android 11 and 12 the screen is
     * started again here.
     */
    fun change(context: Context, language: SuiteLanguage?) {
        val tag = language?.locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            runCatching {
                manager.setApplicationLocales(if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag))
            }
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .apply { if (tag == null) remove(KEY) else putString(KEY, tag) }.apply()
            activityOf(context)?.recreate()
        }
    }

    /** The activity a context belongs to, if there is one. */
    private fun activityOf(context: Context): Activity? {
        var current = context
        var depth = 0
        while (current is ContextWrapper && depth++ < 8) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /**
     * The context an activity should use, with the chosen language applied. On Android 13 and later the
     * platform has already done it; on Android 11 and 12 this is where the language is applied.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = storedTag(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** The BCP 47 names the app declares in its `locales_config.xml`. */
    fun declared(context: Context, @XmlRes config: Int): List<String> {
        val names = mutableListOf<String>()
        val parser = context.resources.getXml(config)
        try {
            var event = parser.next()
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "locale") {
                    // The name is an android:name, so it is in Android's namespace.
                    val name = parser.getAttributeValue(ANDROID_NS, "name") ?: parser.getAttributeValue(null, "name")
                    if (name != null) names.add(name)
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            return names
        } finally {
            runCatching { parser.close() }
        }
        return names
    }

    /** The tag of the language the app is in, read from the platform on Android 13 and later. */
    private fun storedTag(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return null
            val locales = manager.applicationLocales
            return if (locales.isEmpty) null else locales[0].toLanguageTag()
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    }
}

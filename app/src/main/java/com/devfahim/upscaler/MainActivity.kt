package com.devfahim.upscaler

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.devfahim.upscaler.ui.UpscalerRoot
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

/**
 * Single-activity host. In-app language switching:
 *  - Android 13+: LocaleManager (persists system-side, honors localeConfig).
 *  - Android 8-12: classic attachBaseContext wrap reading SharedPreferences.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.wrapWithAppLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UpscalerRoot()
        }
    }
}

/**
 * Applies the stored in-app locale ("en" | "bn" | "system") to a base
 * context on Android 8-12. On Android 13+ the system LocaleManager takes
 * care of per-app locales, so the context passes through untouched.
 */
fun Context.wrapWithAppLocale(): Context {
    if (Build.VERSION.SDK_INT >= 33) return this
    val tag = getSharedPreferences(UpscalerApp.LOCALE_PREFS, Context.MODE_PRIVATE)
        .getString(UpscalerApp.LOCALE_KEY, "system") ?: "system"
    if (tag == "system") return this
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    val config = resources.configuration
    config.setLocale(locale)
    return createConfigurationContext(config)
}

/** Applies a language choice across API levels and recreates the activity. */
fun applyAppLanguage(activity: MainActivity, tag: String) {
    if (Build.VERSION.SDK_INT >= 33) {
        val lm = activity.getSystemService(LocaleManager::class.java)
        if (tag == "system") {
            lm.applicationLocales = LocaleList.getEmptyLocaleList()
        } else {
            lm.applicationLocales = LocaleList.forLanguageTags(tag)
        }
    } else {
        activity.getSharedPreferences(UpscalerApp.LOCALE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(UpscalerApp.LOCALE_KEY, tag).apply()
        activity.recreate()
    }
}

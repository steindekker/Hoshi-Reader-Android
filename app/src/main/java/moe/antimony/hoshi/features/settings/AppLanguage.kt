package moe.antimony.hoshi.features.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.ChecksSdkIntAtLeast

enum class AppLanguageMode(val languageTags: String) {
    System(languageTags = ""),
    English(languageTags = "en-US"),
    SimplifiedChinese(languageTags = "zh-CN"),
    ;

    companion object {
        fun fromLanguageTags(languageTags: String): AppLanguageMode {
            val firstTag = languageTags.split(',').firstOrNull()?.trim().orEmpty()
            return entries.firstOrNull { it.languageTags == firstTag } ?: System
        }
    }
}

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
fun isAppLanguagePickerSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

fun Context.currentAppLanguageMode(): AppLanguageMode {
    if (!isAppLanguagePickerSupported()) return AppLanguageMode.System
    val localeManager = getSystemService(LocaleManager::class.java)
    return AppLanguageMode.fromLanguageTags(localeManager.getApplicationLocales().toLanguageTags())
}

fun Context.setAppLanguageMode(mode: AppLanguageMode) {
    if (!isAppLanguagePickerSupported()) return
    val localeManager = getSystemService(LocaleManager::class.java)
    localeManager.setApplicationLocales(mode.toLocaleList())
}

private fun AppLanguageMode.toLocaleList(): LocaleList =
    if (languageTags.isBlank()) {
        LocaleList.getEmptyLocaleList()
    } else {
        LocaleList.forLanguageTags(languageTags)
    }

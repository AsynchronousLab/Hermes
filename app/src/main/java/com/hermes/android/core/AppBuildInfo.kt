package com.hermes.android.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Which build is actually installed.
 *
 * Reports kept arriving as "the new APK still crashes" while the phone was
 * running a build from before the fix — the version name is the only field that
 * settles that question without a USB cable, so it is printed on the settings
 * screen and on the crash report.
 */
data class AppBuildInfo(
    val versionName: String,
    val versionCode: Long,
    /** Install time in epoch millis; 0 when the platform will not say. */
    val installedAt: Long,
)

fun buildInfo(context: Context): AppBuildInfo = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    AppBuildInfo(
        versionName = info.versionName ?: "?",
        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        },
        installedAt = info.lastUpdateTime,
    )
}.getOrElse { AppBuildInfo("?", 0, 0) }

/** One line for the settings footer and the crash header. */
fun AppBuildInfo.summary(): String = buildString {
    append("版本 ").append(versionName).append(" (").append(versionCode).append(")")
    if (installedAt > 0) {
        append(" · 安装于 ")
        append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(installedAt)))
    }
}

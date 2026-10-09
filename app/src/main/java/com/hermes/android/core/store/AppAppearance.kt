package com.hermes.android.core.store

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

data class AppAppearance(val theme: ThemeMode = ThemeMode.SYSTEM, val fontScale: Float = 1f)

internal fun validFontScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.85f, 1.3f) else 1f

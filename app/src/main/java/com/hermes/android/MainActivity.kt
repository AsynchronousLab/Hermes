package com.hermes.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.hermes.android.core.store.AppAppearance
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import com.hermes.android.ui.HermesAppRoot
import com.hermes.android.ui.theme.HermesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings = (application as HermesApp).settings
            val appearance by settings.appearance.collectAsState(AppAppearance())
            val scope = rememberCoroutineScope()
            val dark = appearance.theme.isDark(isSystemInDarkTheme())
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * appearance.fontScale)) {
                HermesTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) {
                        HermesAppRoot(
                            appearance = appearance,
                            darkTheme = dark,
                            onTheme = { scope.launch { settings.setTheme(it) } },
                            onFontScale = { scope.launch { settings.setFontScale(it) } },
                        )
                    }
                }
            }
        }
    }
}

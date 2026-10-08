package com.hermes.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the `<queries>` package-visibility filter.
 *
 * The action must be the real intent string `android.speech.action.RECOGNIZE_SPEECH`.
 * Writing the *constant name* (`android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH`)
 * compiles fine and looks right, but the filter then matches nothing, so on
 * devices with package-visibility restrictions the app reports "no speech
 * recognizer installed" even though one is present.
 */
class ManifestQueriesTest {
    @Test fun speechQueryUsesTheRealActionNotTheConstantName() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.speech.action.RECOGNIZE_SPEECH"))
        assertFalse(manifest.contains("RecognizerIntent.ACTION_RECOGNIZE_SPEECH"))
    }
}

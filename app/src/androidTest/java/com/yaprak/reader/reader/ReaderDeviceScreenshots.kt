package com.yaprak.reader.reader

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue

internal fun captureReaderScreenshot(compose: ComposeTestRule, name: String) {
    require(name.matches(Regex("[a-z0-9-]+")))
    compose.waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.waitForIdle(250, 3_000)
    val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "yaprak-foundation-0.186")
        .apply { mkdirs() }
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        File(folder, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally { bitmap.recycle() }
}

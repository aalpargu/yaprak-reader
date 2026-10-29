package com.yaprak.reader.feature

import android.app.Application
import android.content.ClipboardManager
import com.yaprak.reader.feature.reader.copyPlainSelectedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SelectedTextClipboardTest {
    @Test fun `saved note writes exact Unicode multiline text and label to real clipboard service`() {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = "İlk satır: ışık 🌿\n  İkinci satır\tson"
        copyPlainSelectedText(context, text, "Yaprak notu")
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals(1, clip.itemCount)
        assertEquals(text, clip.getItemAt(0).text.toString())
        assertEquals("Yaprak notu", clip.description.label.toString())
        assertTrue(clip.description.hasMimeType("text/plain"))
    }

    @Test fun `existing preview and passage calls keep the default clipboard label`() {
        val context = RuntimeEnvironment.getApplication()
        copyPlainSelectedText(context, "Pasaj")
        val clip = requireNotNull(context.getSystemService(ClipboardManager::class.java).primaryClip)
        assertEquals("Yaprak seçili metin", clip.description.label.toString())
        assertEquals("Pasaj", clip.getItemAt(0).text.toString())
    }

    @Test fun `saved word pair keeps both languages and its clipboard label`() {
        val context = RuntimeEnvironment.getApplication()
        val text = "leaf 🌿 — yaprak, yeşillik"
        copyPlainSelectedText(context, text, "Yaprak kelime kaydı")
        val clip = requireNotNull(context.getSystemService(ClipboardManager::class.java).primaryClip)
        assertEquals(1, clip.itemCount)
        assertEquals(text, clip.getItemAt(0).text.toString())
        assertEquals("Yaprak kelime kaydı", clip.description.label.toString())
        assertTrue(clip.description.hasMimeType("text/plain"))
    }
}

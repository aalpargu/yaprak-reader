package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.formats.epub.VisibleTextObservation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VisibleTextObservationTest {
    private fun result(path: String = "/publication/one.xhtml", node: String = "1/0", text: String = "Hello") =
        JSONObject.quote(JSONObject().put("href", path).put("nodes", JSONArray().put(JSONObject().put("path", node).put("text", text))).toString())

    @Test fun `only matching resource text becomes versioned hashed evidence`() {
        val span = VisibleTextObservation.decode(result(text = "Türkçe 🌿"), "one.xhtml").single()
        assertEquals(9, span.endOffset)
        assertEquals(1, span.extractionVersion)
        assertEquals(64, span.textSha256.length)
        assertEquals("1/0", span.nodePath)
        assertTrue(VisibleTextObservation.decode(result(), "future.xhtml").isEmpty())
    }

    @Test fun `malformed missing oversized or unanchored observations fail closed`() {
        for (raw in listOf(null, "null", "undefined", "{}", result(node = "../escape"), result(text = "x".repeat(4097)))) {
            assertTrue(VisibleTextObservation.decode(raw, "one.xhtml").isEmpty())
        }
    }
}

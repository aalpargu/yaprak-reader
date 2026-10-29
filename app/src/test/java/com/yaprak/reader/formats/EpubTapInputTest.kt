@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.yaprak.reader.formats

import android.app.Application
import android.graphics.PointF
import com.yaprak.reader.domain.settings.TapZone
import com.yaprak.reader.formats.epub.EpubTapInput
import com.yaprak.reader.reader.ReaderTap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.input.InputListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.lang.reflect.Proxy
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubTapInputTest {
    @Test fun pinnedSdkCompositeStopsBeforeLaterDefaultListenersWhenMappingOwnsTap() {
        val type = Class.forName("org.readium.r2.navigator.input.CompositeInputListener")
        val composite = type.getDeclaredConstructor().newInstance()
        val add = type.getDeclaredMethod("add", InputListener::class.java)
        val input = EpubTapInput { 300 to 600 }
        var defaults = 0
        add.invoke(composite, input)
        add.invoke(composite, object : InputListener {
            override fun onTap(event: TapEvent): Boolean { defaults++; return true }
        })
        val tap = type.getDeclaredMethod("onTap", TapEvent::class.java)
        input.setHandler { true }
        assertEquals(true, tap.invoke(composite, TapEvent(PointF(290f, 50f))))
        assertEquals(0, defaults)
        input.setHandler { false }
        assertEquals(true, tap.invoke(composite, TapEvent(PointF(290f, 50f))))
        assertEquals(1, defaults)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun pinnedSdkBridgeSuppressesInteractiveElementsPreventedEventsAndActiveSelectionBeforeCustomInput() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var view: android.webkit.WebView? = null
        try {
            val type = Class.forName("org.readium.r2.navigator.R2BasicWebView")
            view = type.getDeclaredConstructor(android.content.Context::class.java, android.util.AttributeSet::class.java)
                .newInstance(RuntimeEnvironment.getApplication(), org.robolectric.Robolectric.buildAttributeSet().build()) as android.webkit.WebView
            val listenerType = type.declaredClasses.single { it.simpleName == "Listener" }
            val input = EpubTapInput { 300 to 600 }
            var commands = 0
            input.setHandler { commands++; true }
            val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, args ->
                if (method.name == "onTap") input.onTap(TapEvent(args!![0] as PointF)) else null
            }
            type.getDeclaredMethod("setListener", listenerType).invoke(view, listener)
            val method = type.getDeclaredMethod("onTap", String::class.java)
            val event = org.json.JSONObject().put("x", 290).put("y", 50).put("targetElement", "p")
                .put("defaultPrevented", false)
            assertEquals(true, method.invoke(view, event.toString())); assertEquals(1, commands)
            event.put("defaultPrevented", true)
            assertEquals(false, method.invoke(view, event.toString())); assertEquals(1, commands)
            event.put("defaultPrevented", false).put("interactiveElement", "<a href='chapter.xhtml'>link</a>")
            assertEquals(false, method.invoke(view, event.toString())); assertEquals(1, commands)
            event.remove("interactiveElement")
            type.getDeclaredMethod("onSelectionStart").invoke(view)
            assertEquals(false, method.invoke(view, event.toString())); assertEquals(1, commands)
            type.getDeclaredMethod("onSelectionEnd").invoke(view)
            assertEquals(true, method.invoke(view, event.toString())); assertEquals(2, commands)
        } finally { view?.destroy(); Dispatchers.resetMain() }
    }
    @Test fun disabledFallsThroughAndEnabledConsumesIncludingNoOpAtBookEdge() {
        val input = EpubTapInput { 300 to 600 }
        val event = TapEvent(PointF(250f, 50f))
        assertFalse(input.onTap(event))
        var seen: ReaderTap? = null
        input.setHandler { seen = it; true }
        assertTrue(input.onTap(event)); assertEquals(TapZone.RIGHT, seen?.zone)
        input.setHandler { false }; assertFalse(input.onTap(event))
        input.setHandler(null); assertFalse(input.onTap(event))
    }
    @Test fun currentViewportAndStrictGeometryKeepPhysicalThirdsAfterRotation() {
        var width = 300; var height = 600
        val input = EpubTapInput { width to height }
        val zones = mutableListOf<TapZone?>()
        input.setHandler { zones += it.zone; true }
        for (x in listOf(0f, 99f, 100f, 199f, 200f, 299f))
            assertTrue(input.onTap(TapEvent(PointF(x, 100f))))
        assertEquals(listOf(TapZone.LEFT, TapZone.LEFT, TapZone.MIDDLE, TapZone.MIDDLE, TapZone.RIGHT, TapZone.RIGHT), zones)
        width = 600; height = 300
        assertTrue(input.onTap(TapEvent(PointF(200f, 100f)))); assertEquals(TapZone.MIDDLE, zones.last())
        for (point in listOf(PointF(-1f, 100f), PointF(600f, 100f), PointF(100f, 300f),
            PointF(Float.NaN, 100f), PointF(100f, Float.POSITIVE_INFINITY))) {
            input.onTap(TapEvent(point)); assertNull(zones.last())
        }
        width = 0; input.onTap(TapEvent(PointF(0f, 0f))); assertNull(zones.last())
    }
}

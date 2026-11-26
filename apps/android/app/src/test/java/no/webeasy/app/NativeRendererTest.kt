package no.webeasy.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeRendererTest {
    @Test
    fun keepsNativeHtmlAndUnicodeWithoutLoadingJni() {
        val files = NativeRenderer.files(JSONObject("""{"files":[{"path":"nyheter/🎉.html","html":"<h1>Blåbær 🌍</h1>"}]}"""))
        assertEquals("nyheter/🎉.html", files.getJSONObject(0).getString("path"))
        assertEquals("<h1>Blåbær 🌍</h1>", files.getJSONObject(0).getString("html"))
    }

    @Test
    fun rejectsRendererErrorsEvenWhenFilesArePresent() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            NativeRenderer.files(JSONObject("""{"error":"Unknown template.","files":[]}"""))
        }
        assertEquals("Unknown template.", error.message)
    }

    @Test
    fun rejectsMalformedNativeFiles() {
        for (response in listOf(
            """{}""", """{"files":{}}""", """{"files":[null]}""",
            """{"files":[{"path":"index.html"}]}""",
            """{"files":[{"path":17,"html":"hello"}]}""",
            """{"files":[{"path":"index.html","html":null}]}"""
        )) {
            assertThrows(RuntimeException::class.java) { NativeRenderer.files(JSONObject(response)) }
        }
    }
}

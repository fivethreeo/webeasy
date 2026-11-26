package no.webeasy.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "sw375dp-w375dp-h812dp")
@LooperMode(LooperMode.Mode.PAUSED)
class LinkDetailsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun choosingPageFillsURLAndTextAndSavesNativeResult() {
        var result: JSONObject? = null
        val details = JSONObject().put("pages", JSONArray().put(JSONObject().put("title", "Home").put("href", "/")))
        compose.setContent { MaterialTheme { LinkDetails(details, { href, completion -> completion(href == "/") }, { result = it }) } }
        compose.onNodeWithText("Enter a URL").performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Link URL").assertTextContains("/")
        compose.onNodeWithText("Link text").assertTextContains("Home")
        compose.onNode(hasText("Insert link") and hasClickAction()).performClick()
        compose.runOnIdle {
            assertEquals("/", result?.getString("href"))
            assertEquals("Home", result?.getString("text"))
        }
    }

    @Test
    fun invalidURLKeepsFormOpenAndRemoveBypassesValidation() {
        var result: JSONObject? = null
        var completed = false
        val details = JSONObject().put("editing", true).put("href", "about/").put("text", "Original text")
        compose.setContent { MaterialTheme { LinkDetails(details, { _, completion -> completion(false) }, { completed = true; result = it }) } }
        compose.onNodeWithText("Link URL").performTextReplacement("javascript:alert(1)")
        compose.onNodeWithText("Save link").performClick()
        compose.onNodeWithText("Use a web, email, telephone, or local page URL.").assertExists()
        compose.runOnIdle { assertFalse(completed) }
        compose.onNodeWithText("Remove link").performClick()
        compose.runOnIdle { assertTrue(result?.getBoolean("remove") == true) }
    }

    @Test
    fun cancelReturnsNoChanges() {
        var completed = false
        var result: JSONObject? = JSONObject()
        compose.setContent { MaterialTheme { LinkDetails(JSONObject(), { _, completion -> completion(true) }, { completed = true; result = it }) } }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(completed); assertNull(result) }
    }
}

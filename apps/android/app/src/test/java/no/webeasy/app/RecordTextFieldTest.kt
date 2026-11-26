package no.webeasy.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class RecordTextFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun typingUpdatesTheVisibleTitleAndSlugAndManualOverridesStayVisible() {
        val record = JSONObject().put("id", "new").put("title", "New news").put("slug", "new-news")
        val revision = mutableIntStateOf(0)
        var automatic = true
        compose.setContent {
            MaterialTheme {
                Column {
                    RecordTextField(record, "title", "Title", revision.intValue) {
                        ContentSlugs.update(record, "news", "title", it, automatic)
                        revision.intValue++
                    }
                    RecordTextField(record, "slug", "Slug", revision.intValue) {
                        automatic = false
                        record.put("slug", it)
                        revision.intValue++
                    }
                }
            }
        }
        compose.onNodeWithText("Title").performTextReplacement("Live news title")
        compose.onNodeWithText("Title").assertTextContains("Live news title")
        compose.onNodeWithText("Slug").assertTextContains("live-news-title")
        compose.onNodeWithText("Slug").performTextReplacement("my-custom-url")
        compose.onNodeWithText("Title").performTextReplacement("Changed again")
        compose.onNodeWithText("Title").assertTextContains("Changed again")
        compose.onNodeWithText("Slug").assertTextContains("my-custom-url")
    }
}

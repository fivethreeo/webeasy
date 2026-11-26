package no.webeasy.app

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class TemplateCatalogListTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "sw800dp-w1280dp-h800dp")
    fun tabletListsBothGroupsAndSelectsTheCurrentPartialSource() {
        val catalog = JSONObject("""{
            "templates":[{"id":"page","title":"Simple page","description":"Page layout","source":"{{{content}}}"}],
            "components":[{"id":"site-header","title":"Site header","description":"Reusable header","source":"<header>Edited source</header>"}]
        }""")
        var selected: JSONObject? = null
        compose.setContent { MaterialTheme { TemplateCatalogList(catalog, onSelect = { selected = it }) } }
        compose.onNodeWithText("Page templates").assertExists()
        compose.onNodeWithText("Simple page").assertExists()
        compose.onNodeWithText("Reusable partials").assertExists()
        compose.onNodeWithText("Site header").performClick()
        assertSame(catalog.getJSONArray("components").getJSONObject(0), selected)
    }

    @Test
    @Config(qualifiers = "sw375dp-w375dp-h812dp")
    fun phoneCanReachNewPartialsBeyondTheBundledPageTemplates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = JSONObject(context.assets.open("website/catalog.json").bufferedReader().use { it.readText() })
        var selected: JSONObject? = null
        compose.setContent { MaterialTheme { TemplateCatalogList(catalog, onSelect = { selected = it }) } }
        for (title in listOf("Site header", "Site footer", "Section heading and body")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
            compose.onNodeWithText(title).performClick()
            assertSame(catalog.getJSONArray("components").let { entries ->
                (0 until entries.length()).map { entries.getJSONObject(it) }.first { it.getString("title") == title }
            }, selected)
        }
    }
}

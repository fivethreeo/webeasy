package no.webeasy.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PrimaryNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val destinations = listOf("Pages", "News", "Settings", "Changes", "Hosting")

    @Test
    @Config(qualifiers = "sw375dp-w375dp-h812dp")
    fun phoneNavigationStaysAtBottomAndSelectsEachDestination() {
        val selected = mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                Scaffold(bottomBar = { PrimaryNavigation(false, selected.intValue) { selected.intValue = it } }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding))
                }
            }
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = destinations.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.all { it.top > root.height / 2 })
        assertTrue(bounds.maxOf { it.center.y } - bounds.minOf { it.center.y } <= 1f)
        assertTrue(bounds.zipWithNext().all { (left, right) -> left.center.x < right.center.x })
        destinations.forEach { name ->
            compose.onNodeWithText(name).performClick().assertIsSelected()
        }
    }

    @Test
    @Config(qualifiers = "sw800dp-w1280dp-h800dp")
    fun tabletNavigationStaysOnLeftAndSelectsEachDestination() {
        val selected = mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                Row(Modifier.fillMaxSize()) {
                    PrimaryNavigation(true, selected.intValue) { selected.intValue = it }
                    Box(Modifier.weight(1f))
                }
            }
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = destinations.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.all { it.right < root.width / 2 })
        assertTrue(bounds.maxOf { it.center.x } - bounds.minOf { it.center.x } <= 1f)
        assertTrue(bounds.zipWithNext().all { (upper, lower) -> upper.center.y < lower.center.y })
        destinations.forEach { name ->
            compose.onNodeWithText(name).performClick().assertIsSelected()
        }
    }
}

package no.webeasy.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContentSlugsTest {
    private fun record(id: String, slug: String = "custom", date: String = "2026-11-26") =
        JSONObject().put("id", id).put("slug", slug).put("date", date)

    @Test
    fun newPageSlugsFollowMenuTitleLiveButNotPageTitle() {
        val page = record("new")
        for ((title, slug) in listOf("A" to "a", "About us" to "about-us", "Café & culture!" to "cafe-culture")) {
            ContentSlugs.update(page, "page", "menuTitle", title, automatic = true)
            assertEquals(title, page.getString("menuTitle"))
            assertEquals(slug, page.getString("slug"))
        }
        ContentSlugs.update(page, "page", "title", "Different page heading", automatic = true)
        assertEquals("cafe-culture", page.getString("slug"))
        assertEquals("page", ContentSlugs.fromTitle("?!", "page"))
    }

    @Test
    fun newsTitleGeneratesLiveAndOnlySameDateRoutesConflict() {
        val item = record("new")
        val siblings = listOf(record("saved", "hello-world"), record("another-day", "hello-world-2", "2026-11-25"))
        ContentSlugs.update(item, "news", "title", "Hello, world!", automatic = true, siblings = siblings)
        assertEquals("hello-world-2", item.getString("slug"))
        ContentSlugs.update(item, "news", "menuTitle", "Unrelated", automatic = true, siblings = siblings)
        assertEquals("hello-world-2", item.getString("slug"))
    }

    @Test
    fun savedOrManuallyEditedSlugsDoNotFollowTitleChanges() {
        for (kind in listOf("page", "news")) {
            val item = record("item", "my-custom-url")
            ContentSlugs.update(item, kind, if (kind == "page") "menuTitle" else "title", "New title", automatic = false)
            assertEquals("my-custom-url", item.getString("slug"))
        }
    }

    @Test
    fun saveGivesSavedRoutesPriorityAndNumbersOnlyNewConflicts() {
        val first = record("first", "about")
        val second = record("second", "about")
        val saved = record("saved", "about")
        val records = listOf(first, second, saved)
        assertTrue(ContentSlugs.resolve(records, { it == "saved" }, rootPage = true))
        assertEquals("about", saved.getString("slug"))
        assertEquals("about-2", first.getString("slug"))
        assertEquals("about-3", second.getString("slug"))
        assertFalse(ContentSlugs.resolve(records, { it == "saved" }, rootPage = true))
    }

    @Test
    fun reservedNamesApplyOnlyToRootPagesAndEmptyManualSlugsStayInvalid() {
        val page = record("new")
        assertEquals("news-2", ContentSlugs.generated("News", "page", page, emptyList(), rootPage = true))
        assertEquals("news", ContentSlugs.generated("News", "page", page, emptyList(), rootPage = false))
        val reserved = record("reserved", "news")
        val empty = record("empty", "")
        ContentSlugs.resolve(listOf(reserved, empty), { false }, rootPage = true)
        assertEquals("news-2", reserved.getString("slug"))
        assertEquals("", empty.getString("slug"))
    }

    @Test
    fun saveScopesNewsConflictsByDateAndNeverRenamesSavedItems() {
        val saved = record("saved", "update")
        val duplicate = record("new", "update")
        val differentDate = record("other", "update", "2026-11-25")
        ContentSlugs.resolve(listOf(duplicate, differentDate, saved), { it == "saved" }, news = true)
        assertEquals("update", saved.getString("slug"))
        assertEquals("update-2", duplicate.getString("slug"))
        assertEquals("update", differentDate.getString("slug"))
        val savedConflict = record("saved-conflict", "update")
        assertFalse(ContentSlugs.resolve(listOf(saved, savedConflict), { true }, news = true))
        assertEquals("update", savedConflict.getString("slug"))
    }
}

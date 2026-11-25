package no.webeasy.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContentStoreTest {
    private lateinit var context: Context
    private lateinit var store: ContentStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("webeasy.sqlite")
        java.io.File(context.filesDir, "Media").deleteRecursively()
        store = ContentStore(context)
    }

    @After
    fun tearDown() { store.close() }

    @Test
    fun seedsOwnedContentAndReopeningPreservesChangesAndDeletion() {
        val project = store.project()
        val page = project.getJSONArray("pages").getJSONObject(0)
        assertEquals("team", page.getJSONArray("children").getJSONObject(0).getString("slug"))
        assertEquals(1, page.getJSONArray("sections").getJSONObject(0).getJSONArray("asides").length())
        page.put("title", "Locally edited")
        store.save(project)
        store.close()
        store = ContentStore(context)
        assertEquals("Locally edited", store.project().getJSONArray("pages").getJSONObject(0).getString("title"))
        store.save(JSONObject().put("version", 1).put("pages", JSONArray()).put("news", JSONArray()))
        store.close()
        store = ContentStore(context)
        assertEquals(0, store.project().getJSONArray("pages").length())
        assertEquals(0, store.project().getJSONArray("news").length())
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM sections", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun publishedSnapshotSurvivesReopeningAndLocalSavesDoNotAdvanceIt() {
        assertNull(store.publishedSnapshot())
        val published = store.project()
        store.savePublishedSnapshot(published)
        val draft = JSONObject(published.toString())
        draft.getJSONArray("pages").getJSONObject(0).put("title", "Unpublished edit")
        store.save(draft)
        store.close()
        store = ContentStore(context)
        assertEquals(published.toString(), store.publishedSnapshot())
        assertEquals("Unpublished edit", store.project().getJSONArray("pages").getJSONObject(0).getString("title"))
        store.savePublishedSnapshot(draft)
        assertEquals(draft.toString(), store.publishedSnapshot())
    }

    @Test
    fun failedSaveRollsBackInsteadOfLosingExistingContent() {
        val original = store.project()
        val changed = JSONObject(original.toString())
        val duplicate = JSONObject(changed.getJSONArray("pages").getJSONObject(0).toString()).put("id", "duplicate")
        changed.getJSONArray("pages").put(duplicate)
        assertThrows(Exception::class.java) { store.save(changed) }
        assertEquals(original.toString(), store.project().toString())
    }

    @Test
    fun importedMediaAndFigureContentSurviveReopeningAndExportExcludesTheDatabase() {
        val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
        val media = store.importMedia(ByteArrayInputStream(bytes), "image/png", "Phone photo.png")
        val filename = media.getString("filename")
        assertArrayEquals(bytes, java.io.File(store.mediaDirectory, filename).readBytes())
        val project = store.project()
        project.getJSONArray("pages").getJSONObject(0).put("content",
            "<figure><img src=\"media/$filename\" alt=\"Phone photo\"><figcaption>Caption</figcaption></figure>")
        store.save(project)
        store.close()
        store = ContentStore(context)
        assertEquals(1, store.project().getJSONArray("media").length())
        assertTrue(store.project().getJSONArray("pages").getJSONObject(0).getString("content").contains(filename))
        val unused = store.importMedia(ByteArrayInputStream(bytes), "image/png", "Cancelled figure.png")
        val files = JSONArray().put(JSONObject().put("path", "index.html").put("html", "<h1>Home</h1>"))
            .put(JSONObject().put("path", "about/team/index.html").put("html", "<h1>Team</h1>"))
        val archive = store.exportSite(files, JSONArray().put("assets/coast.svg").put("media/$filename"))
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(entries.containsAll(setOf("index.html", "about/team/index.html", "assets/site.css", "assets/site.js", "assets/logo.svg", "assets/coast.svg", "media/$filename")))
            assertArrayEquals(context.assets.open("website/assets/logo.svg").use { it.readBytes() },
                zip.getInputStream(zip.getEntry("assets/logo.svg")).readBytes())
            assertFalse(entries.any { it.endsWith(".sqlite") || it in setOf("storage.sql", "content.json", "catalog.json", "editor.html", "assets/editor.js") })
            assertFalse(entries.contains("media/${unused.getString("filename")}"))
            assertArrayEquals(bytes, zip.getInputStream(zip.getEntry("media/$filename")).readBytes())
        }
    }

    @Test
    fun namedMediaKeepsItsExtensionAndNumbersCollisionsWithoutOverwriting() {
        val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
        val first = store.importMedia(ByteArrayInputStream(bytes), "image/png", "Phone.png", " coast ")
        val second = store.importMedia(ByteArrayInputStream(bytes + byteArrayOf(0)), "image/png", "Other.png", "coast")
        val third = store.importMedia(ByteArrayInputStream(bytes), "image/png", "Third.png", "Coast")
        val video = store.importMedia(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "video/mp4", "Phone.mp4", "coast")
        assertEquals("coast.png", first.getString("filename"))
        assertEquals("coast-2.png", second.getString("filename"))
        assertEquals("Coast-3.png", third.getString("filename"))
        assertEquals("coast.mp4", video.getString("filename"))
        assertArrayEquals(bytes, java.io.File(store.mediaDirectory, "coast.png").readBytes())
        assertArrayEquals(bytes + byteArrayOf(0), java.io.File(store.mediaDirectory, "coast-2.png").readBytes())
        store.close(); store = ContentStore(context)
        assertEquals(setOf("coast.png", "coast-2.png", "Coast-3.png", "coast.mp4"),
            store.project().getJSONArray("media").let { records ->
                (0 until records.length()).map { records.getJSONObject(it).getString("filename") }.toSet()
            })
        val site = store.prepareSite(JSONArray().put(JSONObject().put("path", "index.html").put("html", "<h1>Home</h1>")),
            JSONArray().put("media/coast.png").put("media/coast-2.png").put("media/coast.mp4"))
        assertArrayEquals(bytes, java.io.File(site, "media/coast.png").readBytes())
        assertArrayEquals(bytes + byteArrayOf(0), java.io.File(site, "media/coast-2.png").readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), java.io.File(site, "media/coast.mp4").readBytes())
    }

    @Test
    fun invalidMediaDoesNotLeaveFilesOrRowsAndTraversalIsRejected() {
        assertThrows(Exception::class.java) { store.importMedia(ByteArrayInputStream(byteArrayOf(1)), "application/octet-stream", "unknown") }
        for (basename in listOf("../private", "/photo", "folder/photo", "photo.jpg", "two words", "a".repeat(81))) {
            assertThrows(IllegalArgumentException::class.java) {
                store.importMedia(ByteArrayInputStream(byteArrayOf(1)), "image/png", "Phone.png", basename)
            }
        }
        assertEquals(0, store.project().getJSONArray("media").length())
        assertEquals(0, store.mediaDirectory.listFiles()!!.size)
        for (path in listOf("../webeasy.sqlite", "/private/file", "media/%2e%2e/file", "child/../../file")) {
            assertThrows(IllegalArgumentException::class.java) { ContentStore.safeFile(store.mediaDirectory, path) }
        }
    }

    @Test
    fun versionOneMigrationPreservesPagesAndLayoutChoicesPersistOnReopen() {
        store.close()
        context.deleteDatabase("webeasy.sqlite")
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("webeasy.sqlite"), null)
        val schema = context.assets.open("website/storage.sql").bufferedReader().use { it.readText() }
            .replace("    template_id TEXT NOT NULL DEFAULT 'page',\n", "")
        schema.split(";").map(String::trim).filter(String::isNotEmpty).forEach(old::execSQL)
        old.execSQL("INSERT INTO metadata (key, value) VALUES ('seeded', '1')")
        old.execSQL("INSERT INTO pages (id, title, menu_title, slug, content, date, position) VALUES ('legacy', 'Old page', 'Old page', 'legacy', '<p>Keep me</p>', '2026-11-01', 0)")
        old.execSQL("INSERT INTO pages (id, parent_id, title, menu_title, slug, content, date, position) VALUES ('legacy-child', 'legacy', 'Child', 'Child', 'child', '<p>Child content</p>', '2026-11-01', 0)")
        old.version = 1
        old.close()
        store = ContentStore(context)
        val project = store.project()
        assertEquals(1, project.getJSONArray("pages").length())
        val page = project.getJSONArray("pages").getJSONObject(0)
        assertEquals("Keep me", page.getString("content").replace(Regex("<[^>]+>"), ""))
        assertEquals("page", page.getString("templateId"))
        assertEquals("page", page.getJSONArray("children").getJSONObject(0).getString("templateId"))
        page.put("templateId", "reading-page")
        store.save(project)
        store.close()
        store = ContentStore(context)
        assertEquals("reading-page", store.project().getJSONArray("pages").getJSONObject(0).getString("templateId"))
        assertEquals(2, store.readableDatabase.version)
    }

    @Test
    fun homepageChoiceSurvivesReopenAndInvalidReferencesRollBack() {
        val project = store.project()
        val first = project.getJSONArray("pages").getJSONObject(0)
        assertEquals(first.getString("id"), project.getString("homePageId"))
        val child = first.getJSONArray("children").getJSONObject(0)
        project.put("homePageId", child.getString("id"))
        store.save(project); store.close(); store = ContentStore(context)
        assertEquals(child.getString("id"), store.project().getString("homePageId"))
        val invalid = JSONObject(project.toString()).put("homePageId", "missing")
        assertThrows(Exception::class.java) { store.save(invalid) }
        assertEquals(child.getString("id"), store.project().getString("homePageId"))
        first.put("children", JSONArray()); project.put("homePageId", first.getString("id"))
        store.save(project)
        assertEquals(first.getString("id"), store.project().getString("homePageId"))
    }

    @Test
    fun draftDiscardRestoresOwnedContentInPlaceWithoutChangingOtherDrafts() {
        val project = store.project()
        val drafts = ContentDrafts()
        drafts.markSaved(drafts.capture(project))
        val page = project.getJSONArray("pages").getJSONObject(0)
        val child = page.getJSONArray("children").getJSONObject(0)
        val news = project.getJSONArray("news").getJSONObject(0)
        assertFalse(drafts.hasChanges(page, project))
        val title = page.getString("title")
        page.put("title", "Changed"); page.put("title", title)
        assertFalse(drafts.hasChanges(page, project))
        child.put("title", "Nested edit")
        news.put("title", "Unrelated draft")
        assertTrue(drafts.hasChanges(page, project))
        drafts.discard(page, project)
        assertSame(child, page.getJSONArray("children").getJSONObject(0))
        assertNotEquals("Nested edit", child.getString("title"))
        assertEquals("Unrelated draft", news.getString("title"))
        assertFalse(drafts.hasChanges(page, project))
        assertTrue(drafts.hasChanges(news, project))
    }

    @Test
    fun homepageAndNewRecordDiscardAndCapturedSaveKeepLaterEditsDirty() {
        val project = store.project()
        val drafts = ContentDrafts()
        drafts.markSaved(drafts.capture(project))
        val page = project.getJSONArray("pages").getJSONObject(0)
        val child = page.getJSONArray("children").getJSONObject(0)
        project.put("homePageId", child.getString("id"))
        assertTrue(drafts.hasChanges(child, project))
        drafts.discard(child, project)
        assertEquals(page.getString("id"), project.getString("homePageId"))
        page.put("title", "Saving this")
        val snapshot = drafts.capture(project)
        page.put("title", "Newer edit")
        drafts.markSaved(snapshot)
        assertTrue(drafts.hasChanges(page, project))
        drafts.discard(page, project)
        assertEquals("Saving this", page.getString("title"))
        val fresh = JSONObject(page.toString()).put("id", "new-draft")
        project.getJSONArray("pages").put(fresh)
        assertTrue(drafts.hasChanges(fresh, project))
        drafts.discard(fresh, project)
        assertEquals(1, project.getJSONArray("pages").length())
    }
}

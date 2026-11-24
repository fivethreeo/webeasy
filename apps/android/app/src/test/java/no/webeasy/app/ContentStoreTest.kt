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
            assertTrue(entries.containsAll(setOf("index.html", "about/team/index.html", "assets/site.css", "assets/site.js", "assets/coast.svg", "media/$filename")))
            assertFalse(entries.any { it.endsWith(".sqlite") || it in setOf("storage.sql", "content.json", "catalog.json", "editor.html", "assets/editor.js") })
            assertFalse(entries.contains("media/${unused.getString("filename")}"))
            assertArrayEquals(bytes, zip.getInputStream(zip.getEntry("media/$filename")).readBytes())
        }
    }

    @Test
    fun invalidMediaDoesNotLeaveFilesOrRowsAndTraversalIsRejected() {
        assertThrows(Exception::class.java) { store.importMedia(ByteArrayInputStream(byteArrayOf(1)), "application/octet-stream", "unknown") }
        assertEquals(0, store.project().getJSONArray("media").length())
        assertEquals(0, store.mediaDirectory.listFiles()!!.size)
        for (path in listOf("../webeasy.sqlite", "/private/file", "media/%2e%2e/file", "child/../../file")) {
            assertThrows(IllegalArgumentException::class.java) { ContentStore.safeFile(store.mediaDirectory, path) }
        }
    }
}

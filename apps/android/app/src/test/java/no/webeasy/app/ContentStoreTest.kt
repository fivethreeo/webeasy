package no.webeasy.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    fun backupRestoresDatabaseMediaAndCustomizationTogether() {
        val project = store.project()
        project.getJSONArray("pages").getJSONObject(0).put("title", "Backed up title")
        store.save(project)
        val assets = mapOf("assets/site.css" to "custom css".toByteArray(),
            "assets/site.js" to byteArrayOf(), "assets/logo.svg" to "<svg/>".toByteArray())
        store.saveCustomization(JSONObject().put("webeasy_templates/templates/master.html", "{{title}}"), assets)
        java.io.File(store.mediaDirectory, "owned.bin").writeBytes(byteArrayOf(7, 8, 9))
        val snapshot = store.backupSnapshot()
        try {
            project.getJSONArray("pages").getJSONObject(0).put("title", "Later title")
            store.save(project)
            java.io.File(store.mediaDirectory, "owned.bin").writeText("Later bytes")
            store.restoreSnapshot(snapshot)
            store.close()
            store = ContentStore(context)
            assertEquals("Backed up title", store.project().getJSONArray("pages").getJSONObject(0).getString("title"))
            assertEquals("{{title}}", store.templateSources()!!.getString("webeasy_templates/templates/master.html"))
            assertArrayEquals(assets.getValue("assets/site.css"), store.assetFiles().getValue("assets/site.css"))
            assertArrayEquals(byteArrayOf(7, 8, 9), java.io.File(store.mediaDirectory, "owned.bin").readBytes())
        } finally { snapshot.deleteRecursively() }
    }

    @Test
    fun invalidBackupDoesNotChangeCurrentDatabaseOrMedia() {
        val before = store.project().toString()
        java.io.File(store.mediaDirectory, "owned.bin").writeText("Keep this")
        val snapshot = store.backupSnapshot()
        try {
            java.io.File(snapshot, "webeasy.sqlite").writeText("Not SQLite")
            assertThrows(Exception::class.java) { store.restoreSnapshot(snapshot) }
            assertEquals(before, store.project().toString())
            assertEquals("Keep this", java.io.File(store.mediaDirectory, "owned.bin").readText())
        } finally { snapshot.deleteRecursively() }
    }

    @Test
    fun startupRecoversInterruptedRestoreEvenWhenDatabaseWasAlreadyMovedBackBeforeAnotherCrash() {
        val expected = store.project().toString()
        java.io.File(store.mediaDirectory, "owned.bin").writeText("Original media")
        val snapshot = store.backupSnapshot()
        store.close()
        val recovery = java.io.File(context.filesDir, "restore-recovery-${java.util.UUID.randomUUID()}")
        assertTrue(snapshot.renameTo(recovery))
        java.io.File(context.filesDir, "webeasy-restore.json").writeText(
            JSONObject().put("recovery", recovery.name).toString())
        context.deleteDatabase("webeasy.sqlite")
        assertTrue(java.io.File(recovery, "webeasy.sqlite").renameTo(context.getDatabasePath("webeasy.sqlite")))
        java.io.File(store.mediaDirectory, "owned.bin").writeText("Partially restored media")
        store = ContentStore(context)
        assertEquals(expected, store.project().toString())
        assertEquals("Original media", java.io.File(store.mediaDirectory, "owned.bin").readText())
        assertFalse(java.io.File(context.filesDir, "webeasy-restore.json").exists())
        assertFalse(recovery.exists())
    }

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
    fun resolvingNewSlugConflictsSavesAndReopensWithoutChangingExistingRoutes() {
        val project = store.project()
        val drafts = ContentDrafts()
        drafts.markSaved(drafts.capture(project))
        val pages = project.getJSONArray("pages")
        val news = project.getJSONArray("news")
        val originalPage = pages.getJSONObject(0).getString("slug")
        val originalNews = news.getJSONObject(0).getString("slug")
        fun duplicate(items: JSONArray, id: String) {
            items.put(JSONObject(items.getJSONObject(0).toString()).put("id", id)
                .put("children", JSONArray()).put("sections", JSONArray()).put("asides", JSONArray()))
        }
        duplicate(pages, "new-page")
        duplicate(news, "new-news")
        fun records(items: JSONArray) = (0 until items.length()).map { items.getJSONObject(it) }
        assertTrue(ContentSlugs.resolve(records(pages), drafts::isSaved, rootPage = true))
        assertTrue(ContentSlugs.resolve(records(news), drafts::isSaved, news = true))
        store.save(project)
        store.close(); store = ContentStore(context)
        val saved = store.project()
        assertEquals(originalPage, saved.getJSONArray("pages").getJSONObject(0).getString("slug"))
        assertEquals("$originalPage-2", records(saved.getJSONArray("pages")).first { it.getString("id") == "new-page" }.getString("slug"))
        assertEquals("$originalNews-2", records(saved.getJSONArray("news")).first { it.getString("id") == "new-news" }.getString("slug"))
    }

    @Test
    fun downloadedTemplatesPersistAcrossContentSavesAndAppReopening() {
        assertNull(store.templateSources())
        val sources = JSONObject().put("webeasy_templates/templates/master.html", "<html>Edited{{{content}}}</html>")
        store.saveTemplateSources(sources)
        val project = store.project()
        project.getJSONArray("pages").getJSONObject(0).put("title", "Content changed")
        store.save(project)
        store.close(); store = ContentStore(context)
        assertEquals(sources.toString(), store.templateSources().toString())
        assertEquals("Content changed", store.project().getJSONArray("pages").getJSONObject(0).getString("title"))
    }

    @Test
    fun customizationAssetsAndSourcesSurviveSavesAndLargeFilesFitCursorWindows() {
        val files = store.customizationFiles()
        val sources = JSONObject()
        files.filterKeys { it.startsWith("webeasy_templates/") }.forEach { (path, bytes) -> sources.put(path, bytes.toString(Charsets.UTF_8)) }
        sources.put("webeasy_templates/components/site-footer.html", "<footer>Customized footer</footer>")
        val assets = files.filterKeys { it.startsWith("assets/") }.toMutableMap()
        val font = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        assets["assets/fonts/custom.woff2"] = font
        assets["assets/site.css"] = "body { color: green; }".toByteArray()
        store.saveCustomization(sources, assets)
        store.save(store.project())
        store.close(); store = ContentStore(context)
        assertEquals("<footer>Customized footer</footer>", store.templateSources()!!.getString("webeasy_templates/components/site-footer.html"))
        assertArrayEquals(font, store.assetFiles()["assets/fonts/custom.woff2"])
        val site = store.prepareSite(JSONArray().put(JSONObject().put("path", "index.html").put("html", "<h1>Customized</h1>")), JSONArray())
        try {
            assertEquals("body { color: green; }", java.io.File(site, "assets/site.css").readText())
            assertArrayEquals(font, java.io.File(site, "assets/fonts/custom.woff2").readBytes())
            assertFalse(java.io.File(site, "assets/generator.js").exists())
            assertFalse(java.io.File(site, "webeasy_templates").exists())
        } finally { site.deleteRecursively() }
    }

    @Test
    fun importingInvalidAssetsCannotReplaceCurrentCustomization() {
        val files = store.customizationFiles()
        val sources = JSONObject().put("webeasy_templates/components/site-footer.html", "<footer>Kept</footer>")
        val assets = files.filterKeys { it.startsWith("assets/") }
        store.saveCustomization(sources, assets)
        val invalid = assets + ("assets/generator.js" to "Untrusted editor replacement".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            store.saveCustomization(JSONObject().put("webeasy_templates/components/site-footer.html", "<footer>Changed</footer>"), invalid)
        }
        assertEquals("<footer>Kept</footer>", store.templateSources()!!.getString("webeasy_templates/components/site-footer.html"))
        assertFalse(store.assetFiles().containsKey("assets/generator.js"))
        for (path in listOf("assets/../field.js", "assets//site.css", "assets/.hidden/../../outside", "/assets/site.css")) {
            assertFalse(CustomizationAssets.validPath(path))
        }
    }

    @Test
    fun largeTemplateCollectionsUseIndividualRowsAndLegacySourcesStillLoad() {
        val path = "webeasy_templates/components/site-footer.html"
        writableLegacyTemplate(path)
        assertEquals("Legacy source", store.templateSources()!!.getString(path))
        val sources = JSONObject()
        for (index in 0 until 12) sources.put("webeasy_templates/components/custom-$index.html", "x".repeat(220000))
        store.saveTemplateSources(sources)
        store.close(); store = ContentStore(context)
        val restored = store.templateSources()!!
        assertEquals(12, restored.length())
        assertEquals(sources.getString("webeasy_templates/components/custom-11.html"), restored.getString("webeasy_templates/components/custom-11.html"))
    }

    private fun writableLegacyTemplate(path: String) {
        store.writableDatabase.execSQL("INSERT OR REPLACE INTO metadata (key, value) VALUES ('template_sources', ?)",
            arrayOf(JSONObject().put(path, "Legacy source").toString()))
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

    private fun imageBytes(format: Bitmap.CompressFormat, width: Int = 160, height: Int = 80): ByteArray {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until image.height) for (x in 0 until image.width) {
                image.setPixel(x, y, Color.argb(96, x * 37 % 256, y * 19 % 256, x * y * 7 % 256))
            }
            return ByteArrayOutputStream().use { output ->
                check(image.compress(format, 100, output))
                output.toByteArray()
            }
        } finally { image.recycle() }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun resizedJpegPreservesProportionsAndUsesRequestedWebQuality() {
        val bytes = imageBytes(Bitmap.CompressFormat.JPEG)
        val high = store.importMedia(ByteArrayInputStream(bytes), "image/jpeg", "Photo.jpg", "web-photo", 80, 95)
        val low = store.importMedia(ByteArrayInputStream(bytes), "image/jpeg", "Photo.jpg", "web-photo", 80, 25)
        val highFile = java.io.File(store.mediaDirectory, high.getString("filename"))
        val lowFile = java.io.File(store.mediaDirectory, low.getString("filename"))
        val image = BitmapFactory.decodeFile(highFile.path)
        try {
            assertEquals(80, image.width)
            assertEquals(40, image.height)
            assertEquals("web-photo.jpg", highFile.name)
            assertEquals("web-photo-2.jpg", lowFile.name)
            assertEquals("image/jpeg", high.getString("mimeType"))
            assertTrue(lowFile.length() < highFile.length())
            assertEquals(highFile.length(), high.getLong("size"))
        } finally { image.recycle() }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun coversRejectUndersizedImagesAndPersistAlongsideIndependentNewsText() {
        val small = imageBytes(Bitmap.CompressFormat.JPEG)
        assertThrows(IllegalArgumentException::class.java) {
            store.importMedia(ByteArrayInputStream(small), "image/jpeg", "Small.jpg", minimumWidth = 1200)
        }
        val bytes = imageBytes(Bitmap.CompressFormat.JPEG, 1400, 700)
        assertThrows(IllegalArgumentException::class.java) {
            store.importMedia(ByteArrayInputStream(bytes), "image/jpeg", "Small output.jpg", width = 400, quality = 85, minimumWidth = 1200)
        }
        assertEquals(0, store.project().getJSONArray("media").length())
        assertEquals(0, store.mediaDirectory.listFiles()!!.size)
        val cover = store.importMedia(ByteArrayInputStream(bytes), "image/jpeg", "Cover.jpg", width = 1200, quality = 85, minimumWidth = 1200)
        val project = store.project()
        val news = project.getJSONArray("news").getJSONObject(0)
        news.put("shortContent", "<p>Summary</p>").put("content", "<h2>Full story</h2><p>Details</p>")
            .put("coverMediaId", cover.getString("id")).put("coverAlt", "Coast")
        store.save(project)
        store.close(); store = ContentStore(context)
        val loaded = store.project().getJSONArray("news").getJSONObject(0)
        assertEquals("<p>Summary</p>", loaded.getString("shortContent"))
        assertEquals("<h2>Full story</h2><p>Details</p>", loaded.getString("content"))
        assertEquals(cover.getString("id"), loaded.getString("coverMediaId"))
        assertEquals("Coast", loaded.getString("coverAlt"))
        val image = BitmapFactory.decodeFile(java.io.File(store.mediaDirectory, cover.getString("filename")).path)
        try { assertEquals(1200, image.width); assertEquals(600, image.height) } finally { image.recycle() }
        loaded.put("coverMediaId", "").put("coverAlt", "")
        store.save(store.project().also { it.getJSONArray("news").put(0, loaded) })
        assertEquals("", store.project().getJSONArray("news").getJSONObject(0).getString("coverMediaId"))
        assertEquals(1, store.project().getJSONArray("media").length())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun coverMinimumWidthUsesUprightExifDimensions() {
        val source = java.io.File.createTempFile("cover-orientation", ".jpg", context.cacheDir)
        try {
            source.writeBytes(imageBytes(Bitmap.CompressFormat.JPEG, 1400, 700))
            androidx.exifinterface.media.ExifInterface(source.absolutePath).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.importMedia(source.inputStream(), "image/jpeg", "Rotated cover.jpg", minimumWidth = 1200)
            }
            assertEquals(0, store.project().getJSONArray("media").length())
            assertEquals(0, store.mediaDirectory.listFiles()!!.size)
        } finally { source.delete() }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun resizedPngKeepsTransparencyAndSmallerImagesAreNotEnlarged() {
        val bytes = imageBytes(Bitmap.CompressFormat.PNG)
        for ((width, expectedWidth) in listOf(40 to 40, 320 to 160)) {
            val media = store.importMedia(ByteArrayInputStream(bytes), "image/png", "Transparent.png", width = width, quality = 50)
            val image = BitmapFactory.decodeFile(java.io.File(store.mediaDirectory, media.getString("filename")).path)
            try {
                assertEquals(expectedWidth, image.width)
                assertEquals(expectedWidth / 2, image.height)
                assertEquals("image/png", media.getString("mimeType"))
                assertTrue(image.hasAlpha())
                assertEquals(96, Color.alpha(image.getPixel(0, 0)))
            } finally { image.recycle() }
        }
    }

    @Test
    @Config(sdk = [26, 28])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun imageOrientationIsAppliedBeforeChoosingTheRequestedWidth() {
        val source = java.io.File.createTempFile("orientation-", ".jpg", context.cacheDir)
        try {
            source.writeBytes(imageBytes(Bitmap.CompressFormat.JPEG))
            ExifInterface(source.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val media = store.importMedia(source.inputStream(), "image/jpeg", "Portrait.jpg", width = 20)
            val image = BitmapFactory.decodeFile(java.io.File(store.mediaDirectory, media.getString("filename")).path)
            try {
                assertEquals(20, image.width)
                assertEquals(40, image.height)
            } finally { image.recycle() }
        } finally { source.delete() }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun invalidOptimizationAndAnimatedImagesLeaveNoPartialFilesOrRows() {
        val bytes = imageBytes(Bitmap.CompressFormat.JPEG)
        for ((width, quality) in listOf(0 to 85, 16385 to 85, 80 to 0, 80 to 101)) {
            assertThrows(IllegalArgumentException::class.java) {
                store.importMedia(ByteArrayInputStream(bytes), "image/jpeg", "Photo.jpg", width = width, quality = quality)
            }
        }
        val animated = Base64.getDecoder().decode("R0lGODlhAgABAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAgABAAAIBQABAAgIACH5BAEKAAEALAAAAAACAAEAgQAA/wAAAAAAAAAAAAgFAAEACAgAOw==")
        assertThrows(IllegalArgumentException::class.java) {
            store.importMedia(ByteArrayInputStream(animated), "image/gif", "Animation.gif", width = 1)
        }
        assertEquals(0, store.project().getJSONArray("media").length())
        assertEquals(0, store.mediaDirectory.listFiles()!!.size)
        val original = store.importMedia(ByteArrayInputStream(animated), "image/gif", "Animation.gif")
        assertArrayEquals(animated, java.io.File(store.mediaDirectory, original.getString("filename")).readBytes())
    }

    @Test
    fun versionOneMigrationPreservesPagesAndLayoutChoicesPersistOnReopen() {
        store.close()
        context.deleteDatabase("webeasy.sqlite")
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("webeasy.sqlite"), null)
        val schema = context.assets.open("website/storage.sql").bufferedReader().use { it.readText() }
            .replace("    template_id TEXT NOT NULL DEFAULT 'page',\n", "")
            .replace("    cover_media_id TEXT REFERENCES media(id) ON DELETE SET NULL,\n", "")
            .replace("    cover_alt TEXT NOT NULL DEFAULT '',\n", "")
            .replace("    subheading TEXT NOT NULL DEFAULT '',\n", "")
            .replace("    content TEXT NOT NULL DEFAULT '',\n", "")
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
        assertEquals(4, store.readableDatabase.version)
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
    fun versionTwoMigrationPreservesContentAndAddsEmptyOptionalFields() {
        store.close()
        context.deleteDatabase("webeasy.sqlite")
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("webeasy.sqlite"), null)
        val schema = context.assets.open("website/storage.sql").bufferedReader().use { it.readText() }
            .replace("    cover_media_id TEXT REFERENCES media(id) ON DELETE SET NULL,\n", "")
            .replace("    cover_alt TEXT NOT NULL DEFAULT '',\n", "")
            .replace("    subheading TEXT NOT NULL DEFAULT '',\n", "")
            .replace("    content TEXT NOT NULL DEFAULT '',\n", "")
        schema.split(";").map(String::trim).filter(String::isNotEmpty).forEach(old::execSQL)
        old.execSQL("INSERT INTO metadata (key, value) VALUES ('seeded', '1')")
        old.execSQL("INSERT INTO pages (id, title, menu_title, template_id, slug, content, date, position) VALUES ('legacy', 'Old page', 'Menu', 'reading-page', 'legacy', '<p>Keep me</p>', '2026-11-01', 0)")
        old.execSQL("INSERT INTO sections (id, page_id, heading, content, position) VALUES ('legacy-section', 'legacy', 'Heading', '<p>Section</p>', 0)")
        old.version = 2
        old.close()
        store = ContentStore(context)
        val page = store.project().getJSONArray("pages").getJSONObject(0)
        assertEquals("reading-page", page.getString("templateId"))
        assertEquals("<p>Keep me</p>", page.getString("content"))
        assertEquals("", page.getString("coverMediaId"))
        val section = page.getJSONArray("sections").getJSONObject(0)
        assertEquals("", section.getString("subheading"))
        section.put("subheading", "An introduction")
        store.save(store.project().also { it.getJSONArray("pages").put(0, page) })
        store.close(); store = ContentStore(context)
        assertEquals("An introduction", store.project().getJSONArray("pages").getJSONObject(0)
            .getJSONArray("sections").getJSONObject(0).getString("subheading"))
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
    fun versionThreeMigrationRetainsLegacyShortMarkupInTheNewLongField() {
        store.close()
        context.deleteDatabase("webeasy.sqlite")
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("webeasy.sqlite"), null)
        val schema = context.assets.open("website/storage.sql").bufferedReader().use { it.readText() }
            .replace("    content TEXT NOT NULL DEFAULT '',\n", "")
        schema.split(";").map(String::trim).filter(String::isNotEmpty).forEach(old::execSQL)
        old.execSQL("INSERT INTO metadata (key, value) VALUES ('seeded', '1')")
        val rich = "<h2>Existing heading</h2><figure><img src=\"media/old.jpg\"></figure>"
        old.execSQL("INSERT INTO news (id,title,slug,date,short_content) VALUES ('legacy-news','Old story','old','2026-11-01',?)", arrayOf(rich))
        old.version = 3
        old.close()
        store = ContentStore(context)
        val news = store.project().getJSONArray("news").getJSONObject(0)
        assertEquals(rich, news.getString("content"))
        assertEquals(rich, news.getString("shortContent"))
        assertEquals(4, store.readableDatabase.version)
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

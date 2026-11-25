package no.webeasy.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContentStore(private val context: Context) :
    SQLiteOpenHelper(context, "webeasy.sqlite", null, 2) {
    val mediaDirectory = File(context.filesDir, "Media").apply { mkdirs() }

    init {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (rows(db, "SELECT value FROM metadata WHERE key = ?", arrayOf("seeded")).length() == 0) {
                val seed = JSONObject(context.assets.open("website/content.json").bufferedReader().use { it.readText() })
                writeContent(db, seed)
                db.execSQL("INSERT INTO metadata (key, value) VALUES (?, ?)", arrayOf("seeded", "1"))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        context.assets.open("website/storage.sql").bufferedReader().use { reader ->
            reader.readText().split(";").map(String::trim).filter(String::isNotEmpty).forEach(db::execSQL)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "A database migration is required. Your existing content has not been deleted." }
        db.execSQL("ALTER TABLE pages ADD COLUMN template_id TEXT NOT NULL DEFAULT 'page'")
    }

    private fun rows(db: SQLiteDatabase, sql: String, args: Array<String>? = null): JSONArray {
        val result = JSONArray()
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val record = JSONObject()
                cursor.columnNames.forEachIndexed { index, name ->
                    record.put(name, when {
                        cursor.isNull(index) -> JSONObject.NULL
                        cursor.getType(index) == android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        else -> cursor.getString(index)
                    })
                }
                result.put(record)
            }
        }
        return result
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    private fun asides(db: SQLiteDatabase, owner: String, id: String): JSONArray = JSONArray(
        rows(db, "SELECT id, heading, content FROM asides WHERE ${owner}_id = ? ORDER BY position", arrayOf(id)).objects(),
    )

    private fun related(db: SQLiteDatabase, record: JSONObject, owner: String): JSONObject {
        val id = record.getString("id")
        record.put("asides", asides(db, owner, id))
        val sections = rows(db, "SELECT id, heading, content FROM sections WHERE ${owner}_id = ? ORDER BY position", arrayOf(id))
        sections.objects().forEach { section -> section.put("asides", asides(db, "section", section.getString("id"))) }
        record.put("sections", sections)
        return record
    }

    private fun pages(db: SQLiteDatabase, parent: String?): JSONArray {
        val records = if (parent == null) rows(db,
            "SELECT id, title, menu_title AS menuTitle, template_id AS templateId, slug, content, date FROM pages WHERE parent_id IS NULL ORDER BY position")
        else rows(db, "SELECT id, title, menu_title AS menuTitle, template_id AS templateId, slug, content, date FROM pages WHERE parent_id = ? ORDER BY position", arrayOf(parent))
        records.objects().forEach { page ->
            related(db, page, "page")
            page.put("children", pages(db, page.getString("id")))
        }
        return records
    }

    fun project(): JSONObject {
        val db = readableDatabase
        val pages = pages(db, null)
        val home = rows(db, "SELECT value FROM metadata WHERE key = 'home_page_id'").objects().firstOrNull()?.getString("value")
            ?: pages.objects().firstOrNull()?.getString("id")
        val news = rows(db, "SELECT id, title, slug, date, short_content AS shortContent FROM news ORDER BY date DESC, slug")
        news.objects().forEach { related(db, it, "news") }
        return JSONObject().put("version", 1).put("homePageId", home ?: JSONObject.NULL).put("pages", pages).put("news", news)
            .put("media", rows(db, "SELECT id, filename, mime_type AS mimeType, original_name AS originalName, size FROM media ORDER BY id"))
    }

    fun save(project: JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            writeContent(db, project)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun publishedSnapshot(): String? =
        rows(readableDatabase, "SELECT value FROM metadata WHERE key = 'published_project'")
            .optJSONObject(0)?.getString("value")

    fun savePublishedSnapshot(project: JSONObject) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO metadata (key, value) VALUES ('published_project', ?)",
            arrayOf(project.toString()))
    }

    private fun insert(db: SQLiteDatabase, table: String, values: Map<String, Any?>) {
        val fields = ContentValues()
        values.forEach { (key, value) ->
            when (value) {
                null -> fields.putNull(key)
                is Int -> fields.put(key, value)
                is Long -> fields.put(key, value)
                else -> fields.put(key, value.toString())
            }
        }
        db.insertOrThrow(table, null, fields)
    }

    private fun text(record: JSONObject, key: String, required: Boolean = false): String {
        require(record.get(key) is String) { "Missing $key." }
        return record.getString(key).also { if (required) require(it.isNotBlank()) { "Missing $key." } }
    }

    private fun id(record: JSONObject) = text(record, "id", true).also {
        require(it.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid content ID." }
    }

    private fun slug(record: JSONObject) = text(record, "slug", true).also {
        require(it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Invalid slug." }
    }

    private fun date(record: JSONObject) = text(record, "date", true).also {
        require(it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && LocalDate.parse(it).toString() == it) { "Invalid date." }
    }

    private fun writeAsides(db: SQLiteDatabase, record: JSONObject, owner: String, ownerId: String) {
        record.optJSONArray("asides")?.objects()?.forEachIndexed { position, aside ->
            insert(db, "asides", mapOf("id" to id(aside), "${owner}_id" to ownerId,
                "heading" to aside.optString("heading", ""), "content" to text(aside, "content"), "position" to position))
        }
    }

    private fun writeRelated(db: SQLiteDatabase, record: JSONObject, owner: String, ownerId: String) {
        writeAsides(db, record, owner, ownerId)
        record.optJSONArray("sections")?.objects()?.forEachIndexed { position, section ->
            val sectionId = id(section)
            insert(db, "sections", mapOf("id" to sectionId, "${owner}_id" to ownerId,
                "heading" to text(section, "heading", true), "content" to text(section, "content"), "position" to position))
            writeAsides(db, section, "section", sectionId)
        }
    }

    private fun writePages(db: SQLiteDatabase, records: JSONArray, parent: String?) {
        records.objects().forEachIndexed { position, page ->
            val pageId = id(page)
            val pageSlug = slug(page)
            val templateId = if (page.has("templateId")) text(page, "templateId", true) else "page"
            require(templateId.matches(Regex("[a-z][a-z0-9-]*"))) { "Invalid page layout ID." }
            if (parent == null) require(pageSlug !in setOf("news", "assets", "media", "examples", "components", "editor", "provision", "catalog", "content", "index"))
            insert(db, "pages", mapOf("id" to pageId, "parent_id" to parent, "title" to text(page, "title", true),
                "menu_title" to text(page, "menuTitle", true), "template_id" to templateId, "slug" to pageSlug, "content" to text(page, "content"),
                "date" to date(page), "position" to position))
            writeRelated(db, page, "page", pageId)
            writePages(db, page.optJSONArray("children") ?: JSONArray(), pageId)
        }
    }

    private fun writeContent(db: SQLiteDatabase, project: JSONObject) {
        require(project.getInt("version") == 1)
        db.delete("pages", null, null)
        db.delete("news", null, null)
        writePages(db, project.getJSONArray("pages"), null)
        db.delete("metadata", "key = ?", arrayOf("home_page_id"))
        if (project.has("homePageId") && !project.isNull("homePageId")) {
            val home = text(project, "homePageId", true)
            require(rows(db, "SELECT id FROM pages WHERE id = ?", arrayOf(home)).length() == 1) { "Invalid homepage." }
            db.execSQL("INSERT INTO metadata (key, value) VALUES ('home_page_id', ?)", arrayOf(home))
        }
        project.getJSONArray("news").objects().forEach { news ->
            val newsId = id(news)
            insert(db, "news", mapOf("id" to newsId, "title" to text(news, "title", true),
                "slug" to slug(news), "date" to date(news), "short_content" to text(news, "shortContent")))
            writeRelated(db, news, "news", newsId)
        }
    }

    fun importMedia(input: InputStream, mimeType: String, originalName: String, basename: String = ""): JSONObject {
        val id = UUID.randomUUID().toString()
        val requested = basename.trim()
        val stem = requested.ifEmpty { id }
        val temporary = File(mediaDirectory, "$id.incoming")
        var destination: File? = null
        fun nextFile(extension: String): File {
            var number = 1
            while (true) {
                val filename = "$stem${if (number == 1) "" else "-$number"}.$extension"
                val file = safeFile(mediaDirectory, filename)
                if (!file.exists() && rows(readableDatabase, "SELECT id FROM media WHERE filename = ? COLLATE NOCASE",
                        arrayOf(filename)).length() == 0) return file
                number += 1
            }
        }
        try {
            input.use { source ->
                require(requested.isEmpty() || requested.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) {
                    "Use up to 80 letters, numbers, hyphens, or underscores for the basename, without an extension."
                }
                temporary.outputStream().use(source::copyTo)
            }
            require(temporary.length() > 0) { "The selected media file is empty." }
            var type = mimeType.lowercase()
            if (type in setOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temporary.path, bounds)
                val options = BitmapFactory.Options()
                while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2560) options.inSampleSize *= 2
                val image = BitmapFactory.decodeFile(temporary.path, options) ?: error("Cannot decode this image. Choose JPEG or PNG.")
                try {
                    destination = nextFile("jpg")
                    destination.outputStream().use { require(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                } finally { image.recycle() }
                type = "image/jpeg"
            } else {
                val extension = extensions[type] ?: error("Unsupported media format. Use JPEG, PNG, GIF, WebP, or MP4.")
                destination = nextFile(extension)
                check(temporary.renameTo(destination)) { "Cannot store the selected media." }
            }
            val file = requireNotNull(destination)
            val media = JSONObject().put("id", id).put("filename", file.name).put("mimeType", type)
                .put("originalName", originalName).put("size", file.length())
            insert(writableDatabase, "media", mapOf("id" to id, "filename" to file.name,
                "mime_type" to type, "original_name" to originalName, "size" to file.length()))
            return media
        } catch (error: Exception) {
            destination?.delete()
            throw error
        } finally { temporary.delete() }
    }

    fun prepareSite(files: JSONArray, references: JSONArray): File {
        val token = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "website-$token").apply { mkdirs() }
        try {
            for (record in files.objects()) {
                val path = record.getString("path")
                require(path.endsWith(".html"))
                safeFile(directory, path).also { it.parentFile!!.mkdirs() }.writeText(record.getString("html"))
            }
            for (path in listOf("assets/site.css", "assets/site.js", "assets/logo.svg") + List(references.length()) { references.getString(it) }) {
                val target = safeFile(directory, path).also { it.parentFile!!.mkdirs() }
                if (path.startsWith("assets/")) context.assets.open("website/$path").use { source -> target.outputStream().use(source::copyTo) }
                else {
                    require(path.startsWith("media/"))
                    val filename = path.removePrefix("media/")
                    require(rows(readableDatabase, "SELECT id FROM media WHERE filename = ?", arrayOf(filename)).length() == 1) { "Referenced media is missing." }
                    safeFile(mediaDirectory, filename).copyTo(target, overwrite = true)
                }
            }
            return directory
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun exportSite(files: JSONArray, references: JSONArray): File {
        val directory = prepareSite(files, references)
        val archive = File(context.cacheDir, "exports/webeasy-${UUID.randomUUID()}.zip").also { it.parentFile!!.mkdirs() }
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                directory.walkTopDown().filter(File::isFile).forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return archive
        } catch (error: Exception) {
            archive.delete()
            throw error
        } finally { directory.deleteRecursively() }
    }

    companion object {
        val extensions = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif",
            "image/webp" to "webp", "video/mp4" to "mp4", "video/webm" to "webm",
            "video/quicktime" to "mov", "video/ogg" to "ogg")

        fun safeFile(root: File, path: String): File {
            require(path.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._/-]*")) && path.split("/").none { it == ".." || it == "." })
            return File(root, path).canonicalFile.also {
                require(it.path.startsWith(root.canonicalPath + File.separator)) { "Invalid file path." }
            }
        }
    }
}

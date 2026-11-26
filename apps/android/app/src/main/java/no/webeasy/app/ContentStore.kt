package no.webeasy.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.media.ExifInterface
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

class ContentStore(private val context: Context) :
    SQLiteOpenHelper(context, "webeasy.sqlite", null, 4) {
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
        check(oldVersion in 1..3 && newVersion == 4) { "A database migration is required. Your existing content has not been deleted." }
        if (oldVersion == 1) db.execSQL("ALTER TABLE pages ADD COLUMN template_id TEXT NOT NULL DEFAULT 'page'")
        if (oldVersion < 3) {
            for (table in listOf("pages", "news")) {
                db.execSQL("ALTER TABLE $table ADD COLUMN cover_media_id TEXT REFERENCES media(id) ON DELETE SET NULL")
                db.execSQL("ALTER TABLE $table ADD COLUMN cover_alt TEXT NOT NULL DEFAULT ''")
            }
            db.execSQL("ALTER TABLE sections ADD COLUMN subheading TEXT NOT NULL DEFAULT ''")
        }
        db.execSQL("ALTER TABLE news ADD COLUMN content TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE news SET content = short_content")
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
        val sections = rows(db, "SELECT id, heading, subheading, content FROM sections WHERE ${owner}_id = ? ORDER BY position", arrayOf(id))
        sections.objects().forEach { section -> section.put("asides", asides(db, "section", section.getString("id"))) }
        record.put("sections", sections)
        return record
    }

    private fun pages(db: SQLiteDatabase, parent: String?): JSONArray {
        val records = if (parent == null) rows(db,
            "SELECT id, title, menu_title AS menuTitle, template_id AS templateId, COALESCE(cover_media_id, '') AS coverMediaId, cover_alt AS coverAlt, slug, content, date FROM pages WHERE parent_id IS NULL ORDER BY position")
        else rows(db, "SELECT id, title, menu_title AS menuTitle, template_id AS templateId, COALESCE(cover_media_id, '') AS coverMediaId, cover_alt AS coverAlt, slug, content, date FROM pages WHERE parent_id = ? ORDER BY position", arrayOf(parent))
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
        val news = rows(db, "SELECT id, title, slug, date, content, COALESCE(cover_media_id, '') AS coverMediaId, cover_alt AS coverAlt, short_content AS shortContent FROM news ORDER BY date DESC, slug")
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

    fun templateSources(): JSONObject? =
        rows(readableDatabase, "SELECT value FROM metadata WHERE key = 'template_sources'")
            .optJSONObject(0)?.getString("value")?.let(::JSONObject)

    fun saveTemplateSources(sources: JSONObject) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO metadata (key, value) VALUES ('template_sources', ?)",
            arrayOf(sources.toString()))
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
                "heading" to text(section, "heading", true), "subheading" to section.optString("subheading"),
                "content" to text(section, "content"), "position" to position))
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
                "menu_title" to text(page, "menuTitle", true), "template_id" to templateId,
                "cover_media_id" to page.optString("coverMediaId").takeIf(String::isNotEmpty), "cover_alt" to page.optString("coverAlt"),
                "slug" to pageSlug, "content" to text(page, "content"),
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
                "slug" to slug(news), "date" to date(news), "short_content" to text(news, "shortContent"),
                "content" to if (news.has("content")) text(news, "content") else text(news, "shortContent"),
                "cover_media_id" to news.optString("coverMediaId").takeIf(String::isNotEmpty), "cover_alt" to news.optString("coverAlt")))
            writeRelated(db, news, "news", newsId)
        }
    }

    private fun imageSize(originalWidth: Int, originalHeight: Int, width: Int?): Pair<Int, Int> {
        require(originalWidth > 0 && originalHeight > 0) { "Cannot read this image. Choose JPEG or PNG." }
        val targetWidth = minOf(width ?: originalWidth, originalWidth)
        val targetHeight = maxOf(1, (originalHeight.toDouble() * targetWidth / originalWidth).roundToInt())
        require(targetWidth.toLong() * targetHeight <= 32_000_000) {
            "Image is too large to optimize. Choose a smaller maximum width."
        }
        return targetWidth to targetHeight
    }

    private fun optimizedBitmap(source: File, mimeType: String, width: Int?): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(source)) { decoder, info, _ ->
                val (targetWidth, targetHeight) = imageSize(info.size.width, info.size.height, width)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(targetWidth, targetHeight)
            }
            require(drawable !is AnimatedImageDrawable) {
                "Animated images must be imported with width and quality left blank."
            }
            return (drawable as? BitmapDrawable)?.bitmap ?: error("Cannot resize this image. Choose JPEG or PNG.")
        }
        require(mimeType !in setOf("image/gif", "image/webp")) {
            "On this Android version, import GIF and WebP with width and quality left blank."
        }
        val orientation = runCatching {
            ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val rotated = orientation in 5..8
        val (targetWidth, targetHeight) = imageSize(
            if (rotated) bounds.outHeight else bounds.outWidth,
            if (rotated) bounds.outWidth else bounds.outHeight, width)
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        val originalWidth = if (rotated) bounds.outHeight else bounds.outWidth
        while (originalWidth / (options.inSampleSize * 2) >= targetWidth) options.inSampleSize *= 2
        val image = BitmapFactory.decodeFile(source.path, options) ?: error("Cannot decode this image. Choose JPEG or PNG.")
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        var upright = image
        var result: Bitmap? = null
        try {
            upright = Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
            val resized = Bitmap.createScaledBitmap(upright, targetWidth, targetHeight, true)
            result = resized
            return resized
        } finally {
            if (image !== result) image.recycle()
            if (upright !== image && upright !== result) upright.recycle()
        }
    }

    fun importMedia(input: InputStream, mimeType: String, originalName: String, basename: String = "",
                    width: Int? = null, quality: Int? = null, minimumWidth: Int? = null): JSONObject {
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
                require((width == null || width in 1..16384) && (quality == null || quality in 1..100) &&
                    (mimeType.startsWith("image/") || (width == null && quality == null))) {
                    "Image width must be 1-16384 pixels and JPEG quality 1-100%."
                }
                require(requested.isEmpty() || requested.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) {
                    "Use up to 80 letters, numbers, hyphens, or underscores for the basename, without an extension."
                }
                temporary.outputStream().use(source::copyTo)
            }
            require(temporary.length() > 0) { "The selected media file is empty." }
            if (minimumWidth != null) {
                require(mimeType.startsWith("image/") && (width == null || width >= minimumWidth)) { "Cover width must be at least $minimumWidth pixels." }
                verifyMinimumWidth(temporary, minimumWidth)
            }
            var type = mimeType.lowercase()
            if (type.startsWith("image/") && (width != null || quality != null)) {
                val image = optimizedBitmap(temporary, type, width)
                try {
                    val png = type == "image/png" || image.hasAlpha()
                    type = if (png) "image/png" else "image/jpeg"
                    destination = nextFile(if (png) "png" else "jpg")
                    destination.outputStream().use {
                        require(image.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality ?: 85, it)) {
                            "Cannot save the resized image."
                        }
                    }
                } finally { image.recycle() }
            } else if (type in setOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temporary.path, bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
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
            if (minimumWidth != null) verifyMinimumWidth(file, minimumWidth)
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

    private fun verifyMinimumWidth(file: File, minimum: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val width = if (orientation in 5..8) bounds.outHeight else bounds.outWidth
        require(width >= minimum) { "Cover images must be at least $minimum pixels wide; smaller images are not enlarged." }
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

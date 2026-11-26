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
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

class ContentStore(private val context: Context) :
    SQLiteOpenHelper(context, "webeasy.sqlite", null, 4) {
    val mediaDirectory = File(context.filesDir, "Media")
    private var swapping = false

    override fun getWritableDatabase(): SQLiteDatabase = openDatabase(true)
    override fun getReadableDatabase(): SQLiteDatabase = openDatabase(false)

    private fun openDatabase(writable: Boolean): SQLiteDatabase = synchronized(restoreLock) {
        if (!swapping && File(context.filesDir, "webeasy-restore.json").exists()) {
            super.close()
            recoverInterruptedRestore()
        }
        if (writable) super.getWritableDatabase() else super.getReadableDatabase()
    }

    internal fun <T> restoreOperation(operation: () -> T): T = synchronized(restoreLock) { operation() }

    init {
        synchronized(restoreLock) {
            recoverInterruptedRestore()
            check(mediaDirectory.isDirectory || mediaDirectory.mkdirs())
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
    }

    override fun close() = synchronized(restoreLock) { super.close() }

    private fun recoverInterruptedRestore() {
        val marker = File(context.filesDir, "webeasy-restore.json")
        if (!marker.exists()) return
        val name = JSONObject(marker.readText()).getString("recovery")
        require(name.matches(Regex("restore-recovery-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "Invalid restore recovery record." }
        val recovery = safeFile(context.filesDir, name)
        val database = context.getDatabasePath(databaseName)
        val names = listOf(database.name, "${database.name}-journal", "${database.name}-wal", "${database.name}-shm")
        if (File(recovery, database.name).isFile) {
            names.forEach { val file = File(database.parentFile, it); check(!file.exists() || file.delete()) }
        }
        for (file in names.map { File(recovery, it) }.filter(File::exists)) {
            val target = File(database.parentFile, file.name)
            check(!target.exists() || target.delete())
            check(file.renameTo(target)) { "Cannot recover the original database." }
        }
        if (File(recovery, "media").isDirectory) {
            if (mediaDirectory.exists()) check(mediaDirectory.deleteRecursively())
            check(File(recovery, "media").renameTo(mediaDirectory)) { "Cannot recover original media." }
        }
        check(marker.delete()) { "Cannot finish interrupted restore recovery." }
        if (recovery.exists()) recovery.deleteRecursively()
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

    private fun metadata(key: String): String? =
        rows(readableDatabase, "SELECT value FROM metadata WHERE key = ?", arrayOf(key)).optJSONObject(0)?.getString("value")

    fun templateSources(): JSONObject? {
        val index = metadata("template_source_index") ?: return metadata("template_sources")?.let(::JSONObject)
        return JSONObject().apply {
            val paths = JSONArray(index)
            require(paths.length() <= 1024)
            for (i in 0 until paths.length()) {
                val path = paths.getString(i)
                put(path, metadata("template_source/$path") ?: error("Stored template is missing."))
            }
        }
    }

    fun saveTemplateSources(sources: JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try { writeTemplateSources(sources); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private fun writeTemplateSources(sources: JSONObject) {
        val db = writableDatabase
        db.execSQL("DELETE FROM metadata WHERE key IN ('template_sources', 'template_source_index') OR key LIKE 'template_source/%'")
        val paths = sources.keys().asSequence().toList().sorted()
        require(paths.size <= 1024)
        for (path in paths) {
            require(path.matches(Regex("webeasy_templates/(templates|components)/[a-z][a-z0-9-]*\\.html")))
            val source = sources.getString(path)
            require(source.toByteArray().size <= 256 * 1024) { "Template is too large." }
            insert(db, "metadata", mapOf("key" to "template_source/$path", "value" to source))
        }
        insert(db, "metadata", mapOf("key" to "template_source_index", "value" to JSONArray(paths).toString()))
    }

    fun assetFiles(): Map<String, ByteArray> {
        val index = metadata("asset_overrides")?.let(::JSONObject) ?: return emptyMap()
        require(index.length() <= 1024)
        var total = 0L
        val files = index.keys().asSequence().associateWith { path ->
            val entry = index.getJSONObject(path)
            val size = entry.getInt("size")
            val chunks = entry.getInt("chunks")
            require(CustomizationAssets.validPath(path) && size in 0..CustomizationAssets.maximumFileBytes &&
                chunks == (size + assetChunkSize - 1) / assetChunkSize)
            total += size
            require(total <= CustomizationAssets.maximumTotalBytes)
            java.io.ByteArrayOutputStream(size).use { output ->
                for (chunk in 0 until chunks) {
                    val encoded = metadata("asset_chunk/$path/$chunk") ?: error("Stored website asset is missing.")
                    val bytes = Base64.getDecoder().decode(encoded)
                    require(bytes.size <= assetChunkSize && output.size() + bytes.size <= size)
                    output.write(bytes)
                }
                require(output.size() == size) { "Stored website asset is incomplete." }
                output.toByteArray()
            }
        }
        return files
    }

    fun customizationFiles(): Map<String, ByteArray> {
        val overrides = templateSources()
        val catalog = JSONObject(context.assets.open("website/catalog.json").bufferedReader().use { it.readText() })
        val files = mutableMapOf<String, ByteArray>()
        for (group in listOf("templates", "components")) {
            for (entry in catalog.getJSONArray(group).objects()) {
                val path = "webeasy_templates/$group/${entry.getString("id")}.html"
                files[path] = (if (overrides?.has(path) == true) overrides.getString(path) else entry.getString("source")).toByteArray()
            }
        }
        for (path in CustomizationAssets.bundled) files[path] = context.assets.open("website/$path").use { it.readBytes() }
        files.putAll(assetFiles())
        return files
    }

    fun saveCustomization(sources: JSONObject, assets: Map<String, ByteArray>) {
        CustomizationAssets.validate(assets)
        val db = writableDatabase
        db.beginTransaction()
        try {
            writeTemplateSources(sources)
            db.execSQL("DELETE FROM metadata WHERE key = 'asset_overrides' OR key LIKE 'asset_chunk/%'")
            val index = JSONObject()
            for ((path, bytes) in assets) {
                val chunks = (bytes.size + assetChunkSize - 1) / assetChunkSize
                for (chunk in 0 until chunks) {
                    val data = bytes.copyOfRange(chunk * assetChunkSize, minOf(bytes.size, (chunk + 1) * assetChunkSize))
                    insert(db, "metadata", mapOf("key" to "asset_chunk/$path/$chunk", "value" to Base64.getEncoder().encodeToString(data)))
                }
                index.put(path, JSONObject().put("size", bytes.size).put("chunks", chunks))
            }
            insert(db, "metadata", mapOf("key" to "asset_overrides", "value" to index.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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

    fun backupSnapshot(): File = synchronized(restoreLock) {
        if (File(context.filesDir, "webeasy-restore.json").exists()) {
            close(); recoverInterruptedRestore(); writableDatabase
        }
        val directory = File(context.cacheDir, "backup-snapshot-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            close()
            try { context.getDatabasePath(databaseName).copyTo(File(directory, "webeasy.sqlite")) }
            finally { writableDatabase }
            File(directory, "media").mkdirs()
            for (file in mediaDirectory.walkTopDown()) {
                require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "Media contains an unsupported symbolic link." }
                if (file.isFile) {
                    val path = "media/${file.relativeTo(mediaDirectory).invariantSeparatorsPath}"
                    val target = safeFile(directory, path).also { it.parentFile!!.mkdirs() }
                    file.copyTo(target)
                }
            }
            validateSnapshot(directory)
            directory
        } catch (error: Exception) { directory.deleteRecursively(); throw error }
    }

    private fun validateSnapshot(directory: File) {
        val file = File(directory, "webeasy.sqlite")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            require(db.version == 4) { "Unsupported backup database version." }
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Backup database is damaged." }
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "Backup database has broken references." } }
            val allowed = setOf("metadata", "pages", "news", "sections", "asides", "media", "android_metadata")
            db.rawQuery("SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view', 'trigger')", null).use { cursor ->
                val tables = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    require(cursor.getString(1) == "table" && (name in allowed || name.startsWith("sqlite_"))) { "Unexpected backup database schema." }
                    tables.add(name)
                }
                require(tables.containsAll(allowed - "android_metadata")) { "Incomplete backup database." }
            }
            for (media in rows(db, "SELECT filename, size FROM media").objects()) {
                val target = safeFile(directory, "media/${media.getString("filename")}")
                require(target.isFile && target.length() == media.getLong("size")) { "Backup media is missing or damaged." }
            }
        }
    }

    fun restoreSnapshot(directory: File) = synchronized(restoreLock) {
        validateSnapshot(directory)
        if (File(context.filesDir, "webeasy-restore.json").exists()) {
            close(); recoverInterruptedRestore(); writableDatabase
        }
        val recovery = File(context.filesDir, "restore-recovery-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val marker = File(context.filesDir, "webeasy-restore.json")
        val pending = File(context.filesDir, "webeasy-restore.pending")
        pending.outputStream().use { output ->
            output.write(JSONObject().put("recovery", recovery.name).toString().toByteArray())
            output.fd.sync()
        }
        check(pending.renameTo(marker)) { "Cannot write restore recovery record." }
        val database = context.getDatabasePath(databaseName)
        val names = listOf(database.name, "${database.name}-journal", "${database.name}-wal", "${database.name}-shm")
        var recovered = false
        swapping = true
        try {
            close()
            for (name in names) {
                val original = File(database.parentFile, name)
                if (original.exists()) {
                    val previous = File(recovery, name)
                    check(original.renameTo(previous)) { "Cannot preserve the current database." }
                }
            }
            if (mediaDirectory.exists()) {
                check(mediaDirectory.renameTo(File(recovery, "media"))) { "Cannot preserve current media." }
            }
            check(File(directory, "webeasy.sqlite").renameTo(database)) { "Cannot install the backup database." }
            check(File(directory, "media").renameTo(mediaDirectory)) { "Cannot install backup media." }
            writableDatabase
            project(); templateSources(); assetFiles()
            check(marker.delete()) { "Cannot complete restore safely." }
            recovered = true
        } catch (error: Exception) {
            close()
            recoverInterruptedRestore()
            writableDatabase
            recovered = true
            throw error
        } finally { swapping = false; if (recovered) recovery.deleteRecursively() }
    }

    fun prepareSite(files: JSONArray, references: JSONArray): File {
        val token = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "website-$token").apply { mkdirs() }
        try {
            val assets = assetFiles()
            for (record in files.objects()) {
                val path = record.getString("path")
                require(path.endsWith(".html"))
                safeFile(directory, path).also { it.parentFile!!.mkdirs() }.writeText(record.getString("html"))
            }
            for (path in (listOf("assets/site.css", "assets/site.js", "assets/logo.svg") + List(references.length()) { references.getString(it) } + assets.keys).distinct()) {
                val target = safeFile(directory, path).also { it.parentFile!!.mkdirs() }
                if (path.startsWith("assets/")) {
                    val customized = assets[path]
                    if (customized != null) target.writeBytes(customized)
                    else context.assets.open("website/$path").use { source -> target.outputStream().use(source::copyTo) }
                }
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
        private val restoreLock = Any()
        private const val assetChunkSize = 256 * 1024
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

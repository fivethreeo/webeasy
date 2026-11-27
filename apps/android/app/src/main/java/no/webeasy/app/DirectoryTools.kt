package no.webeasy.app

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

internal class DirectoryTools(private val context: Context) {
    data class Customization(val sources: JSONObject, val assets: Map<String, ByteArray>)
    data class Backup(val directory: DirectoryFile, val createdAt: Instant, val files: JSONObject, val id: String)
    private data class Inventory(val files: Map<String, DirectoryFile>, val directories: Map<String, DirectoryFile>)
    fun exportCustomization(root: DocumentFile, files: Map<String, ByteArray>) = exportCustomization(DeviceDirectoryFile(context, root), files)
    fun loadCustomization(root: DocumentFile, sourcePaths: List<String>) = loadCustomization(DeviceDirectoryFile(context, root), sourcePaths)
    fun backup(root: DocumentFile, snapshot: File, now: Instant = Instant.now()) = backup(DeviceDirectoryFile(context, root), snapshot, now)
    fun backups(root: DocumentFile) = backups(DeviceDirectoryFile(context, root))
    fun cleanup(root: DocumentFile, now: Instant = Instant.now()) = cleanup(DeviceDirectoryFile(context, root), now)

    private val folderFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS'Z'").withZone(ZoneOffset.UTC)
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val manifestName = "webeasy-backup.json"

    private fun validPath(path: String): Boolean = path.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")) &&
        path.split("/").none { it.isEmpty() || it == "." || it == ".." }

    private fun find(root: DirectoryFile, path: String): DirectoryFile? {
        require(validPath(path)) { "Invalid directory path." }
        var current = root
        for (part in path.split("/")) {
            if (!current.isDirectory) return null
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun createFile(root: DirectoryFile, path: String): DirectoryFile {
        require(validPath(path))
        val parts = path.split("/")
        var directory = root
        for (part in parts.dropLast(1)) {
            directory = directory.findFile(part) ?: directory.createDirectory(part) ?: error("Cannot create a directory.")
            require(directory.isDirectory) { "A file blocks the export directory." }
        }
        val filename = parts.last()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val file = directory.findFile(filename) ?: directory.createFile(mime, filename) ?: error("Cannot create a file.")
        if (file.name != filename) require(file.renameTo(filename) && file.name == filename) { "This provider cannot preserve the required filename." }
        require(file.isFile) { "A directory blocks the export file." }
        return file
    }

    private fun read(file: DirectoryFile): InputStream = file.input()

    private fun write(file: DirectoryFile, input: InputStream) {
        input.use { source ->
            val target = file.output()
            target.use { source.copyTo(it) }
        }
    }

    private fun limited(input: InputStream, maximum: Int): ByteArray = input.use { source ->
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= maximum) { "Directory file is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun inventory(root: DirectoryFile, ignoreHidden: Boolean = false): Inventory {
        val files = linkedMapOf<String, DirectoryFile>()
        val directories = linkedMapOf<String, DirectoryFile>()
        val visited = mutableSetOf<String>()
        fun visit(directory: DirectoryFile, prefix: String, depth: Int) {
            require(depth <= 16 && visited.add(directory.uri.toString())) { "Invalid directory structure." }
            for (file in directory.listFiles()) {
                val name = file.name ?: error("Directory entry has no name.")
                if (ignoreHidden && (name.startsWith(".") || name == "Thumbs.db")) continue
                val path = prefix + name
                require(validPath(path)) { "Directory contains an invalid filename." }
                require(files.size + directories.size < 100000) { "Too many directory entries." }
                if (file.isDirectory) { directories[path] = file; visit(file, "$path/", depth + 1) }
                else {
                    require(file.isFile && files.size < 100000 && files.put(path, file) == null) { "Invalid directory contents." }
                }
            }
        }
        visit(root, "", 0)
        return Inventory(files, directories)
    }

    fun exportCustomization(root: DirectoryFile, files: Map<String, ByteArray>) {
        require(root.isDirectory && root.canWrite()) { "Choose a writable directory." }
        for ((path, bytes) in files) write(createFile(root, path), bytes.inputStream())
    }

    fun loadCustomization(root: DirectoryFile, sourcePaths: List<String>): Customization {
        val sources = JSONObject()
        for (path in sourcePaths) {
            val file = find(root, path)?.takeIf { it.isFile } ?: error("Missing template: $path")
            val bytes = limited(read(file), 256 * 1024)
            val decoder = Charsets.UTF_8.newDecoder()
            sources.put(path, decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
        }
        val directory = root.findFile("assets")?.takeIf { it.isDirectory } ?: error("Missing assets directory.")
        val assets = linkedMapOf<String, ByteArray>()
        var total = 0L
        for ((relative, file) in inventory(directory, ignoreHidden = true).files) {
            val path = "assets/$relative"
            require(CustomizationAssets.validPath(path) && assets.size < 1024) { "Editor resources cannot be customized." }
            val bytes = limited(read(file), CustomizationAssets.maximumFileBytes)
            total += bytes.size
            require(total <= CustomizationAssets.maximumTotalBytes) { "Website assets are too large." }
            assets[path] = bytes
        }
        CustomizationAssets.validate(assets)
        return Customization(sources, assets)
    }

    private fun digest(input: InputStream): Pair<Long, String> = input.use { source ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var size = 0L
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count); size += count
        }
        size to hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun backup(root: DirectoryFile, snapshot: File, now: Instant = Instant.now()): Backup {
        require(root.isDirectory && root.canWrite()) { "Choose a writable backup directory." }
        val name = folderFormat.format(now)
        require(root.findFile(name) == null) { "A backup already exists at this time. Try again." }
        val directory = root.createDirectory(name) ?: error("Cannot create backup directory.")
        val created = mutableListOf<DirectoryFile>()
        val paths = mutableSetOf<String>()
        try {
            val entries = JSONObject()
            for (file in snapshot.walkTopDown().filter(File::isFile)) {
                val path = file.relativeTo(snapshot).invariantSeparatorsPath
                require(path == "webeasy.sqlite" || path.startsWith("media/"))
                val (size, hash) = digest(file.inputStream())
                paths.add(path)
                val target = createFile(directory, path)
                created.add(target)
                write(target, file.inputStream())
                require(digest(read(target)) == (size to hash)) { "Backup write was incomplete." }
                entries.put(path, JSONObject().put("size", size).put("sha256", hash))
            }
            require(entries.has("webeasy.sqlite"))
            val manifest = JSONObject().put("format", "webeasy-backup").put("version", 1).put("schemaVersion", 5)
                .put("id", UUID.randomUUID().toString()).put("createdAt", dateFormat.format(now)).put("files", entries)
            val marker = createFile(directory, manifestName); created.add(marker)
            write(marker, manifest.toString().toByteArray().inputStream())
            val result = readBackup(directory)
            runCatching { cleanup(root, now) }
            return result
        } catch (error: Exception) {
            created.asReversed().forEach { it.delete() }
            deleteEmptyDirectories(directory, parentPaths(paths))
            throw error
        }
    }

    private fun readBackup(directory: DirectoryFile): Backup {
        val marker = directory.findFile(manifestName)?.takeIf { it.isFile } ?: error("Not a completed Webeasy backup.")
        val manifest = JSONObject(limited(read(marker), 16 * 1024 * 1024).toString(Charsets.UTF_8))
        require(manifest.optString("format") == "webeasy-backup" && manifest.optInt("version") == 1 &&
            manifest.optInt("schemaVersion") in 4..5 && manifest.optString("id").matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
            "Unsupported backup format."
        }
        val date = Instant.parse(manifest.getString("createdAt"))
        val files = manifest.getJSONObject("files")
        require(files.has("webeasy.sqlite") && files.length() <= 100000)
        for (path in files.keys()) {
            val entry = files.getJSONObject(path)
            require(validPath(path) && (path == "webeasy.sqlite" || path.startsWith("media/")) &&
                entry.getLong("size") >= 0 && entry.getString("sha256").matches(Regex("[0-9a-f]{64}"))) { "Invalid backup inventory." }
        }
        return Backup(directory, date, files, manifest.getString("id"))
    }

    fun backups(root: DirectoryFile): List<Backup> = root.listFiles().filter { it.isDirectory }
        .mapNotNull { runCatching { readBackup(it) }.getOrNull() }.sortedByDescending { it.createdAt }

    fun restoreSnapshot(backup: Backup): File {
        val current = readBackup(backup.directory)
        require(current.id == backup.id && current.createdAt == backup.createdAt && current.files.toString() == backup.files.toString()) { "Backup changed since it was selected. Refresh and select it again." }
        val directory = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            File(directory, "media").mkdirs()
            for (path in current.files.keys()) {
                val expected = current.files.getJSONObject(path)
                val source = find(current.directory, path)?.takeIf { it.isFile } ?: error("Backup file is missing.")
                val target = ContentStore.safeFile(directory, path).also { it.parentFile!!.mkdirs() }
                read(source).use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var count = 0L
                    while (true) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        count += length
                        require(count <= expected.getLong("size")) { "Backup file size does not match." }
                        output.write(buffer, 0, length)
                    }
                } }
                val (size, hash) = digest(target.inputStream())
                require(size == expected.getLong("size") && hash == expected.getString("sha256")) { "Backup file checksum does not match." }
            }
            return directory
        } catch (error: Exception) { directory.deleteRecursively(); throw error }
    }

    fun cleanup(root: DirectoryFile, now: Instant = Instant.now()) {
        val cutoff = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).minus(Duration.ofDays(14))
        for (backup in backups(root)) {
            if (!backup.createdAt.isBefore(cutoff) || backup.directory.name != folderFormat.format(backup.createdAt)) continue
            runCatching {
                val inventory = inventory(backup.directory)
                val files = inventory.files
                val expected = backup.files.keys().asSequence().toSet() + manifestName
                val parents = parentPaths(expected)
                if (files.keys != expected || inventory.directories.keys != parents) return@runCatching
                for (path in backup.files.keys()) {
                    val entry = backup.files.getJSONObject(path)
                    val (size, hash) = digest(read(files.getValue(path)))
                    if (size != entry.getLong("size") || hash != entry.getString("sha256")) return@runCatching
                }
                for (path in backup.files.keys()) check(files.getValue(path).delete())
                check(files.getValue(manifestName).delete())
                deleteEmptyDirectories(backup.directory, parents)
            }
        }
    }

    private fun parentPaths(paths: Set<String>): Set<String> = paths.flatMap { path ->
        val parts = path.split("/")
        (1 until parts.size).map { parts.take(it).joinToString("/") }
    }.toSet()

    private fun deleteEmptyDirectories(root: DirectoryFile, parents: Set<String>) {
        for (path in parents.sortedByDescending { it.count { char -> char == '/' } }) {
            val directory = find(root, path)
            if (directory?.isDirectory == true && directory.listFiles().isEmpty()) directory.delete()
        }
        if (root.listFiles().isEmpty()) root.delete()
    }
}

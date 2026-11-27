package no.webeasy.app

import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

internal class HostingFile(
    private val path: String,
    private val identity: String,
    private val access: Access,
) : DirectoryFile {
    data class Entry(val name: String, val directory: Boolean, val size: Long, val symbolicLink: Boolean = false)
    interface Access {
        fun list(path: String): List<Entry>
        fun mkdir(path: String)
        fun read(path: String): InputStream
        fun write(path: String): OutputStream
        fun remove(path: String, directory: Boolean)
    }

    private fun entry(): Entry? = if (path.isEmpty()) Entry("", true, 0) else
        access.list(path.substringBeforeLast('/', "")).firstOrNull { it.name == name }
    private fun child(name: String): HostingFile {
        require(name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.code < 32 }) {
            "Invalid hosting filename."
        }
        return HostingFile(listOf(path, name).filter(String::isNotEmpty).joinToString("/"), identity, access)
    }
    override fun input(): InputStream {
        require(isFile) { "Cannot read a regular hosting file." }
        return access.read(path)
    }
    override fun output(): OutputStream {
        require(isFile) { "Cannot write a regular hosting file." }
        return access.write(path)
    }
    override val uri: Uri get() = Uri.Builder().scheme("webeasy-hosting").authority(identity).path("/$path").build()
    override val name: String get() = path.substringAfterLast('/').ifEmpty { "Hosting" }
    override val isDirectory: Boolean get() = entry()?.let { it.directory && !it.symbolicLink } == true
    override val isFile: Boolean get() = entry()?.let { !it.directory && !it.symbolicLink } == true
    override fun canWrite(): Boolean = exists()
    override fun exists(): Boolean = entry()?.symbolicLink == false
    override fun listFiles(): List<DirectoryFile> {
        require(isDirectory) { "Cannot list this hosting directory." }
        return access.list(path).filter { it.name != "." && it.name != ".." }.map {
            child(it.name)
        }
    }
    override fun createDirectory(displayName: String): DirectoryFile {
        val file = child(displayName)
        require(file.entry() == null) { "Hosting directory already exists." }
        access.mkdir(file.path)
        require(file.isDirectory) { "Cannot create hosting directory." }
        return file
    }
    override fun createFile(mimeType: String, displayName: String): DirectoryFile {
        val file = child(displayName)
        require(file.entry() == null) { "Hosting file already exists." }
        access.write(file.path).close()
        require(file.isFile) { "Cannot create hosting file." }
        return file
    }
    override fun delete(): Boolean = runCatching {
        require(path.isNotEmpty()) { "Cannot remove the hosting root." }
        val current = entry() ?: return@runCatching true
        require(!current.symbolicLink) { "Symbolic links are not supported." }
        access.remove(path, current.directory)
        true
    }.getOrDefault(false)
    override fun renameTo(displayName: String): Boolean = false
}

package no.webeasy.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

internal interface DirectoryFile {
    val uri: Uri
    val name: String?
    val isDirectory: Boolean
    val isFile: Boolean
    fun exists(): Boolean
    fun canWrite(): Boolean
    fun listFiles(): List<DirectoryFile>
    fun findFile(name: String): DirectoryFile? = listFiles().firstOrNull { it.name == name }
    fun createDirectory(name: String): DirectoryFile?
    fun createFile(mimeType: String, name: String): DirectoryFile?
    fun renameTo(name: String): Boolean
    fun delete(): Boolean
    fun input(): InputStream
    fun output(): OutputStream
}

internal class DeviceDirectoryFile(private val context: Context, private val file: DocumentFile) : DirectoryFile {
    override val uri get() = file.uri
    override val name get() = file.name
    override val isDirectory get() = file.isDirectory
    override val isFile get() = file.isFile
    override fun exists() = file.exists()
    override fun canWrite() = file.canWrite()
    override fun listFiles() = file.listFiles().map { DeviceDirectoryFile(context, it) }
    override fun createDirectory(name: String) = file.createDirectory(name)?.let { DeviceDirectoryFile(context, it) }
    override fun createFile(mimeType: String, name: String) = file.createFile(mimeType, name)?.let { DeviceDirectoryFile(context, it) }
    override fun renameTo(name: String) = file.renameTo(name)
    override fun delete() = file.delete()
    override fun input() = context.contentResolver.openInputStream(uri) ?: error("Cannot read directory file.")
    override fun output() = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write directory file.")
}

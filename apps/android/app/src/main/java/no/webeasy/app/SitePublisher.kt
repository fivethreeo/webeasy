package no.webeasy.app

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class HostKeyApproval(val host: String, val port: Int, val fingerprint: String) : IOException("Confirm the SFTP server key.")

object SitePublisher {
    private const val templateLimit = 256 * 1024

    internal fun privateBackupConnection(connection: JSONObject, directory: String): JSONObject {
        fun normalized(value: String): String {
            require(value.startsWith("/") && value.length <= 512 &&
                value.trim('/').split('/').all { it.matches(Regex("[A-Za-z0-9._-]+")) && it != "." && it != ".." }) {
                "Use an absolute private server directory, without parent segments."
            }
            return value.trimEnd('/')
        }
        val target = normalized(directory.trim())
        val base = normalized(connection.getString("directory"))
        uploadPrefix(connection)
        val publicDirectory = base
        require(publicDirectory.isNotEmpty() && target != publicDirectory && !target.startsWith("$publicDirectory/")) {
            "Backups require a private directory outside the configured website directory."
        }
        return JSONObject(connection.toString()).put("directory", target).put("uploadSubfolder", "")
    }

    internal fun <T> withDirectory(connection: JSONObject, create: Boolean = false, action: (HostingFile) -> T): T {
        val prefix = uploadPrefix(connection)
        val identity = MessageDigest.getInstance("SHA-256").digest(
            listOf("protocol", "host", "port", "username", "directory").joinToString("\n") { connection.get(it).toString() }
                .plus("\n$prefix").toByteArray()
        ).joinToString("") { "%02x".format(it) }
        try {
            return when (connection.getString("protocol")) {
                "ftp", "ftps" -> withFTP(connection) { client ->
                    val access = object : HostingFile.Access {
                        override fun list(path: String): List<HostingFile.Entry> {
                            val files = client.listFiles(prefix + path)
                            check(FTPReply.isPositiveCompletion(client.replyCode))
                            require(files.size <= 100000) { "Too many hosting directory entries." }
                            return files.map {
                                require(it.isDirectory || it.isFile || it.isSymbolicLink) { "Unsupported hosting file type." }
                                HostingFile.Entry(it.name, it.isDirectory, it.size, it.isSymbolicLink)
                            }
                        }
                        override fun mkdir(path: String) { check(client.makeDirectory(prefix + path)) }
                        override fun read(path: String): InputStream {
                            val input = client.retrieveFileStream(prefix + path) ?: error("Cannot download hosting file.")
                            return object : FilterInputStream(input) {
                                override fun close() { try { super.close() } finally { check(client.completePendingCommand()) } }
                            }
                        }
                        override fun write(path: String): OutputStream {
                            val output = client.storeFileStream(prefix + path) ?: error("Cannot upload hosting file.")
                            return object : FilterOutputStream(output) {
                                override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
                                override fun close() { try { super.close() } finally { check(client.completePendingCommand()) } }
                            }
                        }
                        override fun remove(path: String, directory: Boolean) {
                            check(if (directory) client.removeDirectory(prefix + path) else client.deleteFile(prefix + path))
                        }
                    }
                    if (prefix.isNotEmpty()) {
                        var folder = ""
                        for (part in prefix.trimEnd('/').split('/')) {
                            folder += part
                            if (create) client.makeDirectory(folder)
                            check(client.changeWorkingDirectory(folder))
                            check(client.changeWorkingDirectory(connection.getString("directory")))
                            folder += "/"
                        }
                    }
                    action(HostingFile("", identity, access))
                }
                "sftp" -> withSFTP(connection) { sftp ->
                    val base = connection.getString("directory").trimEnd('/') + "/" + prefix.trimEnd('/')
                    val root = base.trimEnd('/').ifEmpty { "/" }
                    if (create) sftp.mkdirs(root)
                    val attributes = sftp.lstat(root)
                    check(attributes.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY)
                    fun remote(path: String) = root.trimEnd('/') + "/" + path
                    val access = object : HostingFile.Access {
                        override fun list(path: String): List<HostingFile.Entry> {
                            val files = sftp.ls(remote(path))
                            require(files.size <= 100000) { "Too many hosting directory entries." }
                            return files.map {
                                val type = it.attributes.type
                                require(type in listOf(net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY,
                                    net.schmizz.sshj.sftp.FileMode.Type.REGULAR, net.schmizz.sshj.sftp.FileMode.Type.SYMLINK)) {
                                    "Unsupported hosting file type."
                                }
                                HostingFile.Entry(it.name, type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY,
                                    it.attributes.size, type == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK)
                            }
                        }
                        override fun mkdir(path: String) { sftp.mkdir(remote(path)) }
                        override fun read(path: String): InputStream {
                            val file = sftp.open(remote(path))
                            return object : FilterInputStream(file.RemoteFileInputStream()) {
                                override fun close() { try { super.close() } finally { file.close() } }
                            }
                        }
                        override fun write(path: String): OutputStream {
                            val file = sftp.open(remote(path), setOf(net.schmizz.sshj.sftp.OpenMode.WRITE,
                                net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.TRUNC))
                            return object : FilterOutputStream(file.RemoteFileOutputStream()) {
                                override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
                                override fun close() { try { super.close() } finally { file.close() } }
                            }
                        }
                        override fun remove(path: String, directory: Boolean) {
                            if (directory) sftp.rmdir(remote(path)) else sftp.rm(remote(path))
                        }
                    }
                    action(HostingFile("", identity, access))
                }
                else -> error("Unsupported publishing protocol.")
            }
        } catch (approval: HostKeyApproval) { throw approval }
        catch (_: Exception) { throw IOException("Hosting directory operation failed. Check the saved connection, private directory, permissions, certificate or server key. Some export files may already have been uploaded.") }
    }

    fun loadTemplates(connection: JSONObject, paths: List<String>): JSONObject {
        require(paths.isNotEmpty() && paths.all {
            it.matches(Regex("webeasy_templates/(templates|components)/[a-z][a-z0-9-]*\\.html"))
        }) { "Invalid template paths." }
        val prefix = uploadPrefix(connection)
        try {
            return when (connection.getString("protocol")) {
                "ftp", "ftps" -> withFTP(connection) { client ->
                    JSONObject().apply {
                        for (path in paths) {
                            val input = client.retrieveFileStream(prefix + path) ?: error("Cannot read template.")
                            val source = input.use(::readTemplate)
                            check(client.completePendingCommand())
                            put(path, source)
                        }
                    }
                }
                "sftp" -> withSFTP(connection) { sftp ->
                    JSONObject().apply {
                        for (path in paths) {
                            val remote = connection.getString("directory").trimEnd('/') + "/" + prefix + path
                            sftp.open(remote).use { file -> file.RemoteFileInputStream().use { put(path, readTemplate(it)) } }
                        }
                    }
                }
                else -> error("Unsupported publishing protocol.")
            }
        } catch (error: HostKeyApproval) { throw error }
        catch (_: Exception) {
            throw IOException("Cannot load templates. Explicitly export templates and assets from Advanced settings and upload webeasy_templates to the configured hosting destination. Check read permissions, certificate or saved server key. Local templates have not changed.")
        }
    }

    private fun readTemplate(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= templateLimit) { "Template is too large." }
            output.write(buffer, 0, count)
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    private fun uploadPrefix(connection: JSONObject): String {
        val subfolder = connection.optString("uploadSubfolder")
        require(subfolder.isEmpty() || subfolder.length <= 512 && subfolder.split("/").all {
            it.matches(Regex("[a-zA-Z0-9._-]+")) && it != "." && it != ".."
        }) { "Use a relative upload subfolder without parent segments." }
        return if (subfolder.isEmpty()) "" else "$subfolder/"
    }

    fun publish(connection: JSONObject, root: File) {
        val uploadPrefix = uploadPrefix(connection)
        val files = root.walkTopDown().filter { it.isFile }.sortedWith(
            compareBy<File> { it.relativeTo(root).invariantSeparatorsPath == "index.html" }
                .thenBy { it.relativeTo(root).invariantSeparatorsPath }
        ).toList()
        require(files.any { it.relativeTo(root).invariantSeparatorsPath == "index.html" }) { "Homepage is missing." }
        try {
            when (connection.getString("protocol")) {
                "ftp", "ftps" -> ftp(connection, root, files, uploadPrefix)
                "sftp" -> sftp(connection, root, files, uploadPrefix)
                else -> error("Unsupported publishing protocol.")
            }
        } catch (error: HostKeyApproval) { throw error }
        catch (_: Exception) { throw IOException("Publishing failed. Check the destination, permissions, certificate or saved server key. Some files may already have been uploaded.") }
    }

    private fun ftp(connection: JSONObject, root: File, files: List<File>, uploadPrefix: String) {
        withFTP(connection) { client ->
            val directories = mutableSetOf("")
            val templateListings = mutableMapOf<String, Set<String>>()
            for (file in files) {
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val path = uploadPrefix + relative
                val parts = path.split("/").dropLast(1)
                for (index in parts.indices) {
                    val directory = parts.take(index + 1).joinToString("/")
                    if (directories.add(directory)) {
                        client.makeDirectory(directory)
                        check(client.changeWorkingDirectory(directory))
                        check(client.changeWorkingDirectory(connection.getString("directory")))
                    }
                }
                if (relative.startsWith("webeasy_templates/")) {
                    val parent = path.substringBeforeLast("/")
                    val existing = templateListings.getOrPut(parent) {
                        val names = client.listFiles(parent).map { it.name }.toSet()
                        check(FTPReply.isPositiveCompletion(client.replyCode))
                        names
                    }
                    if (file.name in existing) continue
                }
                file.inputStream().use { check(client.storeFile(path, it)) }
            }
        }
    }

    private fun <T> withFTP(connection: JSONObject, action: (FTPClient) -> T): T {
        val client = if (connection.getString("protocol") == "ftps") FTPSClient(false).apply {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            setTrustManager(factory.trustManagers.filterIsInstance<X509TrustManager>().first())
            setEndpointCheckingEnabled(true)
        } else FTPClient()
        client.connectTimeout = 30_000
        client.defaultTimeout = 30_000
        client.setDataTimeout(java.time.Duration.ofSeconds(30))
        try {
            client.connect(connection.getString("host"), connection.getInt("port"))
            check(FTPReply.isPositiveCompletion(client.replyCode))
            check(client.login(connection.getString("username"), connection.getString("password")))
            if (client is FTPSClient) { client.execPBSZ(0); client.execPROT("P") }
            client.enterLocalPassiveMode()
            check(client.setFileType(FTP.BINARY_FILE_TYPE))
            check(client.changeWorkingDirectory(connection.getString("directory")))
            val result = action(client)
            check(client.logout())
            return result
        } finally { if (client.isConnected) client.disconnect() }
    }

    private fun sftp(connection: JSONObject, root: File, files: List<File>, uploadPrefix: String) {
        withSFTP(connection) { sftp ->
            val directory = connection.getString("directory").trimEnd('/').ifEmpty { "/" }
            val attributes = sftp.stat(directory)
            check(attributes.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY)
            val directories = mutableSetOf<String>()
            for (file in files) {
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val remote = directory.trimEnd('/') + "/" + uploadPrefix + relative
                val parent = remote.substringBeforeLast("/")
                if (directories.add(parent)) sftp.mkdirs(parent)
                if (relative.startsWith("webeasy_templates/") && sftp.statExistence(remote) != null) continue
                sftp.put(file.absolutePath, remote)
            }
        }
    }

    private fun <T> withSFTP(connection: JSONObject, action: (net.schmizz.sshj.sftp.SFTPClient) -> T): T {
        val host = connection.getString("host")
        val port = connection.getInt("port")
        val pin = connection.optString("_hostKeyFingerprint").takeIf(String::isNotEmpty)
        val observed = AtomicReference<String>()
        SSHClient().use { client ->
            client.connectTimeout = 30_000
            client.timeout = 30_000
            client.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
                    val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
                    observed.set(fingerprint)
                    return fingerprint == pin
                }
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            try { client.connect(host, port) }
            catch (error: Exception) {
                if (pin == null && observed.get() != null) throw HostKeyApproval(host, port, observed.get())
                throw error
            }
            client.authPassword(connection.getString("username"), connection.getString("password"))
            return client.newSFTPClient().use(action)
        }
    }
}

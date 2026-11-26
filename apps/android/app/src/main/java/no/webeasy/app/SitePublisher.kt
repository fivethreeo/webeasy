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
            throw IOException("Cannot load templates. For a server published by an older app, publish once with this app version to add missing template files; existing server edits are preserved. Otherwise check the destination, read permissions, certificate or saved server key. Local templates have not changed.")
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

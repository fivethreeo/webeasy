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
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class HostKeyApproval(val host: String, val port: Int, val fingerprint: String) : IOException("Confirm the SFTP server key.")

object SitePublisher {
    fun publish(connection: JSONObject, root: File) {
        val subfolder = connection.optString("uploadSubfolder")
        require(subfolder.isEmpty() || subfolder.length <= 512 && subfolder.split("/").all {
            it.matches(Regex("[a-zA-Z0-9._-]+")) && it != "." && it != ".."
        }) { "Use a relative upload subfolder without parent segments." }
        val uploadPrefix = if (subfolder.isEmpty()) "" else "$subfolder/"
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
            val directories = mutableSetOf("")
            for (file in files) {
                val path = uploadPrefix + file.relativeTo(root).invariantSeparatorsPath
                val parts = path.split("/").dropLast(1)
                for (index in parts.indices) {
                    val directory = parts.take(index + 1).joinToString("/")
                    if (directories.add(directory)) {
                        client.makeDirectory(directory)
                        check(client.changeWorkingDirectory(directory))
                        check(client.changeWorkingDirectory(connection.getString("directory")))
                    }
                }
                file.inputStream().use { check(client.storeFile(path, it)) }
            }
            check(client.logout())
        } finally { if (client.isConnected) client.disconnect() }
    }

    private fun sftp(connection: JSONObject, root: File, files: List<File>, uploadPrefix: String) {
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
            client.newSFTPClient().use { sftp ->
                val directory = connection.getString("directory").trimEnd('/').ifEmpty { "/" }
                val attributes = sftp.stat(directory)
                check(attributes.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY)
                val directories = mutableSetOf<String>()
                for (file in files) {
                    val relative = uploadPrefix + file.relativeTo(root).invariantSeparatorsPath
                    val remote = directory.trimEnd('/') + "/" + relative
                    val parent = remote.substringBeforeLast("/")
                    if (directories.add(parent)) sftp.mkdirs(parent)
                    sftp.put(file.absolutePath, remote)
                }
            }
        }
    }
}

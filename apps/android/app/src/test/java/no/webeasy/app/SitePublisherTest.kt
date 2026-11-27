package no.webeasy.app

import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SitePublisherTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun connection(protocol: String, port: Int) = JSONObject().put("protocol", protocol).put("host", "localhost")
        .put("port", port).put("username", "publisher").put("password", "test-only").put("directory", "/site")
    private fun site(): File = temporary.newFolder().apply {
        resolve("index.html").writeText("<h1>New home</h1>")
        resolve("assets").mkdir(); resolve("assets/site.css").writeText("body { color: green }")
        resolve("about/team").mkdirs(); resolve("about/team/index.html").writeText("<h1>Team</h1>")
        resolve("webeasy_templates/templates").mkdirs()
        resolve("webeasy_templates/templates/master.html").writeText("<html>{{{navigation}}}{{{content}}}</html>")
        resolve("webeasy_templates/templates/page.html").writeText("<article>{{{content}}}</article>")
    }
    private fun remote(): File = temporary.newFolder().apply {
        resolve("site").mkdir()
        resolve("site/index.html").writeText("Old home")
        resolve("site/unrelated.txt").writeText("Keep me")
        resolve("store").mkdir(); resolve("store/source.json").writeText("Keep the app store")
    }
    private fun checkFiles(root: File, subfolder: String = "") {
        val target = root.resolve("site").resolve(subfolder)
        assertEquals("<h1>New home</h1>", target.resolve("index.html").readText())
        assertEquals("<h1>Team</h1>", target.resolve("about/team/index.html").readText())
        assertEquals("body { color: green }", target.resolve("assets/site.css").readText())
        assertEquals("Keep me", root.resolve("site/unrelated.txt").readText())
        assertEquals("Keep the app store", root.resolve("store/source.json").readText())
    }
    private fun ftp(root: File, tls: File? = null, test: (Int) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val listener = ListenerFactory().apply {
            setPort(port); setServerAddress("127.0.0.1")
            if (tls != null) setSslConfiguration(SslConfigurationFactory().apply {
                setKeystoreFile(tls); setKeystorePassword("test-only")
            }.createSslConfiguration())
        }
        val factory = FtpServerFactory().apply {
            addListener("default", listener.createListener())
            userManager = PropertiesUserManagerFactory().apply {
                file = temporary.newFile()
            }.createUserManager().apply {
                save(BaseUser().apply {
                    name = "publisher"; password = "test-only"; homeDirectory = root.path
                    authorities = listOf(WritePermission())
                })
            }
        }
        val server = factory.createServer()
        server.start()
        try { test(port) } finally { server.stop() }
    }

    @Test
    fun ftpUploadsGeneratedFilesOverwritesExistingHomeAndPreservesUnrelatedFiles() {
        val root = remote()
        ftp(root) { port -> SitePublisher.publish(connection("ftp", port), site()); checkFiles(root) }
    }

    private fun hostingDirectoriesRoundTrip(destination: JSONObject, root: File) {
        val tools = DirectoryTools(ApplicationProvider.getApplicationContext<Context>())
        val files = mapOf(
            "webeasy_templates/templates/page.html" to "<article>{{{content}}}</article>".toByteArray(),
            "assets/site.css" to "body { color: blue }".toByteArray(),
            "assets/site.js" to "console.log('site')".toByteArray(),
            "assets/logo.svg" to "<svg/>".toByteArray(),
        )
        SitePublisher.withDirectory(destination, create = true) { directory ->
            tools.exportCustomization(directory, files)
            val loaded = tools.loadCustomization(directory, listOf("webeasy_templates/templates/page.html"))
            assertEquals("<article>{{{content}}}</article>", loaded.sources.getString("webeasy_templates/templates/page.html"))
            assertArrayEquals(files.getValue("assets/site.css"), loaded.assets.getValue("assets/site.css"))
        }
        root.resolve("private").mkdir()
        val private = SitePublisher.privateBackupConnection(destination, "/private")
        val snapshot = temporary.newFolder().apply {
            resolve("webeasy.sqlite").writeText("saved database")
            resolve("media").mkdir()
            resolve("media/photo.bin").writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val now = Instant.parse("2026-11-27T12:00:00Z")
        val old = SitePublisher.withDirectory(private) { tools.backup(it, snapshot, now.minusSeconds(15 * 86400L)) }
        val modified = SitePublisher.withDirectory(private) { tools.backup(it, snapshot, now.minusSeconds(16 * 86400L)) }
        root.resolve("private/${modified.directory.name}/unrelated.txt").writeText("keep")
        val created = SitePublisher.withDirectory(private) { tools.backup(it, snapshot, now) }
        assertFalse(root.resolve("private/${old.directory.name}").exists())
        assertEquals("keep", root.resolve("private/${modified.directory.name}/unrelated.txt").readText())
        val listed = SitePublisher.withDirectory(private) { tools.backups(it) }
        assertEquals(2, listed.size)
        val selected = listed.first { it.id == created.id }
        SitePublisher.withDirectory(private) { directory ->
            val current = selected.copy(directory = directory.findFile(selected.directory.name!!)!!)
            val restored = tools.restoreSnapshot(current)
            try {
                assertEquals("saved database", restored.resolve("webeasy.sqlite").readText())
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), restored.resolve("media/photo.bin").readBytes())
            } finally { restored.deleteRecursively() }
        }
        assertEquals("Keep me", root.resolve("site/unrelated.txt").readText())
    }

    @Test
    fun ftpCustomizationAndPrivateBackupsRoundTripWithConservativeRetention() {
        val root = remote()
        ftp(root) { port -> hostingDirectoriesRoundTrip(connection("ftp", port), root) }
    }

    @Test
    fun backupsRejectWebsiteDirectoriesAndUnsafePaths() {
        val destination = connection("ftp", 21).put("uploadSubfolder", "published")
        for (path in listOf("/site", "/site/private", "/site/published", "/site/published/backups", "/", "private", "/site/../private")) {
            try { SitePublisher.privateBackupConnection(destination, path); fail("Accepted unsafe backup directory") }
            catch (_: IllegalArgumentException) {}
        }
        assertEquals("/private", SitePublisher.privateBackupConnection(destination, "/private").getString("directory"))
        try {
            SitePublisher.privateBackupConnection(connection("ftp", 21).put("directory", "site"), "/private")
            fail("A relative website directory cannot establish private backup separation")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun ftpUsesRelativeUploadSubfoldersAndOnlyOverwritesFilesInsideThem() {
        val root = remote()
        root.resolve("site/preview/build").mkdirs()
        root.resolve("site/preview/build/index.html").writeText("A much longer old homepage that must be truncated")
        ftp(root) { port ->
            val destination = connection("ftp", port).put("uploadSubfolder", "preview/build")
            SitePublisher.publish(destination, site())
            checkFiles(root, "preview/build")
            SitePublisher.publish(destination, site())
            checkFiles(root, "preview/build")
            assertEquals("Old home", root.resolve("site/index.html").readText())
        }

    }

    @Test
    fun ftpPreservesServerTemplatesLoadsEditsAndRejectsMissingOversizedAndInvalidFiles() {
        val root = remote()
        ftp(root) { port ->
            val destination = connection("ftp", port).put("uploadSubfolder", "preview/build")
            val master = "webeasy_templates/templates/master.html"
            val page = "webeasy_templates/templates/page.html"
            SitePublisher.publish(destination, site())
            val edited = "<html>Server edit{{{content}}}</html>"
            root.resolve("site/preview/build/$master").writeText(edited)
            root.resolve("site/preview/build/$page").delete()
            SitePublisher.publish(destination, site())
            assertEquals(edited, root.resolve("site/preview/build/$master").readText())
            assertEquals("<article>{{{content}}}</article>", root.resolve("site/preview/build/$page").readText())
            val loaded = SitePublisher.loadTemplates(destination, listOf(master, page))
            assertEquals(edited, loaded.getString(master))
            root.resolve("site/preview/build/$page").writeText("x".repeat(256 * 1024 + 1))
            assertThrows(IOException::class.java) { SitePublisher.loadTemplates(destination, listOf(master, page)) }
            root.resolve("site/preview/build/$page").delete()
            val missing = assertThrows(IOException::class.java) { SitePublisher.loadTemplates(destination, listOf(master, page)) }
            assertTrue(missing.message!!.contains("Explicitly export templates and assets"))
            assertTrue(missing.message!!.contains("upload webeasy_templates"))
            assertThrows(IllegalArgumentException::class.java) { SitePublisher.loadTemplates(destination, listOf("../secret.html")) }
            assertEquals("Old home", root.resolve("site/index.html").readText())
        }
    }

    @Test
    fun invalidUploadSubfoldersFailBeforeConnecting() {
        for (path in listOf("/site", "../site", "site/../outside", "site//outside", "site/", "site\\outside", "site/%2e%2e/outside")) {
            assertThrows(IllegalArgumentException::class.java) {
                SitePublisher.publish(connection("ftp", 1).put("uploadSubfolder", path), site())
            }
        }
    }

    @Test
    fun ftpsRejectsUntrustedCertificatesThenPublishesWithAValidatedCertificate() {
        val root = remote()
        val keystore = File(temporary.newFolder(), "server.jks")
        val process = ProcessBuilder("${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-alias", "server",
            "-keystore", keystore.path, "-storetype", "JKS", "-storepass", "test-only", "-keypass", "test-only",
            "-keyalg", "RSA", "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1")
            .redirectErrorStream(true).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
        ftp(root, keystore) { port ->
            assertThrows(IOException::class.java) { SitePublisher.publish(connection("ftps", port), site()) }
            assertEquals("Old home", root.resolve("site/index.html").readText())
            val oldStore = System.getProperty("javax.net.ssl.trustStore")
            val oldPassword = System.getProperty("javax.net.ssl.trustStorePassword")
            try {
                System.setProperty("javax.net.ssl.trustStore", keystore.path)
                System.setProperty("javax.net.ssl.trustStorePassword", "test-only")
                SitePublisher.publish(connection("ftps", port), site())
                checkFiles(root)
                hostingDirectoriesRoundTrip(connection("ftps", port), root)
                val master = "webeasy_templates/templates/master.html"
                root.resolve("site/$master").writeText("FTPS server edit{{{content}}}")
                SitePublisher.publish(connection("ftps", port), site())
                assertEquals("FTPS server edit{{{content}}}", SitePublisher.loadTemplates(connection("ftps", port), listOf(master)).getString(master))
            } finally {
                if (oldStore == null) System.clearProperty("javax.net.ssl.trustStore") else System.setProperty("javax.net.ssl.trustStore", oldStore)
                if (oldPassword == null) System.clearProperty("javax.net.ssl.trustStorePassword") else System.setProperty("javax.net.ssl.trustStorePassword", oldPassword)
            }
        }
    }

    @Test
    fun sftpRequiresConfirmationBeforePasswordAndRejectsChangedKeys() {
        val root = remote()
        val authentications = AtomicInteger()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(temporary.newFolder(), "key").toPath()).apply {
                algorithm = "RSA"; keySize = 2048
            }
            setPasswordAuthenticator { user, password, _ ->
                authentications.incrementAndGet(); user == "publisher" && password == "test-only"
            }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root.toPath())
        }
        server.start()
        try {
            val destination = connection("sftp", server.port)
            val key = assertThrows(HostKeyApproval::class.java) { SitePublisher.publish(destination, site()) }
            assertTrue(key.fingerprint.startsWith("SHA256:"))
            assertEquals(0, authentications.get())
            destination.put("_hostKeyFingerprint", key.fingerprint)
            hostingDirectoriesRoundTrip(destination, root)
            SitePublisher.publish(destination, site())
            checkFiles(root)
            val master = "webeasy_templates/templates/master.html"
            root.resolve("site/$master").writeText("Server SFTP edit{{{content}}}")
            SitePublisher.publish(destination, site())
            assertEquals("Server SFTP edit{{{content}}}", SitePublisher.loadTemplates(destination, listOf(master)).getString(master))
            destination.put("uploadSubfolder", "preview/build")
            SitePublisher.publish(destination, site())
            checkFiles(root, "preview/build")
            root.resolve("site/preview/build/index.html").writeText("A much longer old homepage that must be truncated")
            SitePublisher.publish(destination, site())
            checkFiles(root, "preview/build")
            val authenticated = authentications.get()
            destination.put("_hostKeyFingerprint", "SHA256:wrong-key")
            assertThrows(IOException::class.java) { SitePublisher.publish(destination, site()) }
            assertThrows(IOException::class.java) { SitePublisher.loadTemplates(destination, listOf(master)) }
            assertEquals(authenticated, authentications.get())
        } finally { server.stop(true) }
    }
}

package no.webeasy.app

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DirectoryToolsTest {
    private lateinit var root: File
    private lateinit var tools: DirectoryTools

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "directory-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        tools = DirectoryTools(context)
    }

    @After
    fun tearDown() { root.deleteRecursively() }

    private fun directory(name: String) = DocumentFile.fromFile(File(root, name).apply { mkdirs() })

    private fun snapshot(): File = File(root, "snapshot").apply {
        mkdirs()
        File(this, "webeasy.sqlite").writeText("database fixture")
        File(this, "media").mkdirs()
        File(this, "media/photo.png").writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun customizationRoundTripPreservesUnrelatedFilesAndRejectsIncompleteBundles() {
        val destination = directory("customization")
        val files = mapOf(
            "webeasy_templates/master.html" to "<html>{{title}}</html>".toByteArray(),
            "assets/site.css" to "body { color: red; }".toByteArray(),
            "assets/site.js" to byteArrayOf(),
            "assets/logo.svg" to "<svg/>".toByteArray(),
            "assets/fonts/custom.woff2" to byteArrayOf(5, 6),
        )
        tools.exportCustomization(destination, files)
        File(root, "customization/notes.txt").writeText("Keep this")
        File(root, "customization/assets/.DS_Store").writeText("Finder metadata")
        File(root, "customization/assets/site.css").writeText("body { color: blue; }")
        val loaded = tools.loadCustomization(destination, listOf("webeasy_templates/master.html"))
        assertEquals("<html>{{title}}</html>", loaded.sources.getString("webeasy_templates/master.html"))
        assertEquals("body { color: blue; }", loaded.assets.getValue("assets/site.css").toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(5, 6), loaded.assets.getValue("assets/fonts/custom.woff2"))
        assertFalse(loaded.assets.containsKey("assets/.DS_Store"))
        assertEquals("Keep this", File(root, "customization/notes.txt").readText())
        File(root, "customization/assets/site.js").delete()
        assertThrows(Exception::class.java) {
            tools.loadCustomization(destination, listOf("webeasy_templates/master.html"))
        }
    }

    @Test
    fun completedBackupRestoresExactBytesAndCorruptionOrChangedManifestIsRejected() {
        val backup = tools.backup(directory("backups"), snapshot(), Instant.parse("2026-11-26T10:20:30Z"))
        val staged = tools.restoreSnapshot(backup)
        try {
            assertEquals("database fixture", File(staged, "webeasy.sqlite").readText())
            assertArrayEquals(byteArrayOf(1, 2, 3), File(staged, "media/photo.png").readBytes())
        } finally { staged.deleteRecursively() }
        val folder = File(root, "backups/${backup.directory.name}")
        File(folder, "media/photo.png").writeBytes(byteArrayOf(3, 2, 1))
        assertThrows(Exception::class.java) { tools.restoreSnapshot(backup) }
        val marker = File(folder, "webeasy-backup.json")
        marker.writeText(marker.readText().replace("2026-11-26T10:20:30.000Z", "2026-11-26T10:20:31.000Z"))
        assertThrows(Exception::class.java) { tools.restoreSnapshot(backup) }
    }

    @Test
    fun retentionDeletesOnlyVerifiedOwnedBackupsStrictlyOlderThanFourteenDays() {
        val destination = directory("backups")
        val data = snapshot()
        val now = Instant.parse("2026-11-26T10:20:30Z")
        fun backup(days: Long) = tools.backup(destination, data, now.minus(Duration.ofDays(days)))
        val old = backup(21)
        val boundary = backup(14)
        val extraFile = backup(20)
        val extraDirectory = backup(22)
        val corrupt = backup(23)
        File(root, "backups/${extraFile.directory.name}/notes.txt").writeText("Keep this")
        File(root, "backups/${extraDirectory.directory.name}/empty").mkdirs()
        File(root, "backups/${corrupt.directory.name}/webeasy.sqlite").writeText("changed")
        File(root, "backups/personal").mkdirs()
        File(root, "backups/personal/notes.txt").writeText("Keep this too")
        val incomplete = File(root, "backups/2020-01-01_00-00-00-000Z").apply { mkdirs() }
        tools.backup(destination, data, now)
        assertFalse(old.directory.exists())
        assertTrue(boundary.directory.exists())
        assertTrue(extraFile.directory.exists())
        assertTrue(extraDirectory.directory.exists())
        assertTrue(corrupt.directory.exists())
        assertTrue(incomplete.exists())
        assertEquals("Keep this too", File(root, "backups/personal/notes.txt").readText())
    }

    @Test
    fun unsuccessfulBackupNeverPrunesEarlierBackups() {
        val destination = directory("backups")
        val old = tools.backup(destination, snapshot(), Instant.parse("2026-10-01T00:00:00Z"))
        val invalid = File(root, "invalid").apply { mkdirs(); File(this, "unexpected.txt").writeText("wrong") }
        assertThrows(Exception::class.java) {
            tools.backup(destination, invalid, Instant.parse("2026-11-26T00:00:00Z"))
        }
        assertTrue(old.directory.exists())
        assertEquals(1, tools.backups(destination).size)
    }
}

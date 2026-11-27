import Foundation
import SQLite3

@main
struct StarterSiteTests {
    static func main() throws {
        let files = FileManager.default
        let support = files.temporaryDirectory.appendingPathComponent("webeasy-starter-\(UUID().uuidString)")
        defer { try? files.removeItem(at: support) }
        let bundled = URL(fileURLWithPath: CommandLine.arguments[1])
        var store: ContentStore? = try ContentStore(support: support, bundled: bundled)
        let project = try store!.project()
        let pages = project["pages"] as! [[String: Any]]
        precondition(pages.count == 4)
        precondition((pages[1]["children"] as! [[String: Any]]).count == 1)
        precondition((project["news"] as! [[String: Any]]).count == 3)
        let media = project["media"] as! [[String: Any]]
        precondition(media.count == 4)
        for record in media {
            let name = record["filename"] as! String
            let expected = try Data(contentsOf: bundled.appendingPathComponent("starter/media/\(name)"))
            let actual = try Data(contentsOf: store!.mediaDirectory.appendingPathComponent(name))
            precondition(actual == expected)
        }
        let sources = try store!.templateSources()!
        precondition(String(decoding: sources, as: UTF8.self).contains("Fjord"))
        let published = try store!.publishedSnapshot()
        precondition(published == nil)
        let defaults = try JSONDecoder().decode([String: String].self,
            from: Data(contentsOf: bundled.appendingPathComponent("starter/metadata.json")))
        let expectedCSS = Data(base64Encoded: defaults["asset_chunk/assets/site.css/0"]!)!
        let initialCSS = try exportedCSS(store!)
        precondition(initialCSS == expectedCSS)

        var edited = project
        var editedPages = pages
        editedPages[0]["title"] = "Keep my site"
        editedPages[0]["author"] = "An author"
        editedPages[0]["subtitle"] = "A secondary title"
        edited["pages"] = editedPages
        try store!.save(edited)
        let css = Data("body { color: green; }".utf8)
        try store!.saveCustomization(sources, assets: [
            "assets/site.css": css, "assets/site.js": Data(), "assets/logo.svg": Data("<svg/>".utf8)
        ])
        store = nil
        // Simulate an existing database from before the seed marker was introduced.
        var database: OpaquePointer?
        precondition(sqlite3_open(support.appendingPathComponent("webeasy.sqlite").path, &database) == SQLITE_OK)
        precondition(sqlite3_exec(database, "DELETE FROM metadata WHERE key = 'seeded'", nil, nil, nil) == SQLITE_OK)
        sqlite3_close(database)
        store = try ContentStore(support: support, bundled: bundled)
        let reopened = try store!.project()
        precondition((reopened["pages"] as! [[String: Any]])[0]["title"] as! String == "Keep my site")
        precondition((reopened["pages"] as! [[String: Any]])[0]["author"] as! String == "An author")
        precondition((reopened["pages"] as! [[String: Any]])[0]["subtitle"] as! String == "A secondary title")
        let reopenedSources = try store!.templateSources()
        let expectedSources = try JSONDecoder().decode([String: String].self, from: sources)
        let actualSources = try JSONDecoder().decode([String: String].self, from: reopenedSources!)
        precondition(actualSources == expectedSources)
        let reopenedCSS = try exportedCSS(store!)
        precondition(reopenedCSS == css)
        let legacy = try store!.backupSnapshot()
        defer { try? files.removeItem(at: legacy) }
        precondition(sqlite3_open(legacy.appendingPathComponent("webeasy.sqlite").path, &database) == SQLITE_OK)
        precondition(sqlite3_exec(database,
            "ALTER TABLE pages DROP COLUMN author; ALTER TABLE pages DROP COLUMN subtitle; PRAGMA user_version = 4;",
            nil, nil, nil) == SQLITE_OK)
        sqlite3_close(database)
        try store!.restoreSnapshot(legacy)
        let migrated = try store!.project()
        let migratedPage = (migrated["pages"] as! [[String: Any]])[0]
        precondition(migratedPage["title"] as! String == "Keep my site")
        precondition(migratedPage["author"] as! String == "")
        precondition(migratedPage["subtitle"] as! String == "")
        // Deleting all editable pages/news must remain empty after another launch.
        try store!.save(["version": 1, "pages": [[String: Any]](), "news": [[String: Any]]()])
        store = nil
        store = try ContentStore(support: support, bundled: bundled)
        let empty = try store!.project()
        precondition((empty["pages"] as! [[String: Any]]).isEmpty)
        store = nil
        print("iOS starter media, customization and existing-content preservation passed.")

        let retry = files.temporaryDirectory.appendingPathComponent("webeasy-starter-retry-\(UUID().uuidString)")
        defer { try? files.removeItem(at: retry) }
        let invalidBundle = retry.appendingPathComponent("bundle")
        try files.createDirectory(at: retry, withIntermediateDirectories: true)
        try files.copyItem(at: bundled, to: invalidBundle)
        try Data("{".utf8).write(to: invalidBundle.appendingPathComponent("starter/metadata.json"))
        let retrySupport = retry.appendingPathComponent("support")
        do {
            _ = try ContentStore(support: retrySupport, bundled: invalidBundle)
            preconditionFailure("Corrupt starter metadata should fail initialization.")
        } catch {}
        var recovered: ContentStore? = try ContentStore(support: retrySupport, bundled: bundled)
        let recoveredProject = try recovered!.project()
        precondition((recoveredProject["media"] as! [[String: Any]]).count == 4)
        let recoveredSources = try JSONDecoder().decode([String: String].self, from: recovered!.templateSources()!)
        precondition(recoveredSources == expectedSources)
        recovered = nil
        print("iOS starter SQL rollback and media-copy retry passed.")
    }

    private static func exportedCSS(_ store: ContentStore) throws -> Data {
        let directory = try store.exportSite([["path": "index.html", "html": "<h1>Home</h1>"]], references: [])
        defer { try? FileManager.default.removeItem(at: directory) }
        return try Data(contentsOf: directory.appendingPathComponent("assets/site.css"))
    }
}

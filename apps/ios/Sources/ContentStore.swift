import Foundation
import SQLite3
import UIKit
import UniformTypeIdentifiers
import ImageIO

final class ContentStore: @unchecked Sendable {
    let websiteRoot: URL
    let mediaDirectory: URL
    private let bundleRoot: URL
    private let queue = DispatchQueue(label: "no.webeasy.storage")
    private var database: OpaquePointer?

    convenience init() throws {
        let files = FileManager.default
        let support = try files.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                   appropriateFor: nil, create: true).appendingPathComponent("Webeasy", isDirectory: true)
        guard let bundled = Bundle.main.url(forResource: "Website", withExtension: nil) else {
            throw ContentError.invalid("Website resources are unavailable.")
        }
        try self.init(support: support, bundled: bundled)
    }

    init(support: URL, bundled: URL) throws {
        let files = FileManager.default
        bundleRoot = bundled
        websiteRoot = support.appendingPathComponent("Website", isDirectory: true)
        mediaDirectory = websiteRoot.appendingPathComponent("media", isDirectory: true)
        try Self.recoverInterruptedRestore(support: support, media: mediaDirectory)
        try files.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
        // Only bundled resources are refreshed; the media folder and database are never replaced.
        for resource in try files.contentsOfDirectory(at: bundled, includingPropertiesForKeys: nil) where resource.lastPathComponent != "media" {
            let destination = websiteRoot.appendingPathComponent(resource.lastPathComponent)
            if files.fileExists(atPath: destination.path) { try files.removeItem(at: destination) }
            try files.copyItem(at: resource, to: destination)
        }
        guard sqlite3_open_v2(support.appendingPathComponent("webeasy.sqlite").path, &database,
                             SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            sqlite3_close(database)
            database = nil
            throw ContentError.invalid("Cannot open local SQLite storage.")
        }
        do {
            try execute("PRAGMA foreign_keys = ON")
            let version = try rows("PRAGMA user_version").first?["user_version"] as? Int ?? 0
            guard (0...5).contains(version) else {
                throw ContentError.invalid("A database migration is required. Your content has not been deleted.")
            }
            try transaction {
                if version == 0 {
                    let schema = try String(contentsOf: bundled.appendingPathComponent("storage.sql"), encoding: .utf8)
                    guard sqlite3_exec(database, schema, nil, nil, nil) == SQLITE_OK else { throw databaseError() }
                }
                if version == 1 { try execute("ALTER TABLE pages ADD COLUMN template_id TEXT NOT NULL DEFAULT 'page'") }
                if version > 0 && version < 3 {
                    for table in ["pages", "news"] {
                        try execute("ALTER TABLE \(table) ADD COLUMN cover_media_id TEXT REFERENCES media(id) ON DELETE SET NULL")
                        try execute("ALTER TABLE \(table) ADD COLUMN cover_alt TEXT NOT NULL DEFAULT ''")
                    }
                    try execute("ALTER TABLE sections ADD COLUMN subheading TEXT NOT NULL DEFAULT ''")
                }
                if version > 0 && version < 4 {
                    try execute("ALTER TABLE news ADD COLUMN content TEXT NOT NULL DEFAULT ''")
                    try execute("UPDATE news SET content = short_content")
                }
                if version > 0 && version < 5 {
                    try execute("ALTER TABLE pages ADD COLUMN author TEXT NOT NULL DEFAULT ''")
                    try execute("ALTER TABLE pages ADD COLUMN subtitle TEXT NOT NULL DEFAULT ''")
                }
                if version < 5 { try execute("PRAGMA user_version = 5") }
                if try rows("SELECT value FROM metadata WHERE key = ?", ["seeded"]).isEmpty {
                    // Do not replace an older unmarked site, including its customizations.
                    let occupied = try ["pages", "news", "media", "metadata"].contains {
                        try !rows("SELECT 1 FROM \($0) LIMIT 1").isEmpty
                    }
                    if !occupied { try seedStarter() }
                    try execute("INSERT INTO metadata (key, value) VALUES (?, ?)", ["seeded", "1"])
                }
            }
        } catch {
            sqlite3_close(database)
            database = nil
            throw error
        }
    }

    deinit { sqlite3_close(database) }

    private func seedStarter() throws {
        let root = bundleRoot.appendingPathComponent("starter")
        let data = try Data(contentsOf: root.appendingPathComponent("content.json"))
        guard let seed = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let media = seed["media"] as? [[String: Any]] else {
            throw ContentError.invalid("Invalid bundled starter.")
        }
        // Media must exist before inserting pages/news with cover foreign keys.
        for record in media {
            let filename = try text(record, "filename", required: true)
            let target = try DirectoryTools.safeFile(mediaDirectory, filename)
            let source = try DirectoryTools.safeFile(root.appendingPathComponent("media"), filename)
            let bytes = try Data(contentsOf: source)
            guard record["size"] as? Int == bytes.count else { throw ContentError.invalid("Invalid starter media size.") }
            if FileManager.default.fileExists(atPath: target.path) {
                // Accept matching files left by a rolled-back initialization, never overwrite.
                guard try Data(contentsOf: target) == bytes else {
                    throw ContentError.invalid("Starter media would replace an existing file.")
                }
            } else { try bytes.write(to: target, options: .atomic) }
            try execute("INSERT INTO media (id, filename, mime_type, original_name, size) VALUES (?, ?, ?, ?, ?)",
                        [try identity(record), filename, try text(record, "mimeType", required: true),
                         try text(record, "originalName", required: true), bytes.count])
        }
        try writeContent(seed)
        let defaults = try JSONDecoder().decode([String: String].self,
            from: Data(contentsOf: root.appendingPathComponent("metadata.json")))
        for (key, value) in defaults {
            guard ["template_source_index", "asset_overrides"].contains(key)
                || key.hasPrefix("template_source/") || key.hasPrefix("asset_chunk/") else {
                throw ContentError.invalid("Invalid starter customization.")
            }
            try execute("INSERT INTO metadata (key, value) VALUES (?, ?)", [key, value])
        }
        try CustomizationAssets.validate(assetFiles(), complete: true)
    }

    func publishedSnapshot() throws -> Data? {
        try queue.sync {
            try (rows("SELECT value FROM metadata WHERE key = 'published_project'").first?["value"] as? String)?
                .data(using: .utf8)
        }
    }

    func savePublishedSnapshot(_ data: Data) throws {
        try queue.sync {
            try execute("INSERT OR REPLACE INTO metadata (key, value) VALUES ('published_project', ?)",
                        [String(decoding: data, as: UTF8.self)])
        }
    }

    func templateSources() throws -> Data? {
        try queue.sync {
            guard let sources = try templateSourceFiles() else { return nil }
            return try JSONSerialization.data(withJSONObject: sources)
        }
    }

    func saveTemplateSources(_ data: Data) throws {
        try queue.sync {
            try transaction { try writeTemplateSources(data) }
        }
    }

    private func metadata(_ key: String) throws -> String? {
        try rows("SELECT value FROM metadata WHERE key = ?", [key]).first?["value"] as? String
    }

    private func templateSourceFiles() throws -> [String: String]? {
        guard let index = try metadata("template_source_index") else {
            guard let source = try metadata("template_sources") else { return nil }
            return try JSONDecoder().decode([String: String].self, from: Data(source.utf8))
        }
        let paths = try JSONDecoder().decode([String].self, from: Data(index.utf8))
        guard paths.count <= 1024 else { throw ContentError.invalid("Invalid stored templates.") }
        var sources: [String: String] = [:]
        for path in paths {
            guard let source = try metadata("template_source/\(path)") else { throw ContentError.invalid("Stored template is missing.") }
            sources[path] = source
        }
        return sources
    }

    private func writeTemplateSources(_ data: Data) throws {
        let sources = try JSONDecoder().decode([String: String].self, from: data)
        guard sources.count <= 1024 else { throw ContentError.invalid("Too many templates.") }
        try execute("DELETE FROM metadata WHERE key IN ('template_sources', 'template_source_index') OR key LIKE 'template_source/%'")
        for (path, source) in sources {
            guard path.range(of: "^webeasy_templates/(templates|components)/[a-z][a-z0-9-]*\\.html$", options: .regularExpression) != nil,
                  source.utf8.count <= 256 * 1024 else { throw ContentError.invalid("Invalid or oversized template.") }
            try execute("INSERT INTO metadata (key, value) VALUES (?, ?)", ["template_source/\(path)", source])
        }
        let index = try JSONEncoder().encode(sources.keys.sorted())
        try execute("INSERT INTO metadata (key, value) VALUES ('template_source_index', ?)", [String(decoding: index, as: UTF8.self)])
    }

    private struct StoredAsset: Codable {
        let size: Int
        let chunks: Int
    }
    private static let assetChunkSize = 256 * 1024

    private func assetFiles() throws -> [String: Data] {
        guard let value = try metadata("asset_overrides") else { return [:] }
        let index = try JSONDecoder().decode([String: StoredAsset].self, from: Data(value.utf8))
        guard index.count <= 1024 else { throw ContentError.invalid("Too many stored website assets.") }
        var files: [String: Data] = [:]
        var total = 0
        for (path, entry) in index {
            guard CustomizationAssets.validPath(path), (0...CustomizationAssets.maximumFileBytes).contains(entry.size),
                  entry.chunks == (entry.size + Self.assetChunkSize - 1) / Self.assetChunkSize else {
                throw ContentError.invalid("Invalid stored website asset.")
            }
            total += entry.size
            guard total <= CustomizationAssets.maximumTotalBytes else { throw ContentError.invalid("Stored website assets are too large.") }
            var data = Data()
            for chunk in 0..<entry.chunks {
                guard let encoded = try metadata("asset_chunk/\(path)/\(chunk)"), let bytes = Data(base64Encoded: encoded),
                      bytes.count <= Self.assetChunkSize, data.count + bytes.count <= entry.size else {
                    throw ContentError.invalid("Stored website asset is missing.")
                }
                data.append(bytes)
            }
            guard data.count == entry.size else { throw ContentError.invalid("Stored website asset is incomplete.") }
            files[path] = data
        }
        return files
    }

    func customizationFiles() throws -> [String: Data] {
        try queue.sync {
            let catalog = try Catalog.load()
            let sources = try templateSourceFiles() ?? [:]
            var result: [String: Data] = [:]
            for (group, entries) in [("templates", catalog.templates), ("components", catalog.components)] {
                for entry in entries {
                    let path = "webeasy_templates/\(group)/\(entry.id).html"
                    result[path] = Data((sources[path] ?? entry.source).utf8)
                }
            }
            for path in CustomizationAssets.bundled { result[path] = try Data(contentsOf: bundleRoot.appendingPathComponent(path)) }
            result.merge(try assetFiles(), uniquingKeysWith: { _, new in new })
            return result
        }
    }

    func saveCustomization(_ sources: Data, assets: [String: Data]) throws {
        try CustomizationAssets.validate(assets)
        try queue.sync {
            try transaction {
                try writeTemplateSources(sources)
                try execute("DELETE FROM metadata WHERE key = 'asset_overrides' OR key LIKE 'asset_chunk/%'")
                var index: [String: StoredAsset] = [:]
                for (path, bytes) in assets {
                    let chunks = (bytes.count + Self.assetChunkSize - 1) / Self.assetChunkSize
                    for chunk in 0..<chunks {
                        let data = bytes.subdata(in: (chunk * Self.assetChunkSize)..<min(bytes.count, (chunk + 1) * Self.assetChunkSize))
                        try execute("INSERT INTO metadata (key, value) VALUES (?, ?)", ["asset_chunk/\(path)/\(chunk)", data.base64EncodedString()])
                    }
                    index[path] = StoredAsset(size: bytes.count, chunks: chunks)
                }
                let data = try JSONEncoder().encode(index)
                try execute("INSERT INTO metadata (key, value) VALUES ('asset_overrides', ?)", [String(decoding: data, as: UTF8.self)])
            }
        }
    }

    private func databaseError() -> ContentError {
        .invalid(database.map { String(cString: sqlite3_errmsg($0)) } ?? "SQLite storage is unavailable.")
    }

    private func statement(_ sql: String, _ values: [Any?]) throws -> OpaquePointer {
        var prepared: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &prepared, nil) == SQLITE_OK, let prepared else {
            throw databaseError()
        }
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            let result: Int32
            if value == nil || value is NSNull {
                result = sqlite3_bind_null(prepared, index)
            } else if let number = value as? Int {
                result = sqlite3_bind_int64(prepared, index, Int64(number))
            } else if let number = value as? Int64 {
                result = sqlite3_bind_int64(prepared, index, number)
            } else if let string = value as? String {
                result = sqlite3_bind_text(prepared, index, string, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
            } else {
                sqlite3_finalize(prepared)
                throw ContentError.invalid("Invalid SQLite value.")
            }
            if result != SQLITE_OK { sqlite3_finalize(prepared); throw databaseError() }
        }
        return prepared
    }

    private func execute(_ sql: String, _ values: [Any?] = []) throws {
        let prepared = try statement(sql, values)
        defer { sqlite3_finalize(prepared) }
        guard sqlite3_step(prepared) == SQLITE_DONE else { throw databaseError() }
    }

    private func rows(_ sql: String, _ values: [Any?] = []) throws -> [[String: Any]] {
        let prepared = try statement(sql, values)
        defer { sqlite3_finalize(prepared) }
        var records: [[String: Any]] = []
        var result = sqlite3_step(prepared)
        while result == SQLITE_ROW {
            var record: [String: Any] = [:]
            for index in 0..<sqlite3_column_count(prepared) {
                let name = String(cString: sqlite3_column_name(prepared, index))
                switch sqlite3_column_type(prepared, index) {
                case SQLITE_NULL: record[name] = NSNull()
                case SQLITE_INTEGER: record[name] = Int(sqlite3_column_int64(prepared, index))
                default: record[name] = String(cString: sqlite3_column_text(prepared, index))
                }
            }
            records.append(record)
            result = sqlite3_step(prepared)
        }
        guard result == SQLITE_DONE else { throw databaseError() }
        return records
    }

    private func transaction(_ operation: () throws -> Void) throws {
        try execute("BEGIN IMMEDIATE")
        do { try operation(); try execute("COMMIT") }
        catch { try? execute("ROLLBACK"); throw error }
    }

    private func text(_ record: [String: Any], _ key: String, required: Bool = false) throws -> String {
        guard let value = record[key] as? String,
              !required || !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ContentError.invalid("Missing \(key).")
        }
        return value
    }

    private func identity(_ record: [String: Any]) throws -> String {
        let id = try text(record, "id", required: true)
        guard id.range(of: "^[a-zA-Z0-9_-]+$", options: .regularExpression) != nil else {
            throw ContentError.invalid("Invalid content ID.")
        }
        return id
    }

    private func list(_ record: [String: Any], _ key: String) throws -> [[String: Any]] {
        guard record[key] != nil else { return [] }
        guard let records = record[key] as? [[String: Any]] else { throw ContentError.invalid("Invalid \(key).") }
        return records
    }

    private func writeAsides(_ record: [String: Any], owner: String, id: String) throws {
        for (position, aside) in try list(record, "asides").enumerated() {
            try execute("INSERT INTO asides (id, \(owner)_id, heading, content, position) VALUES (?, ?, ?, ?, ?)",
                        [try identity(aside), id, aside["heading"] as? String ?? "", try text(aside, "content"), position])
        }
    }

    private func writeRelated(_ record: [String: Any], owner: String, id: String) throws {
        try writeAsides(record, owner: owner, id: id)
        for (position, section) in try list(record, "sections").enumerated() {
            let sectionID = try identity(section)
            try execute("INSERT INTO sections (id, \(owner)_id, heading, subheading, content, position) VALUES (?, ?, ?, ?, ?, ?)",
                        [sectionID, id, try text(section, "heading", required: true), section["subheading"] as? String ?? "", try text(section, "content"), position])
            try writeAsides(section, owner: "section", id: sectionID)
        }
    }

    private func writePages(_ pages: [[String: Any]], parent: String?) throws {
        for (position, page) in pages.enumerated() {
            let id = try identity(page)
            let slug = try text(page, "slug", required: true)
            let templateID: String
            if page["templateId"] == nil { templateID = "page" }
            else { templateID = try text(page, "templateId", required: true) }
            guard templateID.range(of: "^[a-z][a-z0-9-]*$", options: .regularExpression) != nil else {
                throw ContentError.invalid("Invalid page layout ID.")
            }
            guard slug.range(of: "^[a-z0-9]+(?:-[a-z0-9]+)*$", options: .regularExpression) != nil else {
                throw ContentError.invalid("Invalid slug.")
            }
            if parent == nil && ["news", "assets", "media", "examples", "components", "editor", "provision", "catalog", "content", "index"].contains(slug) {
                throw ContentError.invalid("Reserved page slug.")
            }
            try execute("INSERT INTO pages (id, parent_id, title, menu_title, template_id, slug, content, date, position, cover_media_id, cover_alt, author, subtitle) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        [id, parent, try text(page, "title", required: true), try text(page, "menuTitle", required: true),
                         templateID, slug, try text(page, "content"), try text(page, "date", required: true), position,
                         (page["coverMediaId"] as? String).flatMap { $0.isEmpty ? nil : $0 }, page["coverAlt"] as? String ?? "",
                         try (page["author"] == nil ? "" : text(page, "author")),
                         try (page["subtitle"] == nil ? "" : text(page, "subtitle"))])
            try writeRelated(page, owner: "page", id: id)
            try writePages(list(page, "children"), parent: id)
        }
    }

    private func writeContent(_ project: [String: Any]) throws {
        guard project["version"] as? Int == 1 else { throw ContentError.invalid("Unsupported project version.") }
        try execute("DELETE FROM pages")
        try execute("DELETE FROM news")
        try writePages(list(project, "pages"), parent: nil)
        try execute("DELETE FROM metadata WHERE key = ?", ["home_page_id"])
        if let home = project["homePageId"] as? String {
            guard try !rows("SELECT id FROM pages WHERE id = ?", [home]).isEmpty else {
                throw ContentError.invalid("Invalid homepage.")
            }
            try execute("INSERT INTO metadata (key, value) VALUES (?, ?)", ["home_page_id", home])
        }
        for news in try list(project, "news") {
            let id = try identity(news)
            try execute("INSERT INTO news (id, title, slug, date, short_content, content, cover_media_id, cover_alt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        [id, try text(news, "title", required: true), try text(news, "slug", required: true),
                         try text(news, "date", required: true), try text(news, "shortContent"),
                         try text(news, news["content"] == nil ? "shortContent" : "content"),
                         (news["coverMediaId"] as? String).flatMap { $0.isEmpty ? nil : $0 }, news["coverAlt"] as? String ?? ""])
            try writeRelated(news, owner: "news", id: id)
        }
    }

    func save(_ project: [String: Any]) throws {
        try queue.sync { try transaction { try writeContent(project) } }
    }

    private func asides(owner: String, id: String) throws -> [[String: Any]] {
        try rows("SELECT id, heading, content FROM asides WHERE \(owner)_id = ? ORDER BY position", [id])
    }

    private func related(_ original: [String: Any], owner: String) throws -> [String: Any] {
        var record = original
        let id = try text(record, "id", required: true)
        record["asides"] = try asides(owner: owner, id: id)
        record["sections"] = try rows("SELECT id, heading, subheading, content FROM sections WHERE \(owner)_id = ? ORDER BY position", [id]).map { original in
            var section = original
            section["asides"] = try asides(owner: "section", id: text(section, "id", required: true))
            return section
        }
        return record
    }

    private func pages(parent: String?) throws -> [[String: Any]] {
        let version = try rows("PRAGMA user_version").first?["user_version"] as? Int ?? 0
        let details = version >= 5 ? "author, subtitle" : "'' AS author, '' AS subtitle"
        let sql = "SELECT id, title, \(details), menu_title AS menuTitle, template_id AS templateId, COALESCE(cover_media_id, '') AS coverMediaId, cover_alt AS coverAlt, slug, content, date FROM pages WHERE \(parent == nil ? "parent_id IS NULL" : "parent_id = ?") ORDER BY position"
        return try rows(sql, parent.map { [$0] } ?? []).map { original in
            var page = try related(original, owner: "page")
            page["children"] = try pages(parent: text(page, "id", required: true))
            return page
        }
    }

    func project() throws -> [String: Any] {
        try queue.sync { try readProject() }
    }

    private func readProject() throws -> [String: Any] {
        let records = try pages(parent: nil)
        let home = try rows("SELECT value FROM metadata WHERE key = ?", ["home_page_id"]).first?["value"] as? String
            ?? records.first?["id"] as? String
        return ["version": 1, "homePageId": home.map { $0 as Any } ?? NSNull(), "pages": records,
         "news": try rows("SELECT id, title, slug, date, content, COALESCE(cover_media_id, '') AS coverMediaId, cover_alt AS coverAlt, short_content AS shortContent FROM news ORDER BY date DESC, slug").map { try related($0, owner: "news") },
         "media": try rows("SELECT id, filename, mime_type AS mimeType, original_name AS originalName, size FROM media ORDER BY id")]
    }

    private struct RestoreRecord: Codable {
        let recovery: String
    }

    private static func databaseFiles(_ support: URL) -> [URL] {
        ["webeasy.sqlite", "webeasy.sqlite-journal", "webeasy.sqlite-wal", "webeasy.sqlite-shm"]
            .map { support.appendingPathComponent($0) }
    }

    private static func recoverInterruptedRestore(support: URL, media: URL) throws {
        let files = FileManager.default
        let marker = support.appendingPathComponent("webeasy-restore.json")
        guard files.fileExists(atPath: marker.path) else { return }
        let record = try JSONDecoder().decode(RestoreRecord.self, from: Data(contentsOf: marker))
        guard record.recovery.range(of: "^restore-recovery-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                                    options: .regularExpression) != nil else {
            throw ContentError.invalid("Invalid restore recovery record.")
        }
        let recovery = support.appendingPathComponent(record.recovery, isDirectory: true)
        guard files.fileExists(atPath: recovery.appendingPathComponent("webeasy.sqlite").path),
              files.fileExists(atPath: recovery.appendingPathComponent("media").path) else {
            throw ContentError.invalid("Original data remains in recovery storage; automatic recovery cannot finish.")
        }
        for file in databaseFiles(support) where files.fileExists(atPath: file.path) { try files.removeItem(at: file) }
        try files.copyItem(at: recovery.appendingPathComponent("webeasy.sqlite"), to: support.appendingPathComponent("webeasy.sqlite"))
        if files.fileExists(atPath: media.path) { try files.removeItem(at: media) }
        try files.copyItem(at: recovery.appendingPathComponent("media"), to: media)
        try files.removeItem(at: marker)
        try? files.removeItem(at: recovery)
    }

    private func validateSnapshot(_ directory: URL) throws {
        var snapshot: OpaquePointer?
        guard sqlite3_open_v2(directory.appendingPathComponent("webeasy.sqlite").path, &snapshot,
                             SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            sqlite3_close(snapshot)
            throw ContentError.invalid("Cannot read backup database.")
        }
        let previous = database
        database = snapshot
        defer { database = previous; sqlite3_close(snapshot) }
        let version = try rows("PRAGMA user_version").first?["user_version"] as? Int ?? 0
        guard (4...5).contains(version),
              try rows("PRAGMA quick_check").first?.values.first as? String == "ok",
              try rows("PRAGMA foreign_key_check").isEmpty else {
            throw ContentError.invalid("Backup database is damaged or unsupported.")
        }
        let required: Set<String> = ["metadata", "pages", "news", "sections", "asides", "media"]
        var tables: Set<String> = []
        for row in try rows("SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view', 'trigger')") {
            guard let name = row["name"] as? String, row["type"] as? String == "table",
                  required.contains(name) || name == "android_metadata" || name.hasPrefix("sqlite_") else {
                throw ContentError.invalid("Unexpected backup database schema.")
            }
            tables.insert(name)
        }
        guard required.isSubset(of: tables) else { throw ContentError.invalid("Incomplete backup database.") }
        for row in try rows("SELECT filename, size FROM media") {
            guard let name = row["filename"] as? String, let size = row["size"] as? Int else {
                throw ContentError.invalid("Invalid backup media record.")
            }
            let file = try DirectoryTools.safeFile(directory, "media/" + name)
            let values = try file.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .isSymbolicLinkKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true, values.fileSize == size else {
                throw ContentError.invalid("Backup media is missing or damaged.")
            }
        }
        _ = try readProject()
        _ = try templateSourceFiles()
        _ = try assetFiles()
    }

    private func snapshotUnlocked() throws -> URL {
        let files = FileManager.default
        let directory = files.temporaryDirectory.appendingPathComponent("webeasy-snapshot-\(UUID())", isDirectory: true)
        try files.createDirectory(at: directory, withIntermediateDirectories: false)
        do {
            var target: OpaquePointer?
            guard sqlite3_open_v2(directory.appendingPathComponent("webeasy.sqlite").path, &target,
                                 SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
                sqlite3_close(target)
                throw ContentError.invalid("Cannot create database snapshot.")
            }
            defer { sqlite3_close(target) }
            guard let backup = sqlite3_backup_init(target, "main", database, "main") else {
                throw ContentError.invalid("Cannot snapshot the current database.")
            }
            let copied = sqlite3_backup_step(backup, -1)
            let finished = sqlite3_backup_finish(backup)
            guard copied == SQLITE_DONE, finished == SQLITE_OK,
                  sqlite3_exec(target, "PRAGMA journal_mode = DELETE", nil, nil, nil) == SQLITE_OK else {
                throw ContentError.invalid("Database snapshot was not completed. Try again.")
            }
            if let enumerator = files.enumerator(at: mediaDirectory, includingPropertiesForKeys: [.isSymbolicLinkKey]) {
                for case let file as URL in enumerator {
                    guard try file.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else {
                        throw ContentError.invalid("Media contains an unsupported symbolic link.")
                    }
                }
            }
            try files.copyItem(at: mediaDirectory, to: directory.appendingPathComponent("media", isDirectory: true))
            try validateSnapshot(directory)
            return directory
        } catch { try? files.removeItem(at: directory); throw error }
    }

    func backupSnapshot() throws -> URL { try queue.sync { try snapshotUnlocked() } }

    private func openRestoredDatabase(_ support: URL) throws {
        guard sqlite3_open_v2(support.appendingPathComponent("webeasy.sqlite").path, &database,
                             SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            sqlite3_close(database); database = nil
            throw ContentError.invalid("Cannot open the restored database.")
        }
        try execute("PRAGMA foreign_keys = ON")
        if try rows("PRAGMA user_version").first?["user_version"] as? Int == 4 {
            try transaction {
                try execute("ALTER TABLE pages ADD COLUMN author TEXT NOT NULL DEFAULT ''")
                try execute("ALTER TABLE pages ADD COLUMN subtitle TEXT NOT NULL DEFAULT ''")
                try execute("PRAGMA user_version = 5")
            }
        }
    }

    func restoreSnapshot(_ directory: URL) throws {
        try queue.sync {
            try validateSnapshot(directory)
            let files = FileManager.default
            let support = websiteRoot.deletingLastPathComponent()
            let marker = support.appendingPathComponent("webeasy-restore.json")
            if files.fileExists(atPath: marker.path) {
                sqlite3_close(database); database = nil
                try Self.recoverInterruptedRestore(support: support, media: mediaDirectory)
                try openRestoredDatabase(support)
            }
            let snapshot = try snapshotUnlocked()
            let recovery = support.appendingPathComponent("restore-recovery-\(UUID().uuidString.lowercased())", isDirectory: true)
            do {
                try files.moveItem(at: snapshot, to: recovery)
                try JSONEncoder().encode(RestoreRecord(recovery: recovery.lastPathComponent)).write(to: marker, options: .atomic)
                let record = try FileHandle(forWritingTo: marker)
                try record.synchronize(); try record.close()
            } catch {
                try? files.removeItem(at: snapshot)
                if !files.fileExists(atPath: marker.path) { try? files.removeItem(at: recovery) }
                throw error
            }
            sqlite3_close(database); database = nil
            do {
                for file in Self.databaseFiles(support) where files.fileExists(atPath: file.path) { try files.removeItem(at: file) }
                if files.fileExists(atPath: mediaDirectory.path) { try files.removeItem(at: mediaDirectory) }
                try files.copyItem(at: directory.appendingPathComponent("webeasy.sqlite"), to: support.appendingPathComponent("webeasy.sqlite"))
                try files.copyItem(at: directory.appendingPathComponent("media"), to: mediaDirectory)
                try openRestoredDatabase(support)
                _ = try readProject(); _ = try templateSourceFiles(); _ = try assetFiles()
                try files.removeItem(at: marker)
                try? files.removeItem(at: recovery)
            } catch {
                sqlite3_close(database); database = nil
                try Self.recoverInterruptedRestore(support: support, media: mediaDirectory)
                try openRestoredDatabase(support)
                throw error
            }
        }
    }

    static func safeFile(_ root: URL, _ path: String) throws -> URL {
        try WebsiteFiles.file(root, path)
    }

    private func optimizedImage(_ source: URL, width: Int?, quality: Int?) throws -> (data: Data, mime: String) {
        guard let imageSource = CGImageSourceCreateWithURL(source as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let properties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [CFString: Any],
              let rawWidth = properties[kCGImagePropertyPixelWidth] as? Int,
              let rawHeight = properties[kCGImagePropertyPixelHeight] as? Int,
              rawWidth > 0, rawHeight > 0 else {
            throw ContentError.invalid("Cannot read this image. Choose JPEG or PNG.")
        }
        guard CGImageSourceGetCount(imageSource) == 1 else {
            throw ContentError.invalid("Animated images must be imported with width and quality left blank.")
        }
        let orientation = properties[kCGImagePropertyOrientation] as? Int ?? 1
        let rotated = (5...8).contains(orientation)
        let originalWidth = rotated ? rawHeight : rawWidth
        let originalHeight = rotated ? rawWidth : rawHeight
        let targetWidth = min(width ?? originalWidth, originalWidth)
        let targetHeight = max(1, Int((Double(originalHeight) * Double(targetWidth) / Double(originalWidth)).rounded()))
        guard Double(targetWidth) * Double(targetHeight) <= 32_000_000 else {
            throw ContentError.invalid("Image is too large to optimize. Choose a smaller maximum width.")
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(targetWidth, targetHeight),
        ]
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, options as CFDictionary) else {
            throw ContentError.invalid("Cannot resize this image. Choose JPEG or PNG.")
        }
        let alpha = [CGImageAlphaInfo.first, .last, .premultipliedFirst, .premultipliedLast, .alphaOnly]
            .contains(thumbnail.alphaInfo)
        let png = CGImageSourceGetType(imageSource) as String? == UTType.png.identifier || alpha
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = !png
        let size = CGSize(width: CGFloat(targetWidth), height: CGFloat(targetHeight))
        let image = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            UIImage(cgImage: thumbnail).draw(in: CGRect(origin: .zero, size: size))
        }
        guard let data = png ? image.pngData() : image.jpegData(compressionQuality: CGFloat(quality ?? 85) / 100) else {
            throw ContentError.invalid("Cannot save the resized image.")
        }
        return (data, png ? "image/png" : "image/jpeg")
    }

    func importMedia(_ source: URL, kind: String, typeIdentifier: String, basename: String = "",
                     width: Int? = nil, quality: Int? = nil, minimumWidth: Int? = nil) throws -> [String: Any] {
        try queue.sync {
            guard width == nil || (1...16384).contains(width!),
                  quality == nil || (1...100).contains(quality!),
                  kind == "image" || (width == nil && quality == nil) else {
                throw ContentError.invalid("Image width must be 1-16384 pixels and JPEG quality 1-100%.")
            }
            let id = UUID().uuidString
            if let minimumWidth {
                guard kind == "image", width == nil || width! >= minimumWidth else {
                    throw ContentError.invalid("Cover width must be at least \(minimumWidth) pixels.")
                }
                try verifyMinimumWidth(source, minimum: minimumWidth)
            }
            let requested = basename.trimmingCharacters(in: .whitespacesAndNewlines)
            guard requested.isEmpty || requested.range(of: "^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$", options: .regularExpression)
                == (requested.startIndex..<requested.endIndex) else {
                throw ContentError.invalid("Use up to 80 letters, numbers, hyphens, or underscores for the basename, without an extension.")
            }
            let stem = requested.isEmpty ? id : requested
            func nextFile(_ ext: String) throws -> URL {
                var number = 1
                while true {
                    let filename = "\(stem)\(number == 1 ? "" : "-\(number)").\(ext)"
                    let file = try Self.safeFile(mediaDirectory, filename)
                    if !FileManager.default.fileExists(atPath: file.path),
                       try rows("SELECT id FROM media WHERE filename = ? COLLATE NOCASE", [filename]).isEmpty { return file }
                    number += 1
                }
            }
            let type = UTType(filenameExtension: source.pathExtension) ?? UTType(typeIdentifier)
            var mime = type?.preferredMIMEType ?? ""
            let formats = ["image/jpeg": "jpg", "image/png": "png", "image/gif": "gif", "image/webp": "webp",
                           "video/mp4": "mp4", "video/webm": "webm", "video/quicktime": "mov", "video/ogg": "ogg"]
            var file: URL
            if kind == "image", width != nil || quality != nil {
                let optimized = try optimizedImage(source, width: width, quality: quality)
                mime = optimized.mime
                file = try nextFile(mime == "image/png" ? "png" : "jpg")
                try optimized.data.write(to: file, options: .atomic)
            } else if kind == "image", formats[mime] == nil {
                guard let image = UIImage(contentsOfFile: source.path), let data = image.jpegData(compressionQuality: 0.9) else {
                    throw ContentError.invalid("Cannot read this image. Choose JPEG or PNG.")
                }
                mime = "image/jpeg"
                file = try nextFile("jpg")
                try data.write(to: file, options: .atomic)
            } else {
                guard mime.hasPrefix("\(kind)/"), let ext = formats[mime] else {
                    throw ContentError.invalid("Unsupported media format. MP4 is recommended for video.")
                }
                file = try nextFile(ext)
                try FileManager.default.copyItem(at: source, to: file)
            }
            do {
                if let minimumWidth { try verifyMinimumWidth(file, minimum: minimumWidth) }
                let size = (try file.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
                guard size > 0 else { throw ContentError.invalid("The selected media file is empty.") }
                try execute("INSERT INTO media (id, filename, mime_type, original_name, size) VALUES (?, ?, ?, ?, ?)",
                            [id, file.lastPathComponent, mime, source.lastPathComponent, size])
                return ["id": id, "filename": file.lastPathComponent, "mimeType": mime,
                        "originalName": source.lastPathComponent, "size": size]
            } catch { try? FileManager.default.removeItem(at: file); throw error }
        }
    }

    private func verifyMinimumWidth(_ file: URL, minimum: Int) throws {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else {
            throw ContentError.invalid("Cannot read cover image dimensions.")
        }
        let orientation = properties[kCGImagePropertyOrientation] as? Int ?? 1
        let key = (5...8).contains(orientation) ? kCGImagePropertyPixelHeight : kCGImagePropertyPixelWidth
        guard let width = properties[key] as? Int, width >= minimum else {
            throw ContentError.invalid("Cover images must be at least \(minimum) pixels wide; smaller images are not enlarged.")
        }
    }

    func exportSite(_ documents: [[String: Any]], references: [String]) throws -> URL {
        try queue.sync {
            let files = FileManager.default
            let directory = files.temporaryDirectory.appendingPathComponent("Webeasy-\(UUID().uuidString)", isDirectory: true)
            try files.createDirectory(at: directory, withIntermediateDirectories: true)
            func copy(_ source: URL, _ path: String) throws {
                let target = try Self.safeFile(directory, path)
                try files.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                if files.fileExists(atPath: target.path) { try files.removeItem(at: target) }
                try files.copyItem(at: source, to: target)
            }
            do {
                let assets = try assetFiles()
                for document in documents {
                    let path = try text(document, "path", required: true)
                    guard path.hasSuffix(".html") else { throw ContentError.invalid("Invalid exported document.") }
                    let target = try Self.safeFile(directory, path)
                    try files.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try text(document, "html").write(to: target, atomically: true, encoding: .utf8)
                }
                for path in Set(["assets/site.css", "assets/site.js", "assets/logo.svg"] + references + Array(assets.keys)) {
                    guard path.hasPrefix("assets/") || path.hasPrefix("media/") else { throw ContentError.invalid("Invalid media reference.") }
                    if path.hasPrefix("media/"),
                       try rows("SELECT id FROM media WHERE filename = ?", [String(path.dropFirst(6))]).isEmpty {
                        throw ContentError.invalid("Referenced media is missing.")
                    }
                    if let data = assets[path] {
                        let target = try Self.safeFile(directory, path)
                        try files.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                        try data.write(to: target, options: .atomic)
                    } else { try copy(Self.safeFile(websiteRoot, path), path) }
                }
                return directory
            } catch { try? files.removeItem(at: directory); throw error }
        }
    }
}

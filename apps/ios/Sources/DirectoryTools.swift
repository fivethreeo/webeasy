import Foundation
import CryptoKit

enum DirectoryTools {
    struct Entry: Codable, Equatable, Sendable {
        let size: Int64
        let sha256: String
    }
    struct Manifest: Codable, Equatable, Sendable {
        let format: String
        let version: Int
        let schemaVersion: Int
        let id: String
        let createdAt: String
        let files: [String: Entry]
    }
    struct Backup: Identifiable, Sendable {
        let directory: URL
        let manifest: Manifest
        var id: String { manifest.id }
    }
    struct Customization: Sendable {
        let sources: Data
        let assets: [String: Data]
    }
    private struct Inventory {
        var files: [String: URL] = [:]
        var directories: Set<String> = []
    }
    static let markerName = "webeasy-backup.json"

    static func folderName(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd_HH-mm-ss-SSS'Z'"
        return formatter.string(from: date)
    }

    private static func timestamp(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    static func date(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: value)
    }

    static func access<T>(_ root: URL, writing: Bool, operation: @escaping (URL) throws -> T) throws -> T {
        let scoped = root.startAccessingSecurityScopedResource()
        defer { if scoped { root.stopAccessingSecurityScopedResource() } }
        let coordinator = NSFileCoordinator(filePresenter: nil)
        var error: NSError?
        var result: Result<T, Error>?
        if writing {
            coordinator.coordinate(writingItemAt: root, options: .forMerging, error: &error) { coordinated in
                result = Result { try operation(coordinated) }
            }
        } else {
            coordinator.coordinate(readingItemAt: root, options: [], error: &error) { coordinated in
                result = Result { try operation(coordinated) }
            }
        }
        if let error { throw error }
        guard let result else { throw ContentError.invalid("Cannot access the selected directory.") }
        return try result.get()
    }

    private static func inventory(_ root: URL, ignoreHidden: Bool = false) throws -> Inventory {
        var result = Inventory()
        func visit(_ directory: URL, prefix: String, depth: Int) throws {
            guard depth <= 16 else { throw ContentError.invalid("Directory nesting is too deep.") }
            for file in try FileManager.default.contentsOfDirectory(at: directory,
                includingPropertiesForKeys: [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey]) {
                let name = file.lastPathComponent
                if ignoreHidden && (name.hasPrefix(".") || name == "Thumbs.db") { continue }
                let path = prefix + name
                _ = try safeFile(root, path)
                let values = try file.resourceValues(forKeys: [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey])
                guard values.isSymbolicLink != true else { throw ContentError.invalid("Symbolic links are not supported.") }
                guard result.files.count + result.directories.count < 100_000 else { throw ContentError.invalid("Too many directory entries.") }
                if values.isDirectory == true {
                    result.directories.insert(path)
                    try visit(file, prefix: path + "/", depth: depth + 1)
                } else {
                    guard values.isRegularFile == true, result.files.count < 100_000 else {
                        throw ContentError.invalid("Invalid directory contents.")
                    }
                    result.files[path] = file
                }
            }
        }
        try visit(root, prefix: "", depth: 0)
        return result
    }

    static func validatePath(_ path: String) throws {
        guard path.range(of: "^[A-Za-z0-9][A-Za-z0-9._/-]*$", options: .regularExpression) != nil,
              !path.split(separator: "/", omittingEmptySubsequences: false).contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }) else {
            throw ContentError.invalid("Invalid directory path.")
        }
    }

    static func safeFile(_ root: URL, _ path: String) throws -> URL {
        try validatePath(path)
        let target = root.appendingPathComponent(path)
        var resolved = root.resolvingSymlinksInPath().standardizedFileURL
        let rootComponents = resolved.pathComponents
        // Resolve each ancestor: resolving a whole nonexistent destination can
        // leave an existing parent symlink unresolved on Apple Foundation.
        for component in path.split(separator: "/") {
            resolved = resolved.appendingPathComponent(String(component)).resolvingSymlinksInPath().standardizedFileURL
            let components = resolved.pathComponents
            guard components.count > rootComponents.count,
                  components.starts(with: rootComponents) else {
                throw ContentError.invalid("Directory path escapes the selected folder.")
            }
        }
        return target
    }

    private static func limited(_ file: URL, maximum: Int) throws -> Data {
        let input = try FileHandle(forReadingFrom: file)
        defer { try? input.close() }
        var result = Data()
        while let bytes = try input.read(upToCount: min(65_536, maximum - result.count + 1)), !bytes.isEmpty {
            guard result.count + bytes.count <= maximum else { throw ContentError.invalid("Directory file is too large.") }
            result.append(bytes)
        }
        return result
    }

    static func exportCustomization(_ root: URL, files: [String: Data]) throws {
        try access(root, writing: true) { directory in
            for (path, data) in files {
                let target = try safeFile(directory, path)
                try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                try data.write(to: target, options: .atomic)
            }
        }
    }

    static func loadCustomization(_ root: URL, sourcePaths: [String]) throws -> Customization {
        try access(root, writing: false) { directory in
            var sources: [String: String] = [:]
            for path in sourcePaths {
                let bytes = try limited(safeFile(directory, path), maximum: 256 * 1024)
                guard let source = String(data: bytes, encoding: .utf8) else { throw ContentError.invalid("Templates must be UTF-8.") }
                sources[path] = source
            }
            let assetRoot = try safeFile(directory, "assets")
            var assets: [String: Data] = [:]
            var total = 0
            for (relative, file) in try inventory(assetRoot, ignoreHidden: true).files {
                let path = "assets/" + relative
                guard CustomizationAssets.validPath(path), assets.count < 1024 else {
                    throw ContentError.invalid("Editor resources cannot be customized.")
                }
                let bytes = try limited(file, maximum: CustomizationAssets.maximumFileBytes)
                total += bytes.count
                guard total <= CustomizationAssets.maximumTotalBytes else { throw ContentError.invalid("Website assets are too large.") }
                assets[path] = bytes
            }
            try CustomizationAssets.validate(assets)
            return Customization(sources: try JSONEncoder().encode(sources), assets: assets)
        }
    }

    static func fingerprint(_ file: URL) throws -> Entry {
        let input = try FileHandle(forReadingFrom: file)
        defer { try? input.close() }
        var digest = SHA256()
        var size: Int64 = 0
        while let bytes = try input.read(upToCount: 65_536), !bytes.isEmpty {
            size += Int64(bytes.count)
            digest.update(data: bytes)
        }
        return Entry(size: size, sha256: digest.finalize().map { String(format: "%02x", $0) }.joined())
    }

    static func readBackup(_ directory: URL) throws -> Backup {
        let manifest = try JSONDecoder().decode(Manifest.self, from: limited(directory.appendingPathComponent(markerName), maximum: 16 * 1024 * 1024))
        guard manifest.format == "webeasy-backup", manifest.version == 1, manifest.schemaVersion == 4,
              manifest.id.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil,
              date(manifest.createdAt) != nil, manifest.files["webeasy.sqlite"] != nil, manifest.files.count <= 100_000 else {
            throw ContentError.invalid("Unsupported or incomplete backup.")
        }
        for (path, entry) in manifest.files {
            _ = try safeFile(directory, path)
            guard (path == "webeasy.sqlite" || path.hasPrefix("media/")), entry.size >= 0,
                  entry.sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else {
                throw ContentError.invalid("Invalid backup inventory.")
            }
        }
        return Backup(directory: directory, manifest: manifest)
    }

    private static func list(_ root: URL) throws -> [Backup] {
        try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            .compactMap { directory -> Backup? in
                guard let values = try? directory.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]),
                      values.isDirectory == true, values.isSymbolicLink != true else { return nil }
                return try? readBackup(directory)
            }.sorted { $0.manifest.createdAt > $1.manifest.createdAt }
    }

    static func backups(_ root: URL) throws -> [Backup] {
        try access(root, writing: false) { try list($0) }
    }

    static func backup(_ root: URL, snapshot: URL, now: Date = Date()) throws -> Backup {
        try access(root, writing: true) { directory in
            let target = directory.appendingPathComponent(folderName(now), isDirectory: true)
            guard !FileManager.default.fileExists(atPath: target.path) else { throw ContentError.invalid("A backup already exists at this time.") }
            try FileManager.default.createDirectory(at: target, withIntermediateDirectories: false)
            var created: [URL] = []
            var paths: Set<String> = []
            do {
                var entries: [String: Entry] = [:]
                for (path, file) in try inventory(snapshot).files {
                    guard path == "webeasy.sqlite" || path.hasPrefix("media/") else { throw ContentError.invalid("Invalid snapshot contents.") }
                    let output = try safeFile(target, path)
                    paths.insert(path)
                    try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
                    created.append(output)
                    try FileManager.default.copyItem(at: file, to: output)
                    entries[path] = try fingerprint(file)
                    guard try fingerprint(output) == entries[path] else { throw ContentError.invalid("Backup write was incomplete.") }
                }
                guard entries["webeasy.sqlite"] != nil else { throw ContentError.invalid("Snapshot database is missing.") }
                let manifest = Manifest(format: "webeasy-backup", version: 1, schemaVersion: 4,
                    id: UUID().uuidString.lowercased(), createdAt: timestamp(now), files: entries)
                let marker = target.appendingPathComponent(markerName)
                created.append(marker)
                try JSONEncoder().encode(manifest).write(to: marker, options: .atomic)
                let result = try readBackup(target)
                try? cleanupUnlocked(directory, now: now)
                return result
            } catch {
                for file in created.reversed() { try? FileManager.default.removeItem(at: file) }
                try? deleteEmptyDirectories(target, parents: parentPaths(paths))
                throw error
            }
        }
    }

    static func restoreSnapshot(_ backup: Backup) throws -> URL {
        try access(backup.directory, writing: false) { directory in
            let current = try readBackup(directory)
            guard current.manifest == backup.manifest else { throw ContentError.invalid("Backup changed. Refresh and select it again.") }
            let staging = FileManager.default.temporaryDirectory.appendingPathComponent("webeasy-restore-\(UUID())", isDirectory: true)
            try FileManager.default.createDirectory(at: staging.appendingPathComponent("media"), withIntermediateDirectories: true)
            do {
                for (path, expected) in current.manifest.files {
                    let source = try safeFile(directory, path)
                    let output = try safeFile(staging, path)
                    try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
                    let input = try FileHandle(forReadingFrom: source)
                    defer { try? input.close() }
                    FileManager.default.createFile(atPath: output.path, contents: nil)
                    let target = try FileHandle(forWritingTo: output)
                    defer { try? target.close() }
                    var count: Int64 = 0
                    while let bytes = try input.read(upToCount: 65_536), !bytes.isEmpty {
                        count += Int64(bytes.count)
                        guard count <= expected.size else { throw ContentError.invalid("Backup file size does not match.") }
                        try target.write(contentsOf: bytes)
                    }
                    try target.synchronize()
                    guard try fingerprint(output) == expected else { throw ContentError.invalid("Backup file checksum does not match.") }
                }
                return staging
            } catch { try? FileManager.default.removeItem(at: staging); throw error }
        }
    }

    private static func parentPaths(_ paths: Set<String>) -> Set<String> {
        Set(paths.flatMap { path in
            let parts = path.split(separator: "/")
            return (1..<parts.count).map { parts.prefix($0).joined(separator: "/") }
        })
    }

    private static func deleteEmptyDirectories(_ root: URL, parents: Set<String>) throws {
        for path in parents.sorted(by: { $0.split(separator: "/").count > $1.split(separator: "/").count }) {
            let folder = try safeFile(root, path)
            if let contents = try? FileManager.default.contentsOfDirectory(atPath: folder.path), contents.isEmpty {
                try FileManager.default.removeItem(at: folder)
            }
        }
        if try FileManager.default.contentsOfDirectory(atPath: root.path).isEmpty { try FileManager.default.removeItem(at: root) }
    }

    static func cleanup(_ root: URL, now: Date = Date()) throws {
        try access(root, writing: true) { try cleanupUnlocked($0, now: now) }
    }

    private static func cleanupUnlocked(_ root: URL, now: Date) throws {
        let cutoff = Date(timeIntervalSince1970: floor(now.timeIntervalSince1970 * 1000) / 1000 - 14 * 86_400)
        for backup in try list(root) {
            guard let created = date(backup.manifest.createdAt), created < cutoff,
                  backup.directory.lastPathComponent == folderName(created) else { continue }
            do {
                let contents = try inventory(backup.directory)
                let expected = Set(backup.manifest.files.keys).union([markerName])
                let parents = parentPaths(expected)
                guard Set(contents.files.keys) == expected, contents.directories == parents else { continue }
                var valid = true
                for (path, entry) in backup.manifest.files {
                    if try fingerprint(contents.files[path]!) != entry { valid = false; break }
                }
                guard valid else { continue }
                for path in expected { try FileManager.default.removeItem(at: contents.files[path]!) }
                try deleteEmptyDirectories(backup.directory, parents: parents)
            } catch { continue }
        }
    }
}

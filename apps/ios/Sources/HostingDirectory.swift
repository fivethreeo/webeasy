import Foundation

struct HostingDirectory: Sendable {
    struct File: Sendable {
        let name: String
        let directory: Bool
        let regular: Bool
        let size: Int64
    }
    let identity: String
    let list: @Sendable (String) async throws -> [File]
    let mkdir: @Sendable (String) async throws -> Void
    let download: @Sendable (String, URL, Int64) async throws -> Void
    let upload: @Sendable (URL, String) async throws -> Void
    let remove: @Sendable (String, Bool) async throws -> Void

    func url(_ name: String) throws -> URL {
        _ = try DirectoryTools.safeFile(URL(fileURLWithPath: "/webeasy-hosting"), name)
        guard !name.contains("/"), let url = URL(string: "webeasy-hosting://\(identity)/\(name)") else {
            throw ContentError.invalid("Invalid backup directory.")
        }
        return url
    }

    func entries(_ path: String) async throws -> [File] {
        if !path.isEmpty { _ = try DirectoryTools.safeFile(URL(fileURLWithPath: "/webeasy-hosting"), path) }
        let files = try await list(path).filter { $0.name != "." && $0.name != ".." }
        guard files.count <= 100_000, Set(files.map(\.name)).count == files.count,
              files.allSatisfy({ !$0.name.contains("/") && !$0.name.contains("\\") }) else {
            throw ContentError.invalid("Invalid hosting directory contents.")
        }
        return files
    }

    func ensureDirectory(_ path: String, exclusive: Bool = false) async throws {
        let parts = path.split(separator: "/")
        guard !parts.isEmpty else { throw ContentError.invalid("Invalid hosting directory.") }
        for count in 1...parts.count {
            let current = parts.prefix(count).joined(separator: "/")
            let parent = parts.prefix(count - 1).joined(separator: "/")
            _ = try DirectoryTools.safeFile(URL(fileURLWithPath: "/webeasy-hosting"), current)
            if let found = try await entries(parent).first(where: { $0.name == String(parts[count - 1]) }) {
                guard found.directory, !(exclusive && count == parts.count) else {
                    throw ContentError.invalid("Hosting path already exists or is not a directory.")
                }
            } else {
                try await mkdir(current)
            }
        }
    }

    func write(_ local: URL, path: String) async throws {
        _ = try DirectoryTools.safeFile(URL(fileURLWithPath: "/webeasy-hosting"), path)
        let parts = path.split(separator: "/")
        let parent = parts.dropLast().joined(separator: "/")
        if !parent.isEmpty { try await ensureDirectory(parent) }
        if let found = try await entries(parent).first(where: { $0.name == parts.last.map(String.init) }) {
            guard found.regular else { throw ContentError.invalid("Hosting target is not a regular file.") }
        }
        try await upload(local, path)
    }

    func read(_ path: String, to local: URL, maximum: Int64) async throws {
        _ = try DirectoryTools.safeFile(URL(fileURLWithPath: "/webeasy-hosting"), path)
        guard maximum >= 0 else { throw ContentError.invalid("Invalid download size.") }
        let parts = path.split(separator: "/")
        var parent = ""
        for (index, component) in parts.enumerated() {
            guard let file = try await entries(parent).first(where: { $0.name == String(component) }),
                  (index == parts.count - 1 ? file.regular : file.directory) else {
                throw ContentError.invalid("Hosting file is missing or is a symbolic link.")
            }
            if index == parts.count - 1, file.size >= 0, file.size > maximum {
                throw ContentError.invalid("Hosting file exceeds its permitted size.")
            }
            parent = parent.isEmpty ? String(component) : parent + "/" + component
        }
        try FileManager.default.createDirectory(at: local.deletingLastPathComponent(), withIntermediateDirectories: true)
        try await download(path, local, maximum)
    }

    func inventory(_ path: String, ignoreHidden: Bool = false) async throws -> (files: [String: File], directories: Set<String>) {
        var files: [String: File] = [:]
        var directories: Set<String> = []
        func visit(_ folder: String, relative: String, depth: Int) async throws {
            guard depth <= 16 else { throw ContentError.invalid("Directory nesting is too deep.") }
            for file in try await entries(folder) {
                if ignoreHidden && (file.name.hasPrefix(".") || file.name == "Thumbs.db") { continue }
                let child = relative + file.name
                _ = try DirectoryTools.safeFile(URL(fileURLWithPath: "/webeasy-hosting"), child)
                guard files.count + directories.count < 100_000 else { throw ContentError.invalid("Too many directory entries.") }
                if file.directory {
                    directories.insert(child)
                    try await visit(folder + "/" + file.name, relative: child + "/", depth: depth + 1)
                } else {
                    guard file.regular else { throw ContentError.invalid("Symbolic links and special files are not supported.") }
                    files[child] = file
                }
            }
        }
        try await visit(path, relative: "", depth: 0)
        return (files, directories)
    }
}

enum HostingTools {
    private static func staging() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("webeasy-hosting-\(UUID())", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        return root
    }

    static func exportCustomization(_ directory: HostingDirectory, files: [String: Data]) async throws {
        let root = try staging()
        defer { try? FileManager.default.removeItem(at: root) }
        try DirectoryTools.exportCustomization(root, files: files)
        for path in files.keys.sorted() { try await directory.write(DirectoryTools.safeFile(root, path), path: path) }
    }

    static func loadCustomization(_ directory: HostingDirectory, sourcePaths: [String]) async throws -> DirectoryTools.Customization {
        let root = try staging()
        defer { try? FileManager.default.removeItem(at: root) }
        for path in sourcePaths {
            try await directory.read(path, to: DirectoryTools.safeFile(root, path), maximum: 256 * 1024)
        }
        try FileManager.default.createDirectory(at: root.appendingPathComponent("assets"), withIntermediateDirectories: true)
        let inventory = try await directory.inventory("assets", ignoreHidden: true)
        guard inventory.files.count <= 1024 else { throw ContentError.invalid("Too many website assets.") }
        var total: Int64 = 0
        for relative in inventory.files.keys.sorted() {
            let path = "assets/" + relative
            guard CustomizationAssets.validPath(path) else { throw ContentError.invalid("Editor resources cannot be customized.") }
            let maximum = min(Int64(CustomizationAssets.maximumFileBytes), Int64(CustomizationAssets.maximumTotalBytes) - total)
            let output = try DirectoryTools.safeFile(root, path)
            try await directory.read(path, to: output, maximum: maximum)
            total += try DirectoryTools.fingerprint(output).size
        }
        return try DirectoryTools.loadCustomization(root, sourcePaths: sourcePaths)
    }

    private static func readBackup(_ directory: HostingDirectory, name: String, staging root: URL) async throws -> DirectoryTools.Backup {
        let local = try DirectoryTools.safeFile(root, name)
        try await directory.read(name + "/" + DirectoryTools.markerName,
            to: local.appendingPathComponent(DirectoryTools.markerName), maximum: 16 * 1024 * 1024)
        let backup = try DirectoryTools.readBackup(local)
        return DirectoryTools.Backup(directory: try directory.url(name), manifest: backup.manifest)
    }

    static func backups(_ directory: HostingDirectory) async throws -> [DirectoryTools.Backup] {
        let root = try staging()
        defer { try? FileManager.default.removeItem(at: root) }
        var result: [DirectoryTools.Backup] = []
        for file in try await directory.entries("") where file.directory {
            if let backup = try? await readBackup(directory, name: file.name, staging: root) { result.append(backup) }
        }
        return result.sorted { $0.manifest.createdAt > $1.manifest.createdAt }
    }

    static func backup(_ directory: HostingDirectory, snapshot: URL, now: Date = Date()) async throws -> DirectoryTools.Backup {
        let root = try staging()
        defer { try? FileManager.default.removeItem(at: root) }
        let backup = try DirectoryTools.backup(root, snapshot: snapshot, now: now)
        let name = backup.directory.lastPathComponent
        try await directory.ensureDirectory(name, exclusive: true)
        let verification = root.appendingPathComponent("verification")
        for (path, expected) in backup.manifest.files.sorted(by: { $0.key < $1.key }) {
            try await directory.write(DirectoryTools.safeFile(backup.directory, path), path: name + "/" + path)
            try await directory.read(name + "/" + path, to: verification, maximum: expected.size)
            guard try DirectoryTools.fingerprint(verification) == expected else {
                throw ContentError.invalid("Hosting backup write was incomplete.")
            }
        }
        try await directory.write(backup.directory.appendingPathComponent(DirectoryTools.markerName),
            path: name + "/" + DirectoryTools.markerName)
        let confirmed = try await readBackup(directory, name: name, staging: root.appendingPathComponent("confirmed"))
        guard confirmed.manifest == backup.manifest else { throw ContentError.invalid("Hosting backup manifest changed.") }
        try? await cleanup(directory, now: now)
        return confirmed
    }

    static func restoreSnapshot(_ directory: HostingDirectory, backup: DirectoryTools.Backup) async throws -> URL {
        let name = backup.directory.lastPathComponent
        guard try directory.url(name) == backup.directory else { throw ContentError.invalid("Hosting destination changed. Refresh backups.") }
        let root = try staging()
        defer { try? FileManager.default.removeItem(at: root) }
        let current = try await readBackup(directory, name: name, staging: root)
        guard current.manifest == backup.manifest else { throw ContentError.invalid("Backup changed. Refresh and select it again.") }
        let local = try DirectoryTools.safeFile(root, name)
        for (path, expected) in current.manifest.files {
            try await directory.read(name + "/" + path, to: DirectoryTools.safeFile(local, path), maximum: expected.size)
        }
        return try DirectoryTools.restoreSnapshot(DirectoryTools.Backup(directory: local, manifest: current.manifest))
    }

    static func cleanup(_ directory: HostingDirectory, now: Date = Date()) async throws {
        let cutoff = Date(timeIntervalSince1970: floor(now.timeIntervalSince1970 * 1000) / 1000 - 14 * 86_400)
        let root = try staging()
        defer { try? FileManager.default.removeItem(at: root) }
        for backup in try await backups(directory) {
            let name = backup.directory.lastPathComponent
            guard let created = DirectoryTools.date(backup.manifest.createdAt), created < cutoff,
                  name == DirectoryTools.folderName(created) else { continue }
            do {
                let inventory = try await directory.inventory(name)
                let expected = Set(backup.manifest.files.keys).union([DirectoryTools.markerName])
                let parents = Set(expected.flatMap { path in
                    let parts = path.split(separator: "/")
                    return (1..<parts.count).map { parts.prefix($0).joined(separator: "/") }
                })
                guard Set(inventory.files.keys) == expected, inventory.directories == parents else { continue }
                let verification = root.appendingPathComponent("verification")
                for (path, entry) in backup.manifest.files {
                    try await directory.read(name + "/" + path, to: verification, maximum: entry.size)
                    guard try DirectoryTools.fingerprint(verification) == entry else { throw ContentError.invalid("Backup modified.") }
                }
                let current = try await readBackup(directory, name: name, staging: root)
                guard current.manifest == backup.manifest else { continue }
                try await directory.remove(name + "/" + DirectoryTools.markerName, false)
                for path in backup.manifest.files.keys { try await directory.remove(name + "/" + path, false) }
                for path in parents.sorted(by: { $0.split(separator: "/").count > $1.split(separator: "/").count }) {
                    guard try await directory.entries(name + "/" + path).isEmpty else { throw ContentError.invalid("Backup modified.") }
                    try await directory.remove(name + "/" + path, true)
                }
                if try await directory.entries(name).isEmpty { try await directory.remove(name, true) }
            } catch { continue }
        }
    }
}

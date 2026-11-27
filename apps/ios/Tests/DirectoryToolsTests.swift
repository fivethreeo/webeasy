import Foundation

@main
struct DirectoryToolsTests {
    static func main() async throws {
        let files = FileManager.default
        let root = files.temporaryDirectory.appendingPathComponent("webeasy-directory-test-\(UUID())", isDirectory: true)
        try files.createDirectory(at: root, withIntermediateDirectories: false)
        defer { try? files.removeItem(at: root) }
        try pathTests(root)
        let customization = root.appendingPathComponent("customization", isDirectory: true)
        try files.createDirectory(at: customization, withIntermediateDirectories: false)
        let path = "webeasy_templates/templates/master.html"
        try DirectoryTools.exportCustomization(customization, files: [
            path: Data("<html>{{title}}</html>".utf8),
            "assets/site.css": Data("body { color: red; }".utf8),
            "assets/site.js": Data(),
            "assets/logo.svg": Data("<svg/>".utf8),
            "assets/fonts/custom.woff2": Data([5, 6]),
        ])
        try Data("Keep this".utf8).write(to: customization.appendingPathComponent("notes.txt"))
        try Data("metadata".utf8).write(to: customization.appendingPathComponent("assets/.DS_Store"))
        try Data("body { color: blue; }".utf8).write(to: customization.appendingPathComponent("assets/site.css"))
        let loaded = try DirectoryTools.loadCustomization(customization, sourcePaths: [path])
        let sources = try JSONDecoder().decode([String: String].self, from: loaded.sources)
        precondition(sources[path] == "<html>{{title}}</html>")
        precondition(loaded.assets["assets/site.css"] == Data("body { color: blue; }".utf8))
        precondition(loaded.assets["assets/fonts/custom.woff2"] == Data([5, 6]))
        precondition(loaded.assets["assets/.DS_Store"] == nil)
        let notes = try Data(contentsOf: customization.appendingPathComponent("notes.txt"))
        precondition(notes == Data("Keep this".utf8))
        try files.removeItem(at: customization.appendingPathComponent("assets/site.js"))
        do {
            _ = try DirectoryTools.loadCustomization(customization, sourcePaths: [path])
            preconditionFailure("Incomplete customization was accepted.")
        } catch {}

        let snapshot = root.appendingPathComponent("snapshot", isDirectory: true)
        try files.createDirectory(at: snapshot.appendingPathComponent("media"), withIntermediateDirectories: true)
        try Data("database fixture".utf8).write(to: snapshot.appendingPathComponent("webeasy.sqlite"))
        try Data([1, 2, 3]).write(to: snapshot.appendingPathComponent("media/photo.png"))
        let destination = root.appendingPathComponent("backups", isDirectory: true)
        try files.createDirectory(at: destination, withIntermediateDirectories: false)
        let now = DirectoryTools.date("2026-11-26T10:20:30.000Z")!
        func backup(_ days: Double) throws -> DirectoryTools.Backup {
            try DirectoryTools.backup(destination, snapshot: snapshot, now: now.addingTimeInterval(-days * 86_400))
        }
        let old = try backup(21)
        let boundary = try backup(14)
        let extra = try backup(20)
        let empty = try backup(22)
        try Data("Keep".utf8).write(to: extra.directory.appendingPathComponent("notes.txt"))
        try files.createDirectory(at: empty.directory.appendingPathComponent("empty"), withIntermediateDirectories: false)
        let recent = try DirectoryTools.backup(destination, snapshot: snapshot, now: now)
        precondition(!files.fileExists(atPath: old.directory.path))
        precondition(files.fileExists(atPath: boundary.directory.path))
        precondition(files.fileExists(atPath: extra.directory.path))
        precondition(files.fileExists(atPath: empty.directory.path))
        let restored = try DirectoryTools.restoreSnapshot(recent)
        defer { try? files.removeItem(at: restored) }
        let restoredBytes = try Data(contentsOf: restored.appendingPathComponent("media/photo.png"))
        precondition(restoredBytes == Data([1, 2, 3]))
        try Data([3, 2, 1]).write(to: recent.directory.appendingPathComponent("media/photo.png"))
        do {
            _ = try DirectoryTools.restoreSnapshot(recent)
            preconditionFailure("Corrupt backup was accepted.")
        } catch {}
        try await hostingTests(root, snapshot: snapshot, now: now)
        print("Directory and hosting customization, backup integrity, and retention tests passed.")
    }

    static func pathTests(_ root: URL) throws {
        let files = FileManager.default
        let selected = root.appendingPathComponent("path-checks", isDirectory: true)
        let outside = root.appendingPathComponent("path-checks-outside", isDirectory: true)
        try files.createDirectory(at: selected, withIntermediateDirectories: false)
        try files.createDirectory(at: outside, withIntermediateDirectories: false)
        for folder in [selected, URL(fileURLWithPath: selected.path + "///", isDirectory: true)] {
            let target = try DirectoryTools.safeFile(folder, "nested/webeasy-backup.json")
            precondition(target.standardizedFileURL.pathComponents.last == "webeasy-backup.json")
        }
        try DirectoryTools.validatePath("2026-11-27_05-36-07-001Z/webeasy-backup.json")
        for path in ["", "/backups", "../outside", "nested/../outside", "nested//file", "nested/", "./file", "nested\\file", "nested/%2e%2e/file"] {
            do {
                try DirectoryTools.validatePath(path)
                preconditionFailure("Unsafe relative path accepted.")
            } catch {}
        }
        try files.createSymbolicLink(at: selected.appendingPathComponent("link"), withDestinationURL: outside)
        for path in ["link/file.txt", "link/missing/file.txt"] {
            do {
                _ = try DirectoryTools.safeFile(selected, path)
                preconditionFailure("Symlink escape into a shared-prefix sibling accepted.")
            } catch {}
        }
        let nested = selected.appendingPathComponent("nested", isDirectory: true)
        try files.createDirectory(at: nested, withIntermediateDirectories: false)
        try files.createSymbolicLink(at: selected.appendingPathComponent("inside"), withDestinationURL: nested)
        _ = try DirectoryTools.safeFile(selected, "inside/new-file.txt")
    }

    static func remote(_ root: URL, identity: String = "fixture") -> HostingDirectory {
        HostingDirectory(identity: identity,
            list: { path in
                let directory = path.isEmpty ? root : try DirectoryTools.safeFile(root, path)
                return try FileManager.default.contentsOfDirectory(at: directory,
                    includingPropertiesForKeys: [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]).map {
                        let values = try $0.resourceValues(forKeys: [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
                        return HostingDirectory.File(name: $0.lastPathComponent,
                            directory: values.isDirectory == true && values.isSymbolicLink != true,
                            regular: values.isRegularFile == true && values.isSymbolicLink != true,
                            size: values.fileSize.map { Int64($0) } ?? -1)
                    }
            },
            mkdir: { try FileManager.default.createDirectory(at: DirectoryTools.safeFile(root, $0), withIntermediateDirectories: false) },
            download: { path, local, maximum in
                let data = try Data(contentsOf: DirectoryTools.safeFile(root, path))
                guard data.count <= maximum else { throw ContentError.invalid("Download exceeds limit.") }
                try data.write(to: local)
            },
            upload: { local, path in try Data(contentsOf: local).write(to: DirectoryTools.safeFile(root, path)) },
            remove: { path, directory in
                let file = try DirectoryTools.safeFile(root, path)
                if directory {
                    guard try FileManager.default.contentsOfDirectory(atPath: file.path).isEmpty else {
                        throw ContentError.invalid("Directory is not empty.")
                    }
                }
                try FileManager.default.removeItem(at: file)
            })
    }

    static func hostingTests(_ root: URL, snapshot: URL, now: Date) async throws {
        let files = FileManager.default
        let website = root.appendingPathComponent("hosting-site")
        try files.createDirectory(at: website, withIntermediateDirectories: false)
        let directory = remote(website)
        let path = "webeasy_templates/templates/master.html"
        let assets = [
            path: Data("<html>Editable</html>".utf8),
            "assets/site.css": Data("body { color: red; }".utf8),
            "assets/site.js": Data(),
            "assets/logo.svg": Data("<svg/>".utf8),
            "assets/nested/nested": Data([1, 2])
        ]
        try Data("keep".utf8).write(to: website.appendingPathComponent("unrelated.txt"))
        try await HostingTools.exportCustomization(directory, files: assets)
        try Data("body { color: blue; }".utf8).write(to: website.appendingPathComponent("assets/site.css"))
        let loaded = try await HostingTools.loadCustomization(directory, sourcePaths: [path])
        precondition(loaded.assets["assets/site.css"] == Data("body { color: blue; }".utf8))
        precondition(loaded.assets["assets/nested/nested"] == Data([1, 2]))
        let notes = try Data(contentsOf: website.appendingPathComponent("unrelated.txt"))
        precondition(notes == Data("keep".utf8))
        try files.createSymbolicLink(at: website.appendingPathComponent("assets/link"), withDestinationURL: snapshot)
        do {
            _ = try await HostingTools.loadCustomization(directory, sourcePaths: [path])
            preconditionFailure("Symbolic link imported.")
        } catch {}
        try files.removeItem(at: website.appendingPathComponent("assets/link"))
        let privateRoot = root.appendingPathComponent("hosting-backups")
        try files.createDirectory(at: privateRoot, withIntermediateDirectories: false)
        let backups = remote(privateRoot)
        let old = try await HostingTools.backup(backups, snapshot: snapshot, now: now.addingTimeInterval(-15 * 86_400))
        let modified = try await HostingTools.backup(backups, snapshot: snapshot, now: now.addingTimeInterval(-16 * 86_400))
        try Data("keep".utf8).write(to: privateRoot.appendingPathComponent(modified.directory.lastPathComponent + "/notes.txt"))
        let boundary = try await HostingTools.backup(backups, snapshot: snapshot, now: now.addingTimeInterval(-14 * 86_400))
        let recent = try await HostingTools.backup(backups, snapshot: snapshot, now: now)
        let listed = try await HostingTools.backups(backups)
        precondition(!listed.contains { $0.id == old.id })
        precondition(listed.contains { $0.id == modified.id })
        precondition(listed.contains { $0.id == boundary.id })
        let restored = try await HostingTools.restoreSnapshot(backups, backup: recent)
        defer { try? files.removeItem(at: restored) }
        let bytes = try Data(contentsOf: restored.appendingPathComponent("media/photo.png"))
        precondition(bytes == Data([1, 2, 3]))
        do {
            _ = try await HostingTools.restoreSnapshot(remote(privateRoot, identity: "changed"), backup: recent)
            preconditionFailure("Backup restored from a changed destination.")
        } catch {}
        let remoteBackup = privateRoot.appendingPathComponent(recent.directory.lastPathComponent)
        try Data([3, 2, 1]).write(to: remoteBackup.appendingPathComponent("media/photo.png"))
        do {
            _ = try await HostingTools.restoreSnapshot(backups, backup: recent)
            preconditionFailure("Tampered hosting backup restored.")
        } catch {}
        try Data([1, 2, 3, 4]).write(to: remoteBackup.appendingPathComponent("media/photo.png"))
        do {
            _ = try await HostingTools.restoreSnapshot(backups, backup: recent)
            preconditionFailure("Oversized hosting backup restored.")
        } catch {}
        try files.removeItem(at: remoteBackup.appendingPathComponent(DirectoryTools.markerName))
        let incomplete = try await HostingTools.backups(backups)
        precondition(!incomplete.contains { $0.id == recent.id })
    }
}

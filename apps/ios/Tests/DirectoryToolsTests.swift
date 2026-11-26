import Foundation

@main
struct DirectoryToolsTests {
    static func main() throws {
        let files = FileManager.default
        let root = files.temporaryDirectory.appendingPathComponent("webeasy-directory-test-\(UUID())", isDirectory: true)
        try files.createDirectory(at: root, withIntermediateDirectories: false)
        defer { try? files.removeItem(at: root) }
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
        print("Directory customization, backup integrity, and retention tests passed.")
    }
}

import Foundation

@main
struct WebsiteFilesTests {
    enum Failure: Error { case unexpectedPath, expectedRejection }

    static func rejected(_ action: () throws -> Any) throws {
        do { _ = try action() }
        catch is InvalidWebsitePath { return }
        throw Failure.expectedRejection
    }

    static func main() throws {
        let manager = FileManager.default
        let temporary = manager.temporaryDirectory.appendingPathComponent("Webeasy-path-tests-\(UUID().uuidString)", isDirectory: true)
        try manager.createDirectory(at: temporary, withIntermediateDirectories: true)
        defer { try? manager.removeItem(at: temporary) }
        let root = temporary.appendingPathComponent("Website", isDirectory: true)
        try manager.createDirectory(at: root, withIntermediateDirectories: true)
        let alias = temporary.appendingPathComponent("Alias", isDirectory: true)
        try manager.createSymbolicLink(at: alias, withDestinationURL: root)
        let roots = [root, URL(fileURLWithPath: root.path + "/", isDirectory: true),
                     URL(fileURLWithPath: root.path, isDirectory: false), alias]
        for (index, base) in roots.enumerated() {
            let path = "new-\(index)/nested/index.html"
            let target = try WebsiteFiles.file(base, path)
            guard target.path == root.standardizedFileURL.resolvingSymlinksInPath().appendingPathComponent(path).path else {
                throw Failure.unexpectedPath
            }
            try manager.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try "New export".write(to: target, atomically: true, encoding: .utf8)
            guard try WebsiteFiles.relativePath(target, in: base) == path else { throw Failure.unexpectedPath }
        }
        for path in ["index.html", "assets/site.css", "about/team/index.html", "media/photo.mp4"] {
            let target = root.appendingPathComponent(path)
            try manager.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try "Website file".write(to: target, atomically: true, encoding: .utf8)
            for base in roots {
                let result = try WebsiteFiles.file(base, path)
                guard result == target.standardizedFileURL.resolvingSymlinksInPath(),
                      try WebsiteFiles.relativePath(target, in: base) == path,
                      try WebsiteFiles.relativePath(alias.appendingPathComponent(path), in: base) == path else {
                    throw Failure.unexpectedPath
                }
            }
        }
        for path in ["", "/index.html", "../outside.html", "assets/../index.html", "./index.html", "media/%2e%2e/file", "index.html\n"] {
            try rejected { try WebsiteFiles.file(root, path) }
        }
        let outside = temporary.appendingPathComponent("Website-other", isDirectory: true)
        try manager.createDirectory(at: outside, withIntermediateDirectories: true)
        let outsideFile = outside.appendingPathComponent("index.html")
        try "Private".write(to: outsideFile, atomically: true, encoding: .utf8)
        try rejected { try WebsiteFiles.relativePath(outsideFile, in: root) }
        try rejected { try WebsiteFiles.relativePath(root, in: root) }
        try manager.createSymbolicLink(at: root.appendingPathComponent("escape-directory", isDirectory: true), withDestinationURL: outside)
        try rejected { try WebsiteFiles.file(root, "escape-directory/not-created.html") }
        try manager.createSymbolicLink(at: root.appendingPathComponent("escape.html"), withDestinationURL: outsideFile)
        try rejected { try WebsiteFiles.file(root, "escape.html") }
        try rejected { try WebsiteFiles.relativePath(root.appendingPathComponent("escape.html"), in: root) }
        print("Website file-path tests passed: new export targets, directory variants, aliases, upload paths and escape rejection.")
    }
}

import Foundation

struct InvalidWebsitePath: LocalizedError {
    var errorDescription: String? { "Invalid file path." }
}

enum WebsiteFiles {
    private static func prefix(_ root: URL) throws -> String {
        guard root.isFileURL else { throw InvalidWebsitePath() }
        let path = root.standardizedFileURL.resolvingSymlinksInPath().path
        return path.hasSuffix("/") ? path : path + "/"
    }

    static func file(_ root: URL, _ path: String) throws -> URL {
        guard path.range(of: "^[a-zA-Z0-9][a-zA-Z0-9._/-]*$", options: .regularExpression) != nil,
              !path.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }) else {
            throw InvalidWebsitePath()
        }
        let file = root.appendingPathComponent(path).standardizedFileURL.resolvingSymlinksInPath()
        guard file.path.hasPrefix(try prefix(root)) else { throw InvalidWebsitePath() }
        return file
    }

    static func relativePath(_ file: URL, in root: URL) throws -> String {
        guard file.isFileURL else { throw InvalidWebsitePath() }
        let rootPrefix = try prefix(root)
        let path = file.standardizedFileURL.resolvingSymlinksInPath().path
        guard path.hasPrefix(rootPrefix) else { throw InvalidWebsitePath() }
        let relative = String(path.dropFirst(rootPrefix.count))
        _ = try self.file(root, relative)
        return relative
    }
}

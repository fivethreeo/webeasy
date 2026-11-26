import Foundation

enum CustomizationAssets {
    static let bundled = ["assets/site.css", "assets/site.js", "assets/logo.svg", "assets/coast.svg"]
    private static let trusted: Set<String> = ["editor.css", "editor.js", "editor.js.LICENSE.txt", "field.css", "field.js",
                                              "field.js.LICENSE.txt", "generator.js", "generator.js.LICENSE.txt"]
    static let maximumFileBytes = 16 * 1024 * 1024
    static let maximumTotalBytes = 64 * 1024 * 1024

    static func validPath(_ path: String) -> Bool {
        path.hasPrefix("assets/") && path.range(of: "^[A-Za-z0-9][A-Za-z0-9._/-]*$", options: .regularExpression) != nil &&
            !path.split(separator: "/", omittingEmptySubsequences: false).contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }) &&
            !trusted.contains(String(path.dropFirst(7)))
    }

    static func validate(_ files: [String: Data], complete: Bool = true) throws {
        guard files.count <= 1024, files.allSatisfy({ validPath($0.key) && $0.value.count <= maximumFileBytes }),
              files.values.reduce(0, { $0 + $1.count }) <= maximumTotalBytes else {
            throw ContentError.invalid("Invalid or oversized website assets.")
        }
        guard !complete || bundled.prefix(3).allSatisfy({ files[$0] != nil }) else {
            throw ContentError.invalid("Include assets/site.css, assets/site.js and assets/logo.svg.")
        }
    }
}

import Foundation

enum PublishingPaths {
    enum Invalid: LocalizedError {
        case subfolder
        case backupDirectory
        var errorDescription: String? {
            switch self {
            case .subfolder: return "Use a relative upload subfolder without parent segments."
            case .backupDirectory: return "Use an absolute private server directory outside the website directory. The website directory must also be absolute."
            }
        }
    }

    static func uploadPrefix(_ subfolder: String?) throws -> String {
        let value = subfolder ?? ""
        if value.isEmpty { return "" }
        guard value.count <= 512,
              value.split(separator: "/", omittingEmptySubsequences: false).allSatisfy({
                  !$0.isEmpty && $0 != "." && $0 != ".." &&
                  $0.unicodeScalars.allSatisfy { scalar in
                      (48...57).contains(scalar.value) || (65...90).contains(scalar.value) ||
                      (97...122).contains(scalar.value) || [45, 46, 95].contains(scalar.value)
                  }
              }) else { throw Invalid.subfolder }
        return value + "/"
    }

    static func privateBackupDirectory(_ directory: String, website: String, subfolder: String?) throws -> String {
        func normalized(_ path: String) throws -> String {
            guard path.hasPrefix("/"), path.count <= 512 else { throw Invalid.backupDirectory }
            let value = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard !value.isEmpty else { throw Invalid.backupDirectory }
            _ = try uploadPrefix(value)
            return "/" + value
        }
        let target = try normalized(directory.trimmingCharacters(in: .whitespacesAndNewlines))
        let publicRoot = try normalized(website)
        _ = try uploadPrefix(subfolder)
        guard target != publicRoot, !target.hasPrefix(publicRoot + "/") else { throw Invalid.backupDirectory }
        return target
    }
}

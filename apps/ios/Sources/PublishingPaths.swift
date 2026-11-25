import Foundation

enum PublishingPaths {
    enum Invalid: LocalizedError {
        case subfolder
        var errorDescription: String? { "Use a relative upload subfolder without parent segments." }
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
}

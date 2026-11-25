import Foundation

enum PublishingOperation {
    static func directory(isolation: isolated (any Actor)? = #isolation,
                          _ action: (@escaping @Sendable (Error?) -> Void) -> Progress?) async throws {
        var progress: Progress?
        // FilesProvider's MKD operation does not send QUIT; cancellation closes its completed stream.
        defer { progress?.cancel() }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            progress = action { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }
    }
}

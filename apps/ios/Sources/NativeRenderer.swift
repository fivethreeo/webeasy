import Foundation

enum NativeRenderer {
    // Project JSON enters C directly. C validates/sanitizes it and, when
    // requested, builds the contexts and renders every page before returning.
    static func generate(_ input: Data, compile: Bool) throws -> Data {
        let pointer = String(decoding: input, as: UTF8.self).withCString {
            compile ? webeasy_compile($0) : webeasy_normalize($0)
        }
        guard let pointer else { throw ContentError.invalid("Cannot generate website content.") }
        defer { webeasy_free(pointer) }
        guard let data = String(validatingCString: pointer)?.data(using: .utf8),
              let output = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ContentError.invalid("Invalid renderer response.")
        }
        if let error = output["error"] {
            throw ContentError.invalid(error as? String ?? "Cannot generate website content.")
        }
        guard output["project"] is [String: Any],
              !compile || (output["files"] is [[String: Any]] && output["referencedMedia"] is [String]) else {
            throw ContentError.invalid("Invalid rendered website.")
        }
        return data
    }

    // Low-level prepared requests remain useful for testing template rendering
    // independently. Normal save/export/publish uses generate() above instead.
    static func compile(_ prepared: Data) throws -> Data {
        guard var output = try JSONSerialization.jsonObject(with: prepared) as? [String: Any],
              let request = output["renderRequest"] as? [String: Any],
              let sources = output["files"] as? [[String: Any]],
              sources.allSatisfy({ $0["path"] is String && $0["html"] is String }) else {
            throw ContentError.invalid("Invalid prepared website.")
        }
        let requestData = try JSONSerialization.data(withJSONObject: request)
        let pointer = String(decoding: requestData, as: UTF8.self).withCString { webeasy_render($0) }
        guard let pointer else { throw ContentError.invalid("Cannot render website.") }
        defer { webeasy_free(pointer) }
        guard let resultData = String(validatingCString: pointer)?.data(using: .utf8),
              let result = try JSONSerialization.jsonObject(with: resultData) as? [String: Any] else {
            throw ContentError.invalid("Invalid renderer response.")
        }
        if let error = result["error"] {
            throw ContentError.invalid(error as? String ?? "Cannot render website.")
        }
        guard let files = result["files"] as? [[String: Any]],
              files.allSatisfy({ $0["path"] is String && $0["html"] is String }) else {
            throw ContentError.invalid("Invalid rendered files.")
        }
        output["files"] = files + sources
        output.removeValue(forKey: "renderRequest")
        return try JSONSerialization.data(withJSONObject: output)
    }
}

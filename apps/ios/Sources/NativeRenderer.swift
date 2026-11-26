import Foundation

enum NativeRenderer {
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

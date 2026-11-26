import Foundation

struct Catalog: Decodable {
    let version: Int
    let templates: [CatalogEntry]
    let components: [CatalogEntry]

    static func data(sources: Data? = nil) throws -> Data {
        guard let root = Bundle.main.url(forResource: "Website", withExtension: nil) else {
            throw CocoaError(.fileNoSuchFile)
        }
        let data = try Data(contentsOf: root.appendingPathComponent("catalog.json"))
        guard let sources else { return data }
        let overrides = try JSONDecoder().decode([String: String].self, from: sources)
        guard var catalog = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CocoaError(.fileReadCorruptFile)
        }
        for group in ["templates", "components"] {
            guard let entries = catalog[group] as? [[String: Any]] else { throw CocoaError(.fileReadCorruptFile) }
            catalog[group] = entries.map { original in
                var entry = original
                if let id = entry["id"] as? String, let source = overrides["webeasy_templates/\(group)/\(id).html"] {
                    entry["source"] = source
                }
                return entry
            }
        }
        return try JSONSerialization.data(withJSONObject: catalog)
    }

    static func load(sources: Data? = nil) throws -> Catalog {
        let catalog = try JSONDecoder().decode(
            Catalog.self,
            from: data(sources: sources)
        )
        guard catalog.version == 1 else {
            throw CocoaError(.fileReadCorruptFile)
        }
        return catalog
    }
}

struct CatalogEntry: Decodable, Identifiable {
    let id: String
    let title: String
    let description: String
    let preview: String
    let source: String
    let pageLayout: Bool?
}

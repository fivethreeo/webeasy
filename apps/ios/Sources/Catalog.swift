import Foundation

struct Catalog: Decodable {
    let version: Int
    let templates: [CatalogEntry]
    let components: [CatalogEntry]

    static func load() throws -> Catalog {
        guard let root = Bundle.main.url(forResource: "Website", withExtension: nil) else {
            throw CocoaError(.fileNoSuchFile)
        }
        let catalog = try JSONDecoder().decode(
            Catalog.self,
            from: Data(contentsOf: root.appendingPathComponent("catalog.json"))
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
}

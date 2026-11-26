import Foundation

enum ContentSlugs {
    private static let reserved: Set<String> = ["news", "assets", "media", "examples", "components", "editor", "provision", "catalog", "content", "index"]

    static func fromTitle(_ title: String, fallback: String) -> String {
        let slug = title.decomposedStringWithCompatibilityMapping
            .replacingOccurrences(of: "[\\u0300-\\u036f]", with: "", options: .regularExpression)
            .lowercased(with: Locale(identifier: "en_US_POSIX"))
            .replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        return slug.isEmpty ? fallback : slug
    }

    private static func unique(_ base: String, taken: Set<String>) -> String {
        guard !base.isEmpty else { return base }
        var slug = base
        var suffix = 2
        while taken.contains(slug) { slug = "\(base)-\(suffix)"; suffix += 1 }
        return slug
    }

    static func generated(_ title: String, news: Bool, record: [String: String],
                          siblings: [[String: String]], rootPage: Bool) -> String {
        var taken = Set(siblings.filter {
            $0["id"] != record["id"] && (!news || $0["date"] == record["date"])
        }.compactMap { $0["slug"] })
        if rootPage { taken.formUnion(reserved) }
        return unique(fromTitle(title, fallback: news ? "news" : "page"), taken: taken)
    }

    static func update(_ record: inout [String: String], source: String?, key: String, value: String,
                       automatic: Bool, siblings: [[String: String]] = [], rootPage: Bool = false) {
        record[key] = value
        if automatic && key == source {
            record["slug"] = generated(value, news: source == "title", record: record, siblings: siblings, rootPage: rootPage)
        }
    }

    static func resolve(_ records: [[String: String]], savedIDs: Set<String>,
                        rootPage: Bool = false, news: Bool = false) -> [[String: String]] {
        var taken: [String: Set<String>] = [:]
        for record in records where savedIDs.contains(record["id"] ?? "") {
            let group = news ? record["date"] ?? "" : ""
            taken[group, default: []].insert(record["slug"] ?? "")
        }
        return records.map { original in
            var record = original
            guard !savedIDs.contains(record["id"] ?? "") else { return record }
            let group = news ? record["date"] ?? "" : ""
            if rootPage { taken[group, default: []].formUnion(reserved) }
            let slug = unique(record["slug"] ?? "", taken: taken[group, default: []])
            record["slug"] = slug
            taken[group, default: []].insert(slug)
            return record
        }
    }
}

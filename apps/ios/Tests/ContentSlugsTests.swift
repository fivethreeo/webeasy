import Foundation

@main
struct ContentSlugsTests {
    enum Failure: Error { case unexpectedSlug }
    static func expect(_ condition: Bool) throws {
        if !condition { throw Failure.unexpectedSlug }
    }

    static func main() throws {
        var page = ["id": "new", "slug": "custom", "date": "2026-11-26"]
        for (title, slug) in [("A", "a"), ("About us", "about-us"), ("Café & culture!", "cafe-culture")] {
            ContentSlugs.update(&page, source: "menuTitle", key: "menuTitle", value: title, automatic: true)
            try expect(page["slug"] == slug && page["menuTitle"] == title)
        }
        ContentSlugs.update(&page, source: "menuTitle", key: "title", value: "Different heading", automatic: true)
        try expect(page["slug"] == "cafe-culture")
        try expect(ContentSlugs.fromTitle("?!", fallback: "page") == "page")
        ContentSlugs.update(&page, source: "menuTitle", key: "menuTitle", value: "New title", automatic: false)
        try expect(page["slug"] == "cafe-culture")

        let saved = ["id": "saved", "slug": "update", "date": "2026-11-26"]
        let otherDate = ["id": "other-date", "slug": "update-2", "date": "2026-11-25"]
        var news = ["id": "new-news", "slug": "custom", "date": "2026-11-26"]
        ContentSlugs.update(&news, source: "title", key: "title", value: "Update", automatic: true,
                            siblings: [saved, otherDate])
        try expect(news["slug"] == "update-2")
        ContentSlugs.update(&news, source: "title", key: "title", value: "Changed", automatic: false)
        try expect(news["slug"] == "update-2")

        let first = ["id": "first", "slug": "about"]
        let second = ["id": "second", "slug": "about"]
        let existing = ["id": "saved", "slug": "about"]
        let resolved = ContentSlugs.resolve([first, second, existing], savedIDs: ["saved"], rootPage: true)
        try expect(resolved.map { $0["slug"] ?? "" } == ["about-2", "about-3", "about"])
        try expect(ContentSlugs.resolve(resolved, savedIDs: ["saved"], rootPage: true) == resolved)
        try expect(ContentSlugs.generated("News", news: false, record: page, siblings: [], rootPage: true) == "news-2")
        try expect(ContentSlugs.generated("News", news: false, record: page, siblings: [], rootPage: false) == "news")
        try expect(ContentSlugs.resolve([["id": "empty", "slug": ""]], savedIDs: [], rootPage: true)[0]["slug"] == "")

        let duplicate = ["id": "duplicate", "slug": "update", "date": "2026-11-26"]
        let differentDate = ["id": "another-day", "slug": "update", "date": "2026-11-25"]
        let routes = ContentSlugs.resolve([duplicate, differentDate, saved], savedIDs: ["saved"], news: true)
        try expect(routes.map { $0["slug"] ?? "" } == ["update-2", "update", "update"])
        try expect(ContentSlugs.resolve([duplicate, saved], savedIDs: ["duplicate", "saved"], news: true) == [duplicate, saved])
        print("Live slug generation, preserved URLs and save conflict tests passed.")
    }
}

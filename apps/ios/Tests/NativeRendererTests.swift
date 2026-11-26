import Foundation

@main
struct NativeRendererTests {
    static func main() async throws {
        let prepared = try JSONSerialization.data(withJSONObject: [
            "project": ["title": "Blåbær 🌍"],
            "renderRequest": [
                "partials": ["page": "<h1>{{title}}</h1>{{#items}}<p>{{name}}</p>{{/items}}{{{content}}}"],
                "documents": [[
                    "path": "nyheter/🎉.html", "template": "page",
                    "values": [
                        "title": "Blåbær 🌍 & <",
                        "items": [["name": "Første"], ["name": "Andre 👋"]],
                        "content": "<aside>Hei 👋</aside>"
                    ]
                ]],
                "rawSlots": ["content", "navigation", "html"]
            ],
            "files": [["path": "webeasy_templates/templates/page.html", "html": "{{title}}"]],
            "referencedMedia": ["photo-1"]
        ] as [String: Any])
        for _ in 0..<20 {
            let data = try await Task.detached { try NativeRenderer.compile(prepared) }.value
            let output = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            let files = output["files"] as! [[String: String]]
            precondition(files.count == 2)
            precondition(files[0]["path"] == "nyheter/🎉.html")
            precondition(files[0]["html"] == "<h1>Blåbær 🌍 &amp; &lt;</h1><p>Første</p><p>Andre 👋</p><aside>Hei 👋</aside>")
            precondition(files[1]["path"] == "webeasy_templates/templates/page.html")
            precondition(files[1]["html"] == "{{title}}")
            precondition((output["project"] as? [String: String])?["title"] == "Blåbær 🌍")
            precondition(output["referencedMedia"] as? [String] == ["photo-1"])
            precondition(output["renderRequest"] == nil)
        }
        let invalid = try JSONSerialization.data(withJSONObject: [
            "renderRequest": [:], "files": []
        ] as [String: Any])
        do {
            _ = try NativeRenderer.compile(invalid)
            preconditionFailure("C renderer error was ignored.")
        } catch ContentError.invalid(let message) {
            precondition(!message.isEmpty)
        }
        let malformed = try JSONSerialization.data(withJSONObject: [
            "renderRequest": [:], "files": [["path": 17, "html": "bad"]]
        ] as [String: Any])
        do {
            _ = try NativeRenderer.compile(malformed)
            preconditionFailure("Malformed prepared file was accepted.")
        } catch ContentError.invalid {}
        print("Native Swift/C renderer tests passed: UTF-8, loops, worker isolation, file order, metadata and errors.")
    }
}

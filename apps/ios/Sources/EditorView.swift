import SwiftUI
import WebKit
import UniformTypeIdentifiers

@MainActor
final class ContentNode: ObservableObject, Identifiable {
    let id: String
    @Published var fields: [String: String]
    @Published var children: [ContentNode]
    @Published var sections: [ContentNode]
    @Published var asides: [ContentNode]

    init(_ record: [String: Any]) {
        id = record["id"] as? String ?? UUID().uuidString
        fields = record.compactMapValues { $0 as? String }
        children = (record["children"] as? [[String: Any]] ?? []).map { ContentNode($0) }
        sections = (record["sections"] as? [[String: Any]] ?? []).map { ContentNode($0) }
        asides = (record["asides"] as? [[String: Any]] ?? []).map { ContentNode($0) }
    }

    var json: [String: Any] {
        var result: [String: Any] = fields
        result["id"] = id
        result["children"] = children.map(\.json)
        result["sections"] = sections.map(\.json)
        result["asides"] = asides.map(\.json)
        return result
    }

    func restore(_ record: [String: Any]) {
        func nodes(_ key: String, _ existing: [ContentNode]) -> [ContentNode] {
            let byID = Dictionary(uniqueKeysWithValues: existing.map { ($0.id, $0) })
            return (record[key] as? [[String: Any]] ?? []).map { item in
                if let id = item["id"] as? String, let node = byID[id] { node.restore(item); return node }
                return ContentNode(item)
            }
        }
        children = nodes("children", children)
        sections = nodes("sections", sections)
        asides = nodes("asides", asides)
        fields = record.compactMapValues { $0 as? String }
    }
}

enum ContentKind: Equatable { case page, news, section, aside }

struct NativePreview: Identifiable {
    let id = UUID()
    let root: URL
    let path: String
    let temporary: Bool
}

@MainActor
final class NativeEditorModel: NSObject, ObservableObject, WKNavigationDelegate {
    private static let storage: Result<ContentStore, Error> = Result { try ContentStore() }
    @Published var pages: [ContentNode] = []
    @Published var news: [ContentNode] = []
    @Published var status = "Opening local SQLite project..."
    @Published var ready = false
    @Published var advancedUI = UserDefaults.standard.bool(forKey: "advancedUI") {
        didSet { UserDefaults.standard.set(advancedUI, forKey: "advancedUI") }
    }
    @Published var busy = false
    @Published var backups: [DirectoryTools.Backup] = []
    @Published var backupDirectory = UserDefaults.standard.string(forKey: "hostingBackupDirectory") ?? "" {
        didSet {
            UserDefaults.standard.set(backupDirectory, forKey: "hostingBackupDirectory")
            backups = []
            pendingHostingAction = nil
        }
    }
    @Published var restoreRevision = 0
    @Published var preview: NativePreview?
    @Published var homePageID: String?
    @Published var pendingHostKey: HostKeyApproval?
    let store: ContentStore?
    @Published var catalog: Catalog?
    var hostKeyForTemplates = false
    enum HostingAction {
        case exportCustomization, loadCustomization, refreshBackups, createBackup
        case restoreBackup(DirectoryTools.Backup)
    }
    var pendingHostingAction: HostingAction?
    var hasHosting: Bool { HostingCredentials.load() != nil }
    private var media: [[String: Any]] = []
    private let generator = WKWebView()
    private var revision = 0
    private var savedNodes: [String: Data] = [:]
    private var savedHomePageID: String?
    private var savedProject: Data?
    private var publishedProject: Data?
    private var automaticSlugs: Set<String> = []

    override init() {
        let storageError: String?
        do {
            store = try Self.storage.get()
            storageError = nil
        } catch {
            store = nil
            storageError = error.localizedDescription
        }
        super.init()
        do {
            guard let store else { throw ContentError.invalid(storageError ?? "Cannot open local SQLite storage.") }
            catalog = try Catalog.load(sources: store.templateSources())
            let project = try store.project()
            savedProject = try JSONSerialization.data(withJSONObject: project)
            publishedProject = try store.publishedSnapshot()
            pages = (project["pages"] as? [[String: Any]] ?? []).map { ContentNode($0) }
            news = (project["news"] as? [[String: Any]] ?? []).map { ContentNode($0) }
            homePageID = project["homePageId"] as? String ?? pages.first?.id
            savedNodes = try snapshot()
            savedHomePageID = homePageID
            media = project["media"] as? [[String: Any]] ?? []
            generator.navigationDelegate = self
            generator.loadFileURL(store.websiteRoot.appendingPathComponent("generator.html"), allowingReadAccessTo: store.websiteRoot)
        } catch { status = error.localizedDescription }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        ready = true
        status = "Local SQLite. Save changes before closing."
    }

    func changed() {
        if homePageID == nil || pagePath(homePageID!) == nil { homePageID = pages.first?.id }
        revision += 1
        status = "Unsaved changes."
    }

    private func nodeData(_ node: ContentNode) throws -> Data {
        var record = node.json
        record["_homepage"] = node.id == homePageID
        return try JSONSerialization.data(withJSONObject: record, options: .sortedKeys)
    }
    private func snapshot() throws -> [String: Data] {
        var result: [String: Data] = [:]
        func collect(_ nodes: [ContentNode]) throws {
            for node in nodes {
                result[node.id] = try nodeData(node)
                try collect(node.children); try collect(node.sections); try collect(node.asides)
            }
        }
        try collect(pages); try collect(news)
        return result
    }
    func hasChanges(_ node: ContentNode) -> Bool { savedNodes[node.id] != (try? nodeData(node)) }
    func discard(_ node: ContentNode) {
        if node.id == homePageID || node.id == savedHomePageID { homePageID = savedHomePageID }
        if let data = savedNodes[node.id],
           let record = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            node.restore(record)
        } else {
            func remove(_ nodes: inout [ContentNode]) {
                nodes.removeAll { $0.id == node.id }
                for item in nodes { remove(&item.children); remove(&item.sections); remove(&item.asides) }
            }
            remove(&pages); remove(&news)
        }
        changed()
        status = "Changes discarded."
    }

    func registerMedia(_ record: [String: Any]) {
        if !media.contains(where: { $0["id"] as? String == record["id"] as? String }) { media.append(record) }
    }

    func importHostingQR(_ code: String) async throws -> String {
        guard ready else { throw ContentError.invalid("Wait for the app to finish opening, then try again.") }
        let argument = try JSONSerialization.data(withJSONObject: code, options: .fragmentsAllowed)
        let value = try await generator.evaluateJavaScript(
            "window.WebeasyGenerator.readConnection(\(String(decoding: argument, as: UTF8.self)))")
        guard let serialized = value as? String, let data = serialized.data(using: .utf8),
              let output = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ContentError.invalid("Cannot read this hosting QR.")
        }
        if let error = output["error"] as? String { throw ContentError.invalid(error) }
        guard var connection = output["connection"] as? [String: Any] else { throw ContentError.invalid("Invalid hosting QR.") }
        if let data = HostingCredentials.load(),
           let previous = try? JSONDecoder().decode(HostingDestination.self, from: data),
           previous.protocol == "sftp", connection["protocol"] as? String == "sftp",
           connection["host"] as? String == previous.host, connection["port"] as? Int == previous.port,
           let fingerprint = previous._hostKeyFingerprint {
            connection["_hostKeyFingerprint"] = fingerprint
        }
        if let data = HostingCredentials.load(),
           let previous = try? JSONDecoder().decode(HostingDestination.self, from: data),
           connection["protocol"] as? String == previous.protocol,
           connection["host"] as? String == previous.host, connection["port"] as? Int == previous.port,
           connection["directory"] as? String == previous.directory {
            connection["publicPathPrefix"] = previous.publicPathPrefix
            connection["uploadSubfolder"] = previous.uploadSubfolder
        }
        try HostingCredentials.save(JSONSerialization.data(withJSONObject: connection))
        backups = []; pendingHostingAction = nil; pendingHostKey = nil
        return "\(ui("Saved hosting")): \(connection["host"] as? String ?? ""). \(ui("Not connected yet."))"
    }

    func savePublishingSettings(publicPathPrefix: String, uploadSubfolder: String) async throws -> HostingDestination {
        guard ready, !busy else { throw ContentError.invalid("Wait for the app to finish its current operation.") }
        busy = true
        defer { busy = false }
        let settings = ["publicPathPrefix": publicPathPrefix, "uploadSubfolder": uploadSubfolder]
        let serialized = String(decoding: try JSONSerialization.data(withJSONObject: settings), as: UTF8.self)
        let argument = try JSONSerialization.data(withJSONObject: serialized, options: .fragmentsAllowed)
        let result = try await generator.evaluateJavaScript(
            "window.WebeasyGenerator.readPublishingSettings(\(String(decoding: argument, as: UTF8.self)))")
        guard let result = result as? String, let data = result.data(using: .utf8),
              let output = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ContentError.invalid("Cannot validate publishing settings.")
        }
        if let error = output["error"] as? String { throw ContentError.invalid(error) }
        guard let normalized = output["settings"] as? [String: String],
              let saved = HostingCredentials.load(),
              var destination = try? JSONDecoder().decode(HostingDestination.self, from: saved) else {
            throw ContentError.invalid("Import your hosting setup QR first.")
        }
        destination.publicPathPrefix = normalized["publicPathPrefix"]
        destination.uploadSubfolder = normalized["uploadSubfolder"]
        try HostingCredentials.save(JSONEncoder().encode(destination))
        backups = []; pendingHostingAction = nil; pendingHostKey = nil
        return destination
    }

    func newNode(_ kind: ContentKind, siblings: [ContentNode], rootPage: Bool = false) -> ContentNode {
        let prefix = kind == .news ? "news" : "page"
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        let node = ContentNode(["id": UUID().uuidString, "title": "New \(prefix)", "menuTitle": "New page",
                            "date": formatter.string(from: Date()),
                            "heading": kind == .section ? "New section" : "", "content": "<p></p>", "shortContent": "<p></p>"])
        node.fields["slug"] = ContentSlugs.generated("New \(prefix)", news: kind == .news, record: node.fields,
                                                    siblings: siblings.map(\.fields), rootPage: rootPage)
        if kind == .page || kind == .news { automaticSlugs.insert(node.id) }
        return node
    }

    func updateText(_ node: ContentNode, kind: ContentKind, key: String, value: String) {
        if key == "slug" { automaticSlugs.remove(node.id) }
        let source = kind == .page ? "menuTitle" : kind == .news ? "title" : nil
        var siblings: [ContentNode] = []
        var rootPage = false
        let automatic = automaticSlugs.contains(node.id) && savedNodes[node.id] == nil
        if automatic && key == source {
            if kind == .news { siblings = news }
            else {
                func find(_ nodes: [ContentNode]) -> [ContentNode]? {
                    if nodes.contains(where: { $0.id == node.id }) { return nodes }
                    for item in nodes { if let found = find(item.children) { return found } }
                    return nil
                }
                siblings = find(pages) ?? []
                rootPage = pages.contains { $0.id == node.id }
            }
        }
        var fields = node.fields
        ContentSlugs.update(&fields, source: source, key: key, value: value, automatic: automatic,
                            siblings: siblings.map(\.fields), rootPage: rootPage)
        node.fields = fields
        changed()
    }

    private func resolveSlugConflicts() {
        let savedIDs = Set(savedNodes.keys)
        var resolved = false
        func resolve(_ nodes: [ContentNode], rootPage: Bool = false, news: Bool = false) {
            let records = ContentSlugs.resolve(nodes.map(\.fields), savedIDs: savedIDs, rootPage: rootPage, news: news)
            for (node, record) in zip(nodes, records) where node.fields["slug"] != record["slug"] {
                node.fields["slug"] = record["slug"]; resolved = true
            }
        }
        func collect(_ nodes: [ContentNode], root: Bool) {
            resolve(nodes, rootPage: root)
            for node in nodes { collect(node.children, root: false) }
        }
        collect(pages, root: true)
        resolve(news, news: true)
        if resolved { changed() }
    }

    private var project: [String: Any] {
        ["version": 1, "homePageId": homePageID.map { $0 as Any } ?? NSNull(), "pages": pages.map(\.json), "news": news.map(\.json), "media": media]
    }

    private func generate(_ compile: Bool, publicPathPrefix: String? = nil) async throws -> [String: Any] {
        guard let store else { throw ContentError.invalid("Storage unavailable.") }
        var input = project
        if compile {
            let catalogData = try Catalog.data(sources: store.templateSources())
            input = ["project": input, "catalog": try JSONSerialization.jsonObject(with: catalogData)]
            if let publicPathPrefix { input["publicPathPrefix"] = publicPathPrefix }
        }
        let data = try JSONSerialization.data(withJSONObject: input)
        // Capture immutable JSON on the UI thread; the complete C pipeline runs
        // off it. Saving/previewing/exporting/publishing no longer executes JS.
        let rendered = try await Task.detached { try NativeRenderer.generate(data, compile: compile) }.value
        guard let output = try JSONSerialization.jsonObject(with: rendered) as? [String: Any] else {
            throw ContentError.invalid("Cannot generate website content.")
        }
        return output
    }

    func changes(published: Bool) async throws -> [ProjectChange]? {
        guard ready, !busy else { throw ContentError.invalid("Wait for the current operation to finish.") }
        guard let baseline = published ? publishedProject : savedProject else { return nil }
        let input: [String: Any] = ["before": try JSONSerialization.jsonObject(with: baseline), "after": project]
        let serialized = String(decoding: try JSONSerialization.data(withJSONObject: input), as: UTF8.self)
        let argument = try JSONSerialization.data(withJSONObject: serialized, options: .fragmentsAllowed)
        let result = try await generator.evaluateJavaScript(
            "window.WebeasyGenerator.changes(\(String(decoding: argument, as: UTF8.self)))")
        guard let serialized = result as? String, let data = serialized.data(using: .utf8),
              let output = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ContentError.invalid("Cannot compare content.")
        }
        if let error = output["error"] as? String { throw ContentError.invalid(error) }
        return try JSONDecoder().decode([ProjectChange].self,
            from: JSONSerialization.data(withJSONObject: output["changes"]!))
    }

    func save(afterSaved: (() -> Void)? = nil) {
        guard !busy, ready, let store else { return }
        resolveSlugConflicts()
        busy = true
        let savedRevision = revision
        Task {
            defer { busy = false }
            do {
                let nodes = try snapshot()
                let savedHome = homePageID
                let output = try await generate(false)
                let data = try JSONSerialization.data(withJSONObject: output["project"]!)
                try await Task.detached {
                    guard let project = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw ContentError.invalid("Invalid project.") }
                    try store.save(project)
                }.value
                savedNodes = nodes
                automaticSlugs.subtract(nodes.keys)
                savedHomePageID = savedHome
                savedProject = data
                status = savedRevision == revision ? "Saved locally to SQLite." : "More recent changes remain unsaved."
                if savedRevision == revision { afterSaved?() }
            } catch { status = error.localizedDescription }
        }
    }

    func site(_ path: String?, export: Bool, publish: Bool = false) {
        guard !busy, ready, let store else { return }
        let destination = publish ? HostingCredentials.load().flatMap { try? JSONDecoder().decode(HostingDestination.self, from: $0) } : nil
        if publish && destination == nil { status = "Import your hosting setup QR first."; return }
        if export || publish { resolveSlugConflicts() }
        busy = true
        let savedRevision = revision
        Task {
            defer { busy = false }
            do {
                let nodes = try snapshot()
                let savedHome = homePageID
                let output = try await generate(true, publicPathPrefix: destination?.publicPathPrefix)
                let data = try JSONSerialization.data(withJSONObject: output)
                let projectData = try JSONSerialization.data(withJSONObject: output["project"]!)
                if export || publish {
                    try await Task.detached {
                        guard let project = try JSONSerialization.jsonObject(with: projectData) as? [String: Any] else {
                            throw ContentError.invalid("Invalid project.")
                        }
                        try store.save(project)
                    }.value
                    savedNodes = nodes
                    automaticSlugs.subtract(nodes.keys)
                    savedHomePageID = savedHome
                    savedProject = projectData
                }
                let directory = try await Task.detached {
                    guard let output = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                          let files = output["files"] as? [[String: Any]],
                          let references = output["referencedMedia"] as? [String] else { throw ContentError.invalid("Invalid website.") }
                    return try store.exportSite(files, references: references)
                }.value
                if let destination {
                    defer { try? FileManager.default.removeItem(at: directory) }
                    status = "Publishing website..."
                    try await SitePublisher.publish(destination, root: directory)
                    do {
                        try await Task.detached { try store.savePublishedSnapshot(projectData) }.value
                        publishedProject = projectData
                    } catch {
                        status = "Website published, but its change snapshot could not be saved."
                        return
                    }
                    status = savedRevision == revision ? "Website published." : "Published snapshot excludes newer edits."
                } else if export {
                    preview = NativePreview(root: directory, path: "", temporary: true)
                    status = savedRevision == revision ? "Website ready to export." : "Exported snapshot excludes newer edits."
                } else {
                    preview = NativePreview(root: directory, path: path ?? "index.html", temporary: true)
                }
            } catch let approval as HostKeyApproval {
                hostKeyForTemplates = false
                pendingHostKey = approval
                status = "Confirm the SFTP server key before publishing."
            } catch { status = error.localizedDescription }
        }
    }

    func trustHostKey(_ key: HostKeyApproval) {
        do {
            guard let data = HostingCredentials.load() else { throw ContentError.invalid("Import hosting first.") }
            var destination = try JSONDecoder().decode(HostingDestination.self, from: data)
            guard destination.host == key.host, destination.port == key.port else { throw ContentError.invalid("Hosting destination changed.") }
            destination._hostKeyFingerprint = key.fingerprint
            try HostingCredentials.save(JSONEncoder().encode(destination))
            pendingHostKey = nil
            if let action = pendingHostingAction {
                pendingHostingAction = nil
                switch action {
                case .exportCustomization: exportCustomization()
                case .loadCustomization: loadCustomization()
                case .refreshBackups: refreshBackups()
                case .createBackup: createBackup()
                case .restoreBackup(let backup): restoreBackup(backup)
                }
            } else if hostKeyForTemplates { loadServerTemplates() }
            else { site(nil, export: false, publish: true) }
        } catch { status = error.localizedDescription }
    }

    private func templateCall(_ name: String, input: Any) async throws -> [String: Any] {
        let serialized = String(decoding: try JSONSerialization.data(withJSONObject: input), as: UTF8.self)
        let argument = try JSONSerialization.data(withJSONObject: serialized, options: .fragmentsAllowed)
        let result = try await generator.evaluateJavaScript(
            "window.WebeasyGenerator.\(name)(\(String(decoding: argument, as: UTF8.self)))")
        guard let result = result as? String, let data = result.data(using: .utf8),
              let output = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ContentError.invalid("Cannot process templates.")
        }
        if let error = output["error"] as? String { throw ContentError.invalid(error) }
        return output
    }

    func loadServerTemplates() {
        guard ready, !busy, let store else { return }
        guard let data = HostingCredentials.load(),
              let destination = try? JSONDecoder().decode(HostingDestination.self, from: data) else {
            status = "Import your hosting setup QR first."; return
        }
        busy = true
        status = "Loading templates from server..."
        Task {
            defer { busy = false }
            do {
                let current = try JSONSerialization.jsonObject(with: Catalog.data(sources: store.templateSources()))
                let output = try await templateCall("templates", input: current)
                guard let expected = output["sources"] as? [String: String] else { throw ContentError.invalid("Cannot list templates.") }
                let sources = try await SitePublisher.loadTemplates(destination, paths: Array(expected.keys).sorted())
                let updated = try await templateCall("loadTemplates", input: ["catalog": current, "sources": sources, "project": project])
                guard let catalogObject = updated["catalog"] else { throw ContentError.invalid("Cannot read templates.") }
                let updatedCatalog = try JSONDecoder().decode(Catalog.self, from: JSONSerialization.data(withJSONObject: catalogObject))
                let sourceData = try JSONSerialization.data(withJSONObject: sources)
                try await Task.detached { try store.saveTemplateSources(sourceData) }.value
                catalog = updatedCatalog
                status = "Templates loaded and saved locally. Previews, exports and the next publish use them."
            } catch let approval as HostKeyApproval {
                hostKeyForTemplates = true; pendingHostKey = approval
                status = "Confirm the SFTP server key before loading templates."
            } catch { status = "Templates not loaded: \(error.localizedDescription) Local templates have not changed." }
        }
    }

    private func hostingDestination(backup: Bool = false) throws -> HostingDestination {
        guard let data = HostingCredentials.load() else { throw ContentError.invalid("Import your hosting setup QR first.") }
        let destination = try JSONDecoder().decode(HostingDestination.self, from: data)
        if backup { return try SitePublisher.privateBackupDestination(destination, directory: backupDirectory) }
        return destination
    }

    private func hostingFailure(_ error: Error, retry: HostingAction) {
        if let approval = error as? HostKeyApproval {
            pendingHostingAction = retry
            pendingHostKey = approval
            status = "Confirm the SFTP server key before continuing."
        } else {
            pendingHostingAction = nil
            status = error.localizedDescription
        }
    }

    func refreshBackups() {
        guard advancedUI, !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let destination = try hostingDestination(backup: true)
                backups = try await SitePublisher.withDirectory(destination) { try await HostingTools.backups($0) }
                status = "Completed backups in the private server directory."
            } catch { backups = []; hostingFailure(error, retry: .refreshBackups) }
        }
    }

    func exportCustomization() {
        guard advancedUI, ready, !busy, let store else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let destination = try hostingDestination()
                let files = try await Task.detached { try store.customizationFiles() }.value
                try await SitePublisher.withDirectory(destination, create: true) {
                    try await HostingTools.exportCustomization($0, files: files)
                }
                status = "Templates and website assets exported to hosting. Unrelated server files were kept."
            } catch { hostingFailure(error, retry: .exportCustomization) }
        }
    }

    func loadCustomization() {
        guard advancedUI, ready, !busy, let store else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let current = try JSONSerialization.jsonObject(with: Catalog.data(sources: store.templateSources()))
                let output = try await templateCall("templates", input: current)
                guard let expected = output["sources"] as? [String: String] else { throw ContentError.invalid("Cannot list templates.") }
                let paths = expected.keys.sorted()
                let destination = try hostingDestination()
                let customization = try await SitePublisher.withDirectory(destination) {
                    try await HostingTools.loadCustomization($0, sourcePaths: paths)
                }
                let sources = try JSONDecoder().decode([String: String].self, from: customization.sources)
                let loaded = try await templateCall("loadTemplates", input: ["catalog": current, "sources": sources, "project": project])
                guard let object = loaded["catalog"] else { throw ContentError.invalid("Cannot read templates.") }
                let updated = try JSONDecoder().decode(Catalog.self, from: JSONSerialization.data(withJSONObject: object))
                try await Task.detached { try store.saveCustomization(customization.sources, assets: customization.assets) }.value
                catalog = updated
                status = "Templates and website assets loaded and saved. Previews and publishing use them."
            } catch { hostingFailure(error, retry: .loadCustomization) }
        }
    }

    func createBackup() {
        guard advancedUI, ready, !busy, let store else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let destination = try hostingDestination(backup: true)
                let snapshot = try await Task.detached { try store.backupSnapshot() }.value
                defer { try? FileManager.default.removeItem(at: snapshot) }
                backups = try await SitePublisher.withDirectory(destination) {
                    _ = try await HostingTools.backup($0, snapshot: snapshot)
                    return try await HostingTools.backups($0)
                }
                status = "Backup created on hosting; older verified app-owned backups checked for cleanup."
            } catch { hostingFailure(error, retry: .createBackup) }
        }
    }

    func restoreBackup(_ backup: DirectoryTools.Backup) {
        guard advancedUI, ready, !busy, let store else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let destination = try hostingDestination(backup: true)
                let restoredSnapshot = try await SitePublisher.withDirectory(destination) {
                    try await HostingTools.restoreSnapshot($0, backup: backup)
                }
                defer { try? FileManager.default.removeItem(at: restoredSnapshot) }
                let (data, catalogData, published) = try await Task.detached {
                    try store.restoreSnapshot(restoredSnapshot)
                    return (try JSONSerialization.data(withJSONObject: store.project()),
                            try Catalog.data(sources: store.templateSources()), try store.publishedSnapshot())
                }.value
                guard let restored = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    throw ContentError.invalid("Cannot read restored project.")
                }
                pages = (restored["pages"] as? [[String: Any]] ?? []).map { ContentNode($0) }
                news = (restored["news"] as? [[String: Any]] ?? []).map { ContentNode($0) }
                media = restored["media"] as? [[String: Any]] ?? []
                homePageID = restored["homePageId"] as? String ?? pages.first?.id
                catalog = try JSONDecoder().decode(Catalog.self, from: catalogData)
                savedProject = data; publishedProject = published
                savedNodes = try snapshot(); savedHomePageID = homePageID
                automaticSlugs.removeAll(); revision += 1; restoreRevision += 1
                status = "Database, media and customizations restored. Hosting credentials unchanged."
            } catch { hostingFailure(error, retry: .restoreBackup(backup)) }
        }
    }

    func previewTemplate(_ id: String) {
        guard ready, !busy, let store else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let current = try JSONSerialization.jsonObject(with: Catalog.data(sources: store.templateSources()))
                let output = try await templateCall("previewTemplate", input: ["catalog": current, "id": id, "media": media])
                let data = try JSONSerialization.data(withJSONObject: output)
                let directory = try await Task.detached {
                    guard let output = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                          let files = output["files"] as? [[String: Any]], let references = output["referencedMedia"] as? [String] else {
                        throw ContentError.invalid("Cannot preview template.")
                    }
                    return try store.exportSite(files, references: references)
                }.value
                preview = NativePreview(root: directory, path: "index.html", temporary: true)
            } catch { status = error.localizedDescription }
        }
    }

    func pagePath(_ id: String) -> String? {
        func find(_ nodes: [ContentNode], _ prefix: String) -> String? {
            for node in nodes {
                let route = prefix + (node.fields["slug"] ?? "") + "/"
                if node.id == id { return route + "index.html" }
                if let result = find(node.children, route) { return result }
            }
            return nil
        }
        return find(pages, "")
    }
}

@MainActor
struct EditorView: View {
    @StateObject private var model = NativeEditorModel()
    @State private var selectedBackup: DirectoryTools.Backup?

    var body: some View {
        TabView {
            contentList(news: false).tabItem { Label("Pages", systemImage: "doc") }
            contentList(news: true).tabItem { Label("News", systemImage: "newspaper") }
            settings().tabItem { Label("Settings", systemImage: "gearshape") }
            ChangesView(model: model).tabItem { Label("Changes", systemImage: "list.bullet.rectangle") }
            HostingView(model: model).tabItem { Label("Hosting", systemImage: "network") }
        }
        .id(model.restoreRevision)
        .alert("Restore this backup?", isPresented: Binding(get: { selectedBackup != nil },
            set: { if !$0 { selectedBackup = nil } }), presenting: selectedBackup) { backup in
            Button("Restore", role: .destructive) { model.restoreBackup(backup) }
            Button("Cancel", role: .cancel) {}
        } message: { backup in
            Text(backup.directory.lastPathComponent + "\n" + ui("Replace the current database, media and website customizations with this backup? Unsaved edits will be discarded. Hosting credentials are unchanged."))
        }
        .sheet(item: $model.preview) { item in
            if item.path.isEmpty {
                ExportFolder(directory: item.root, finished: { model.preview = nil })
                    .onDisappear { try? FileManager.default.removeItem(at: item.root) }
            } else {
                NavigationStack {
                    WebsitePreview(path: item.path, resourceRoot: item.root)
                        .toolbar { Button("Done") { model.preview = nil } }
                }
                .onDisappear { if item.temporary { try? FileManager.default.removeItem(at: item.root) } }
            }
        }
        .alert("Trust SFTP server key?", isPresented: Binding(get: { model.pendingHostKey != nil },
            set: { if !$0 { model.pendingHostKey = nil; model.pendingHostingAction = nil } }), presenting: model.pendingHostKey) { key in
            Button(ui(model.pendingHostingAction != nil ? "Trust and continue" : model.hostKeyForTemplates ? "Trust and load" : "Trust and publish")) { model.trustHostKey(key) }
            Button("Cancel", role: .cancel) {}
        } message: { key in
            Text("\(key.host):\(key.port)\n\(key.fingerprint)\n" + ui("Compare this fingerprint with your server before trusting it. No password has been sent."))
        }
    }

    private func contentList(news isNews: Bool) -> some View {
        NavigationStack {
            List {
                Text(ui(model.status))
                if isNews { Button("Latest three news items") { model.site("news/index.html", export: false) } }
                ForEach(isNews ? model.news : model.pages) { node in
                    NavigationLink(node.fields["menuTitle"].flatMap { isNews ? nil : $0 } ?? node.fields["title"] ?? "Untitled") {
                        NativeContentForm(node: node, kind: isNews ? .news : .page, model: model)
                    }
                }
                .onDelete { offsets in
                    if isNews { model.news.remove(atOffsets: offsets) } else { model.pages.remove(atOffsets: offsets) }
                    model.changed()
                }
                .onMove { source, destination in
                    if isNews { model.news.move(fromOffsets: source, toOffset: destination) }
                    else { model.pages.move(fromOffsets: source, toOffset: destination) }
                    model.changed()
                }
                Button(ui(isNews ? "Add news" : "Add page")) {
                    let node = model.newNode(isNews ? .news : .page, siblings: isNews ? model.news : model.pages, rootPage: !isNews)
                    if isNews { model.news.append(node) } else { model.pages.append(node) }
                    model.changed()
                }
            }
            .navigationTitle(ui(isNews ? "News" : "Pages"))
            .toolbar {
                EditButton()
                Button("Save") { model.save() }.disabled(!model.ready || model.busy)
                Button("Export") { model.site(nil, export: true) }.disabled(!model.ready || model.busy)
            }
        }
    }

    private func settings() -> some View {
        NavigationStack {
            Form {
                Section("Editor mode") {
                    Picker("Editor mode", selection: $model.advancedUI) {
                        Text("Simple").tag(false)
                        Text("Advanced").tag(true)
                    }.pickerStyle(.segmented).disabled(model.busy)
                    Text("Simple hides sections, covers, template loading, and the public URL prefix. Advanced shows all options.")
                }
                Section {
                    NavigationLink("Templates") {
                        examples(title: "Templates")
                    }
                }
                if model.advancedUI {
                    Section("Advanced hosting tools") {
                        NavigationLink("Templates and website assets") { customizations() }
                        NavigationLink("Database and media backups") { backups() }
                    }
                }
            }
            .navigationTitle("Settings")
        }
    }

    private func examples(title: String) -> some View {
        List {
            Text(ui(model.status))
            if model.advancedUI {
                Button("Load templates from server") { model.loadServerTemplates() }.disabled(!model.ready || model.busy)
                Text("Export templates and assets explicitly to hosting from Advanced settings, then load the edited HTML here.")
            }
            if model.busy { ProgressView() }
            Section("Page templates") {
                ForEach(model.catalog?.templates ?? []) { entry in
                    NavigationLink(entry.title) { TemplateSourceView(entry: entry, model: model) }
                }
            }
            Section("Reusable partials") {
                ForEach(model.catalog?.components ?? []) { entry in
                    NavigationLink(entry.title) { TemplateSourceView(entry: entry, model: model) }
                }
            }
        }
        .navigationTitle(title)
    }

    private func customizations() -> some View {
        Form {
            Text(ui(model.status))
            Text("Export editable templates and public website assets to the configured hosting destination and upload subfolder. Edit them on the server, then load both back. Native editor resources cannot be changed.")
            Button("Export templates and assets to hosting") { model.exportCustomization() }
                .disabled(!model.advancedUI || !model.ready || model.busy || !model.hasHosting)
            Button("Load templates and assets from hosting") { model.loadCustomization() }
                .disabled(!model.advancedUI || !model.ready || model.busy || !model.hasHosting)
            Text("Normal website publishing exports required assets, not editable template-source copies.")
            if model.busy { ProgressView() }
        }.navigationTitle("Website customization")
    }

    private func backups() -> some View {
        List {
            Text(ui(model.status))
            Text("Back up saved SQLite content, customizations and all media on hosting in UTC datetime directories. Unsaved edits are not included.")
            LabeledTextField(title: "Private server backup directory", text: $model.backupDirectory)
                .textInputAutocapitalization(.never).autocorrectionDisabled().disabled(model.busy)
            Text("Use an existing absolute server directory outside the public website folder, with the same hosting credentials. Backups contain unpublished content; the app cannot verify your web server's access rules.")
            Button("Back up now") { model.createBackup() }
                .disabled(!model.advancedUI || !model.ready || model.busy || !model.hasHosting || model.backupDirectory.isEmpty)
            Button("Refresh backups") { model.refreshBackups() }
                .disabled(!model.advancedUI || model.busy || !model.hasHosting || model.backupDirectory.isEmpty)
            Text("After a successful backup, verified app-owned backups older than two weeks are removed. Unrelated or modified directories are kept.")
            ForEach(model.backups) { backup in
                Button(backup.directory.lastPathComponent) { selectedBackup = backup }.disabled(model.busy)
            }
            if model.busy { ProgressView() }
        }
        .navigationTitle("Database and media backups")
        .onChange(of: model.backupDirectory) { _, _ in selectedBackup = nil }
        .onAppear { if model.hasHosting && !model.backupDirectory.isEmpty { model.refreshBackups() } }
    }
}

@MainActor
struct LabeledTextField: View {
    let title: String
    @Binding var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(ui(title)).font(.headline)
            TextField("", text: $text)
                .accessibilityLabel(ui(title))
                .textFieldStyle(.roundedBorder)
        }
    }
}

@MainActor
private struct NativeContentForm: View {
    @ObservedObject var node: ContentNode
    let kind: ContentKind
    @ObservedObject var model: NativeEditorModel
    @State private var editingContent = false
    @State private var editingShort = false
    @State private var editingCover = false
    @State private var pickingCover = false
    @State private var coverOptions: NativeCoverOptions?
    @State private var confirmingBack = false
    @Environment(\.dismiss) private var dismiss

    private func back() {
        guard !model.busy else { return }
        if (kind == .page || kind == .news) && model.hasChanges(node) { confirmingBack = true }
        else { dismiss() }
    }

    private func text(_ key: String) -> Binding<String> {
        Binding(get: { node.fields[key] ?? "" }, set: { model.updateText(node, kind: kind, key: key, value: $0) })
    }
    private var date: Binding<Date> {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return Binding(get: { formatter.date(from: node.fields["date"] ?? "") ?? Date() },
                       set: { node.fields["date"] = formatter.string(from: $0); model.changed() })
    }

    var body: some View {
        Form {
            if kind == .page || kind == .news {
                LabeledTextField(title: "Title", text: text("title"))
                if kind == .page {
                    LabeledTextField(title: "Menu title", text: text("menuTitle"))
                    LabeledTextField(title: "Secondary title (optional)", text: text("subtitle"))
                    LabeledTextField(title: "Author (optional)", text: text("author"))
                    Picker("Page layout", selection: Binding(get: { node.fields["templateId"] ?? "page" },
                                                             set: { node.fields["templateId"] = $0; model.changed() })) {
                        ForEach(model.catalog?.templates.filter { $0.pageLayout == true } ?? []) { entry in
                            Text(ui(entry.title)).tag(entry.id)
                        }
                    }
                    Button(ui(model.homePageID == node.id ? "Current homepage" : "Use as homepage")) {
                        model.homePageID = node.id; model.changed()
                    }.disabled(model.homePageID == node.id)
                }
                LabeledTextField(title: "Slug", text: text("slug")).textInputAutocapitalization(.never).autocorrectionDisabled()
                DatePicker("Date", selection: date, displayedComponents: .date)
            } else {
                LabeledTextField(title: kind == .aside ? "Heading (optional)" : "Heading", text: text("heading"))
                if kind == .section { LabeledTextField(title: "Subheading (optional)", text: text("subheading")) }
            }
            Section(kind == .news ? "Short content" : "Content") {
                Button(ui(kind == .news ? "Edit short content" : "Edit content")) { editingShort = kind == .news; editingContent = true }
                if kind == .news { Text("Paragraphs and inline formatting only, including links.") }
            }
            if kind == .news {
                Section("Long content") {
                    Button("Edit long content") { editingShort = false; editingContent = true }
                }
            }
            if model.advancedUI && (kind == .page || kind == .news) {
                Section("Cover image (optional)") {
                    let coverID = node.fields["coverMediaId"] ?? ""
                    if !coverID.isEmpty { Text("Cover selected") }
                    Button(ui(coverID.isEmpty ? "Add cover image" : "Replace cover image")) {
                        coverOptions = nil; editingCover = true
                    }.disabled(model.busy)
                    if !coverID.isEmpty {
                        LabeledTextField(title: "Cover alternative text", text: text("coverAlt"))
                        Button("Remove cover image") { node.fields["coverMediaId"] = ""; node.fields["coverAlt"] = ""; model.changed() }
                            .disabled(model.busy)
                    }
                    Text("Covers must be at least 1200 pixels wide. Optional resizing and JPEG quality are available.")
                }
            }
            if model.advancedUI && (kind == .page || kind == .news) {
                Section("Sections") {
                    ForEach(node.sections) { section in
                        NavigationLink(section.fields["heading"] ?? "Section") { NativeContentForm(node: section, kind: .section, model: model) }
                    }
                    .onDelete { node.sections.remove(atOffsets: $0); model.changed() }
                    .onMove { node.sections.move(fromOffsets: $0, toOffset: $1); model.changed() }
                    Button("Add section") { node.sections.append(model.newNode(.section, siblings: node.sections)); model.changed() }
                }
            }
            if kind != .aside {
                Section("Asides") {
                    ForEach(node.asides) { aside in
                        NavigationLink(aside.fields["heading"].flatMap { $0.isEmpty ? nil : $0 } ?? "Aside") { NativeContentForm(node: aside, kind: .aside, model: model) }
                    }
                    .onDelete { node.asides.remove(atOffsets: $0); model.changed() }
                    .onMove { node.asides.move(fromOffsets: $0, toOffset: $1); model.changed() }
                    Button("Add aside") { node.asides.append(model.newNode(.aside, siblings: node.asides)); model.changed() }
                }
            }
            if kind == .page {
                Section("Subpages") {
                    ForEach(node.children) { child in
                        NavigationLink(child.fields["menuTitle"] ?? "Page") { NativeContentForm(node: child, kind: .page, model: model) }
                    }
                    .onDelete { node.children.remove(atOffsets: $0); model.changed() }
                    .onMove { node.children.move(fromOffsets: $0, toOffset: $1); model.changed() }
                    Button("Add subpage") { node.children.append(model.newNode(.page, siblings: node.children)); model.changed() }
                }
                Button("Preview page") { model.site(model.pagePath(node.id), export: false) }
            }
            if kind == .news {
                Button("Preview news") { model.site("news/\(node.fields["date"] ?? "")/\(node.fields["slug"] ?? "")/index.html", export: false) }
            }
            Text(ui(model.status))
        }
        .navigationTitle(node.fields[kind == .page || kind == .news ? "title" : "heading"] ?? "Content")
        .navigationBarBackButtonHidden(true)
        .alert("Save changes?", isPresented: $confirmingBack) {
            Button("Save") { model.save(afterSaved: { dismiss() }) }
                .disabled(!model.ready || model.busy)
            Button("Don't save", role: .destructive) { model.discard(node); dismiss() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Save changes before going back? Don't save discards changes to this item.")
        }
        .fullScreenCover(isPresented: $editingContent) {
            NavigationStack {
                NativeRichField(content: text(editingShort ? "shortContent" : "content"), model: model, paragraphsOnly: editingShort)
                    .navigationTitle(ui(editingShort ? "Short content" : kind == .news ? "Long content" : "Content"))
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        Button("Done") { editingContent = false }
                        Button("Save") { model.save() }.disabled(!model.ready || model.busy)
                    }
            }
        }
        .fullScreenCover(isPresented: $editingCover, onDismiss: { pickingCover = coverOptions != nil }) {
            NativeRichField.Coordinator.MediaDetailsView(kind: "image", cover: true) { details in
                if let details {
                    let options = details["imageOptions"] as? [String: Int] ?? [:]
                    coverOptions = NativeCoverOptions(basename: details["basename"] as? String ?? "", alt: details["alt"] as? String ?? "",
                        width: options["width"], quality: options["quality"])
                }
                editingCover = false
            }
        }
        .sheet(isPresented: $pickingCover) {
            if let coverOptions {
                NativeCoverPicker(model: model, options: coverOptions) { media, error in
                    if let media, let id = media["id"] as? String {
                        model.registerMedia(media)
                        node.fields["coverMediaId"] = id
                        node.fields["coverAlt"] = coverOptions.alt
                        model.changed()
                    } else if let error { model.status = error }
                    model.busy = false
                    pickingCover = false
                }
                .interactiveDismissDisabled(model.busy)
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(action: back) { Label("Back", systemImage: "chevron.left") }.disabled(model.busy)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                EditButton()
                Button("Save") { model.save() }.disabled(!model.ready || model.busy)
            }
        }
    }
}

@MainActor
private struct ExportFolder: UIViewControllerRepresentable {
    let directory: URL
    let finished: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(finished) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller = UIDocumentPickerViewController(forExporting: [directory], asCopy: true)
        controller.delegate = context.coordinator
        return controller
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let finished: () -> Void
        init(_ finished: @escaping () -> Void) { self.finished = finished }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { finished() }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finished() }
    }
}

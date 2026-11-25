import SwiftUI
import WebKit

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
    @Published var pages: [ContentNode] = []
    @Published var news: [ContentNode] = []
    @Published var status = "Opening local SQLite project..."
    @Published var ready = false
    @Published var busy = false
    @Published var preview: NativePreview?
    @Published var homePageID: String?
    @Published var pendingHostKey: HostKeyApproval?
    let store: ContentStore?
    let catalog: Catalog?
    private var media: [[String: Any]] = []
    private let generator = WKWebView()
    private var revision = 0
    private var savedNodes: [String: Data] = [:]
    private var savedHomePageID: String?

    override init() {
        let storageError: String?
        do {
            store = try ContentStore()
            storageError = nil
        } catch {
            store = nil
            storageError = error.localizedDescription
        }
        catalog = try? Catalog.load()
        super.init()
        do {
            guard let store else { throw ContentError.invalid(storageError ?? "Cannot open local SQLite storage.") }
            let project = try store.project()
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
        return "Saved hosting: \(connection["host"] as? String ?? ""). Not connected yet."
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
        return destination
    }

    func newNode(_ kind: ContentKind, siblings: [ContentNode]) -> ContentNode {
        let prefix = kind == .news ? "news" : "page"
        var number = siblings.count + 1
        while siblings.contains(where: { $0.fields["slug"] == "\(prefix)-\(number)" }) { number += 1 }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return ContentNode(["id": UUID().uuidString, "title": "New \(prefix)", "menuTitle": "New page",
                            "slug": "\(prefix)-\(number)", "date": formatter.string(from: Date()),
                            "heading": kind == .section ? "New section" : "", "content": "<p></p>", "shortContent": "<p></p>"])
    }

    private var project: [String: Any] {
        ["version": 1, "homePageId": homePageID.map { $0 as Any } ?? NSNull(), "pages": pages.map(\.json), "news": news.map(\.json), "media": media]
    }

    private func generate(_ compile: Bool, publicPathPrefix: String? = nil) async throws -> [String: Any] {
        guard let store else { throw ContentError.invalid("Storage unavailable.") }
        var input = project
        if compile {
            let catalogData = try Data(contentsOf: store.websiteRoot.appendingPathComponent("catalog.json"))
            input = ["project": input, "catalog": try JSONSerialization.jsonObject(with: catalogData)]
            if let publicPathPrefix { input["publicPathPrefix"] = publicPathPrefix }
        }
        let data = try JSONSerialization.data(withJSONObject: input)
        let serialized = String(decoding: data, as: UTF8.self)
        let argument = try JSONSerialization.data(withJSONObject: serialized, options: .fragmentsAllowed)
        let value = try await generator.evaluateJavaScript(
            "window.WebeasyGenerator.\(compile ? "compile" : "normalize")(\(String(decoding: argument, as: UTF8.self)))")
        guard let result = value as? String, let data = result.data(using: .utf8),
              let output = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ContentError.invalid("Cannot generate website content.")
        }
        if let error = output["error"] as? String { throw ContentError.invalid(error) }
        return output
    }

    func save(afterSaved: (() -> Void)? = nil) {
        guard !busy, ready, let store else { return }
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
                savedHomePageID = savedHome
                status = savedRevision == revision ? "Saved locally to SQLite." : "More recent changes remain unsaved."
                if savedRevision == revision { afterSaved?() }
            } catch { status = error.localizedDescription }
        }
    }

    func site(_ path: String?, export: Bool, publish: Bool = false) {
        guard !busy, ready, let store else { return }
        let destination = publish ? HostingCredentials.load().flatMap { try? JSONDecoder().decode(HostingDestination.self, from: $0) } : nil
        if publish && destination == nil { status = "Import your hosting setup QR first."; return }
        busy = true
        let savedRevision = revision
        Task {
            defer { busy = false }
            do {
                let nodes = try snapshot()
                let savedHome = homePageID
                let output = try await generate(true, publicPathPrefix: destination?.publicPathPrefix)
                let data = try JSONSerialization.data(withJSONObject: output)
                if export || publish {
                    let projectData = try JSONSerialization.data(withJSONObject: output["project"]!)
                    try await Task.detached {
                        guard let project = try JSONSerialization.jsonObject(with: projectData) as? [String: Any] else {
                            throw ContentError.invalid("Invalid project.")
                        }
                        try store.save(project)
                    }.value
                    savedNodes = nodes
                    savedHomePageID = savedHome
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
                    status = savedRevision == revision ? "Website published." : "Published snapshot excludes newer edits."
                } else if export {
                    preview = NativePreview(root: directory, path: "", temporary: true)
                    status = savedRevision == revision ? "Website ready to export." : "Exported snapshot excludes newer edits."
                } else {
                    preview = NativePreview(root: directory, path: path ?? "index.html", temporary: true)
                }
            } catch let approval as HostKeyApproval {
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
            site(nil, export: false, publish: true)
        } catch { status = error.localizedDescription }
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

    var body: some View {
        TabView {
            contentList(news: false).tabItem { Label("Pages", systemImage: "doc") }
            contentList(news: true).tabItem { Label("News", systemImage: "newspaper") }
            examples(model.catalog?.templates ?? [], title: "Templates").tabItem { Label("Templates", systemImage: "rectangle") }
            examples(model.catalog?.components ?? [], title: "Building blocks").tabItem { Label("Building blocks", systemImage: "square.grid.2x2") }
            HostingView(model: model).tabItem { Label("Hosting", systemImage: "network") }
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
    }

    private func contentList(news isNews: Bool) -> some View {
        NavigationStack {
            List {
                Text(model.status)
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
                Button(isNews ? "Add news" : "Add page") {
                    let node = model.newNode(isNews ? .news : .page, siblings: isNews ? model.news : model.pages)
                    if isNews { model.news.append(node) } else { model.pages.append(node) }
                    model.changed()
                }
            }
            .navigationTitle(isNews ? "News" : "Pages")
            .toolbar {
                EditButton()
                Button("Save") { model.save() }.disabled(!model.ready || model.busy)
                Button("Export") { model.site(nil, export: true) }.disabled(!model.ready || model.busy)
            }
        }
    }

    private func examples(_ entries: [CatalogEntry], title: String) -> some View {
        NavigationStack {
            List(entries) { entry in NavigationLink(entry.title) { WebsitePreview(path: entry.preview) } }
                .navigationTitle(title)
        }
    }
}

@MainActor
private struct NativeContentForm: View {
    @ObservedObject var node: ContentNode
    let kind: ContentKind
    @ObservedObject var model: NativeEditorModel
    @State private var editingContent = false
    @State private var confirmingBack = false
    @Environment(\.dismiss) private var dismiss

    private func back() {
        guard !model.busy else { return }
        if (kind == .page || kind == .news) && model.hasChanges(node) { confirmingBack = true }
        else { dismiss() }
    }

    private func text(_ key: String) -> Binding<String> {
        Binding(get: { node.fields[key] ?? "" }, set: { node.fields[key] = $0; model.changed() })
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
                TextField("Title", text: text("title"))
                if kind == .page {
                    TextField("Menu title", text: text("menuTitle"))
                    Picker("Page layout", selection: Binding(get: { node.fields["templateId"] ?? "page" },
                                                             set: { node.fields["templateId"] = $0; model.changed() })) {
                        ForEach(model.catalog?.templates.filter { $0.pageLayout == true } ?? []) { entry in
                            Text(entry.title).tag(entry.id)
                        }
                    }
                    Button(model.homePageID == node.id ? "Current homepage" : "Use as homepage") {
                        model.homePageID = node.id; model.changed()
                    }.disabled(model.homePageID == node.id)
                }
                TextField("Slug", text: text("slug")).textInputAutocapitalization(.never).autocorrectionDisabled()
                DatePicker("Date", selection: date, displayedComponents: .date)
            } else {
                TextField(kind == .aside ? "Heading (optional)" : "Heading", text: text("heading"))
            }
            Section(kind == .news ? "Short content" : "Content") {
                Button(kind == .news ? "Edit short content" : "Edit content") { editingContent = true }
            }
            if kind == .page || kind == .news {
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
            Text(model.status)
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
                NativeRichField(content: text(kind == .news ? "shortContent" : "content"), model: model)
                    .navigationTitle(kind == .news ? "Short content" : "Content")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        Button("Done") { editingContent = false }
                        Button("Save") { model.save() }.disabled(!model.ready || model.busy)
                    }
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

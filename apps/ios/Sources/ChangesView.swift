import SwiftUI

struct ProjectChange: Decodable, Identifiable {
    let id: String
    let kind: String?
    let title: String
    let status: String
    let fields: [Field]

    var isPageOrNews: Bool { kind == "Page" || kind == "News" }

    struct Field: Decodable {
        let name: String
        let before: String
        let after: String
    }
}

@MainActor
struct ChangesView: View {
    @ObservedObject var model: NativeEditorModel
    @State private var published = false
    @State private var changes: [ProjectChange] = []
    @State private var message = ""
    @State private var loading = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("Show changes", selection: $published) {
                        Text("Unsaved").tag(false)
                        Text("Unpublished").tag(true)
                    }
                    .pickerStyle(.segmented)
                    Text(published
                         ? "Current content compared with the last successful publish recorded on this device. This does not inspect the server."
                         : "Current content compared with the last local save.")
                    Text("Content changes only; hosting settings are not included.")
                        .foregroundStyle(.secondary)
                    if loading { ProgressView() }
                    if !message.isEmpty { Text(message) }
                }
                ForEach(changes) { change in
                    Section {
                        Text(change.status).font(.headline)
                        if change.isPageOrNews {
                            Text("ID: \(change.id)")
                                .font(.body.monospaced()).textSelection(.enabled)
                        }
                        ForEach(change.fields, id: \.name) { field in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(field.name).font(.headline)
                                if change.isPageOrNews && change.status != "Changed" {
                                    let value = change.status == "Added" ? field.after : field.before
                                    Text(value.isEmpty ? "(empty)" : value)
                                        .font(.body.monospaced()).textSelection(.enabled)
                                } else {
                                    Text("Before").foregroundStyle(.secondary)
                                    Text(field.before.isEmpty ? "(empty)" : field.before)
                                        .font(.body.monospaced()).textSelection(.enabled)
                                    Text("After").foregroundStyle(.secondary)
                                    Text(field.after.isEmpty ? "(empty)" : field.after)
                                        .font(.body.monospaced()).textSelection(.enabled)
                                }
                            }
                        }
                    } header: {
                        Text(change.title).textCase(nil)
                    }
                }
            }
            .navigationTitle("Show changes")
            .task(id: "\(published)-\(model.busy)-\(model.ready)") { await loadChanges() }
        }
    }

    private func loadChanges() async {
        changes = []
        message = ""
        loading = false
        guard model.ready, !model.busy else {
            message = "Wait for the current operation to finish."
            return
        }
        loading = true
        do {
            let result = try await model.changes(published: published)
            guard !Task.isCancelled else { return }
            changes = result ?? []
            message = result == nil
                ? "No published snapshot recorded yet. Publish successfully from this version to start tracking unpublished changes."
                : changes.isEmpty ? (published ? "No unpublished content changes." : "No unsaved content changes.") : ""
        } catch {
            guard !Task.isCancelled else { return }
            message = error.localizedDescription
        }
        loading = false
    }
}

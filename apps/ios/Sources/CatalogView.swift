import SwiftUI

struct CatalogView: View {
    private let result = Result { try Catalog.load() }

    var body: some View {
        switch result {
        case .success(let catalog):
            entries(catalog)
        case .failure(let error):
            ContentUnavailableView(
                "Website resources unavailable",
                systemImage: "exclamationmark.triangle",
                description: Text(error.localizedDescription)
            )
        }
    }

    private func entries(_ catalog: Catalog) -> some View {
        NavigationStack {
            List {
                Section("Page templates") {
                    ForEach(catalog.templates) { entry in catalogLink(entry) }
                }
                Section("Reusable partials") {
                    ForEach(catalog.components) { entry in catalogLink(entry) }
                }
            }
            .navigationTitle("Templates")
        }
    }

    private func catalogLink(_ entry: CatalogEntry) -> some View {
        NavigationLink {
            TemplateSourceView(entry: entry)
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                Text(entry.title).font(.headline)
                Text(entry.description).foregroundStyle(.secondary)
            }
            .padding(.vertical, 8)
        }
    }
}

struct TemplateSourceView: View {
    let entry: CatalogEntry
    var model: NativeEditorModel? = nil

    var body: some View {
        ScrollView {
            Text(entry.source)
                .font(.body.monospaced())
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .navigationTitle(entry.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let model {
                Button("Preview") { model.previewTemplate(entry.id) }.disabled(!model.ready || model.busy)
            } else {
                NavigationLink("Preview") { WebsitePreview(path: entry.preview) }
            }
        }
    }
}

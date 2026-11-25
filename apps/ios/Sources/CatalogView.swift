import SwiftUI

struct CatalogView: View {
    private let result = Result { try Catalog.load() }

    var body: some View {
        switch result {
        case .success(let catalog):
            entries(catalog.templates, title: "Templates")
        case .failure(let error):
            ContentUnavailableView(
                "Website resources unavailable",
                systemImage: "exclamationmark.triangle",
                description: Text(error.localizedDescription)
            )
        }
    }

    private func entries(_ entries: [CatalogEntry], title: String) -> some View {
        NavigationStack {
            List(entries) { entry in
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
            .navigationTitle(title)
        }
    }
}

struct TemplateSourceView: View {
    let entry: CatalogEntry

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
            NavigationLink("Preview") { WebsitePreview(path: entry.preview) }
        }
    }
}

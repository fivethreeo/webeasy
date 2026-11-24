import SwiftUI

struct CatalogView: View {
    private let result = Result { try Catalog.load() }

    var body: some View {
        switch result {
        case .success(let catalog):
            TabView {
                entries(catalog.templates, title: "Templates")
                    .tabItem { Label("Templates", systemImage: "doc.text") }
                entries(catalog.components, title: "Building blocks")
                    .tabItem { Label("Building blocks", systemImage: "square.grid.2x2") }
            }
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
                    WebsitePreview(path: entry.preview)
                        .navigationTitle(entry.title)
                        .navigationBarTitleDisplayMode(.inline)
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

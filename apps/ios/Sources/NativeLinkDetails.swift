import SwiftUI

@MainActor
struct LinkDetailsView: View {
    let pages: [[String: String]]
    let editing: Bool
    let textReadOnly: Bool
    let validate: (String, @escaping @MainActor (Bool) -> Void) -> Void
    let completion: ([String: Any]?) -> Void
    @State private var href: String
    @State private var text: String
    @State private var selectedPage: String
    @State private var checking = false
    @State private var finished = false
    @State private var error = ""

    init(details: [String: Any], validate: @escaping (String, @escaping @MainActor (Bool) -> Void) -> Void,
         completion: @escaping ([String: Any]?) -> Void) {
        let pages = details["pages"] as? [[String: String]] ?? []
        let href = details["href"] as? String ?? ""
        self.pages = pages
        editing = details["editing"] as? Bool ?? false
        textReadOnly = details["textReadOnly"] as? Bool ?? false
        self.validate = validate
        self.completion = completion
        _href = State(initialValue: href)
        _text = State(initialValue: details["text"] as? String ?? "")
        _selectedPage = State(initialValue: pages.contains { $0["href"] == href } ? href : "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Page or subpage", selection: $selectedPage) {
                        Text("Enter a URL").tag("")
                        ForEach(Array(pages.enumerated()), id: \.offset) { _, page in
                            Text(page["title"] ?? "").tag(page["href"] ?? "")
                        }
                    }
                    .onChange(of: selectedPage) { _, value in
                        if let page = pages.first(where: { $0["href"] == value }) {
                            href = value
                            if text.isEmpty { text = page["title"] ?? "" }
                            error = ""
                        }
                    }
                    LabeledTextField(title: "Link URL", text: Binding(get: { href }, set: {
                        href = $0; selectedPage = ""; error = ""
                    }))
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    LabeledTextField(title: "Link text", text: $text).disabled(textReadOnly)
                }
                .disabled(checking)
                if !error.isEmpty { Section { Text(ui(error)).foregroundStyle(.red) } }
                if editing {
                    Section { Button("Remove link", role: .destructive) { finish(["remove": true]) } }
                }
            }
            .navigationTitle(ui(editing ? "Edit link" : "Insert link"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { finish(nil) } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(ui(editing ? "Save link" : "Insert link"), action: submit).disabled(checking)
                }
            }
        }
    }

    private func finish(_ result: [String: Any]?) {
        guard !finished else { return }
        finished = true
        completion(result)
    }

    private func submit() {
        let url = href.trimmingCharacters(in: .whitespacesAndNewlines)
        let label = text.isEmpty ? url : text
        checking = true
        validate(url) { valid in
            checking = false
            if valid { finish(["href": url, "text": label]) }
            else { error = "Use a web, email, telephone, or local page URL." }
        }
    }
}

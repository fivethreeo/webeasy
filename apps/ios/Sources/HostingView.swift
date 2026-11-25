import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import CoreImage
import Security

enum HostingCredentials {
    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "no.webeasy.app",
         kSecAttrAccount as String: "hosting"]
    }
    static func save(_ data: Data) throws {
        let update = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else { throw ContentError.invalid("Cannot save hosting securely.") }
        let item = query.merging([kSecValueData as String: data,
                                 kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]) { _, new in new }
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else {
            throw ContentError.invalid("Cannot save hosting securely.")
        }
    }
    static func load() -> Data? {
        let item = query.merging([kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]) { _, new in new }
        var result: CFTypeRef?
        guard SecItemCopyMatching(item as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }
    static func remove() { SecItemDelete(query as CFDictionary) }
}

private enum QRImageSource: String, Identifiable {
    case picture, camera
    var id: String { rawValue }
}

@MainActor
struct HostingView: View {
    @ObservedObject var model: NativeEditorModel
    @State private var source: QRImageSource?
    @State private var status = "Import your hosting setup QR. Pictures are decoded on this phone, not uploaded."
    @State private var confirmingPublish = false
    @State private var publicPathPrefix = ""
    @State private var uploadSubfolder = ""
    @State private var savedPublicPathPrefix = ""
    @State private var savedUploadSubfolder = ""

    private var settingsChanged: Bool {
        publicPathPrefix != savedPublicPathPrefix || uploadSubfolder != savedUploadSubfolder
    }

    private func loadSettings(_ destination: HostingDestination) {
        publicPathPrefix = destination.publicPathPrefix ?? ""
        uploadSubfolder = destination.uploadSubfolder ?? ""
        savedPublicPathPrefix = publicPathPrefix
        savedUploadSubfolder = uploadSubfolder
    }

    var body: some View {
        NavigationStack {
            Form {
                Text(status)
                Button("Read QR from picture") { source = .picture }.disabled(model.busy)
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button("Take a QR picture") { source = .camera }.disabled(model.busy)
                }
                Button("Forget hosting", role: .destructive) {
                    HostingCredentials.remove()
                    publicPathPrefix = ""; uploadSubfolder = ""
                    savedPublicPathPrefix = ""; savedUploadSubfolder = ""
                    status = "Hosting credentials removed."
                }
                .disabled(model.busy)
                Section("Publishing paths") {
                    TextField("Public URL prefix (optional)", text: $publicPathPrefix)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Upload subfolder (optional)", text: $uploadSubfolder)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Text("Use / for a website at the domain root, or /site/ for a website under that path. The upload subfolder is relative to the QR's destination; leave it blank to publish directly there. A blank public prefix uses relative website links.")
                    Button("Save publishing settings") {
                        Task {
                            do {
                                loadSettings(try await model.savePublishingSettings(
                                    publicPathPrefix: publicPathPrefix, uploadSubfolder: uploadSubfolder))
                                status = "Publishing settings saved."
                            } catch { status = error.localizedDescription }
                        }
                    }.disabled(!settingsChanged || !model.ready || model.busy)
                }.disabled(model.busy)
                if settingsChanged { Text("Save publishing settings before publishing.") }
                Button("Publish website") { confirmingPublish = true }.disabled(!model.ready || model.busy || settingsChanged)
                if model.busy { ProgressView() }
                Text(model.status)
                Text("Credentials and trusted SFTP keys are kept in Keychain. FTPS validates the server certificate; SFTP requires a trusted server key.")
            }
            .navigationTitle("Hosting")
            .alert("Publish website?", isPresented: $confirmingPublish) {
                Button("Publish") { model.site(nil, export: false, publish: true) }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Save and upload the website to your saved destination. Matching files are replaced; unrelated files and old pages are kept. Plain FTP sends your password and content without encryption.")
            }
            .alert("Trust SFTP server key?", isPresented: Binding(get: { model.pendingHostKey != nil },
                set: { if !$0 { model.pendingHostKey = nil } }), presenting: model.pendingHostKey) { key in
                Button("Trust and publish") { model.trustHostKey(key) }
                Button("Cancel", role: .cancel) {}
            } message: { key in
                Text("\(key.host):\(key.port)\n\(key.fingerprint)\nCompare this fingerprint with your server before trusting it. No password has been sent.")
            }
            .task {
                if let data = HostingCredentials.load(),
                   let destination = try? JSONDecoder().decode(HostingDestination.self, from: data) {
                    if !settingsChanged { loadSettings(destination) }
                    status = "Saved hosting: \(destination.host)."
                }
            }
            .sheet(item: $source) { source in
                QRPicturePicker(source: source) { result in
                    self.source = nil
                    switch result {
                    case .success(let code):
                        Task {
                            do {
                                status = try await model.importHostingQR(code)
                                if let data = HostingCredentials.load(),
                                   let destination = try? JSONDecoder().decode(HostingDestination.self, from: data) {
                                    loadSettings(destination)
                                }
                            }
                            catch { status = error.localizedDescription }
                        }
                    case .failure(let error): status = error.localizedDescription
                    }
                }
            }
        }
    }
}

@MainActor
private struct QRPicturePicker: UIViewControllerRepresentable {
    let source: QRImageSource
    let completed: (Result<String, Error>) -> Void
    @Environment(\.dismiss) private var dismiss
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> UIViewController {
        if source == .camera {
            let picker = UIImagePickerController()
            picker.sourceType = .camera
            picker.delegate = context.coordinator
            return picker
        }
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = 1
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, PHPickerViewControllerDelegate, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: QRPicturePicker
        init(_ parent: QRPicturePicker) { self.parent = parent }
        private func read(_ image: UIImage) {
            guard let image = CIImage(image: image),
                  let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil,
                                            options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]) else {
                parent.completed(.failure(ContentError.invalid("Cannot read this picture.")))
                return
            }
            let codes = detector.features(in: image).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
            guard codes.count == 1, let code = codes.first else {
                parent.completed(.failure(ContentError.invalid("Choose a picture containing one Webeasy hosting setup QR.")))
                return
            }
            parent.completed(.success(code))
        }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let provider = results.first?.itemProvider else { parent.dismiss(); return }
            let identifier = provider.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: .image) == true } ?? UTType.image.identifier
            provider.loadDataRepresentation(forTypeIdentifier: identifier) { [weak self] data, _ in
                DispatchQueue.main.async {
                    guard let self else { return }
                    guard let data, let image = UIImage(data: data) else {
                        self.parent.completed(.failure(ContentError.invalid("Cannot read this picture.")))
                        return
                    }
                    self.read(image)
                }
            }
        }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { read(image) }
            else { parent.completed(.failure(ContentError.invalid("Cannot read this picture."))) }
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}

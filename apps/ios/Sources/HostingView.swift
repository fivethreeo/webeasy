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

    var body: some View {
        NavigationStack {
            Form {
                Text(status)
                Button("Read QR from picture") { source = .picture }
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button("Take a QR picture") { source = .camera }
                }
                Button("Forget hosting", role: .destructive) {
                    HostingCredentials.remove()
                    status = "Hosting credentials removed."
                }
                Text("Credentials are kept in Keychain. Connecting and publishing from the app are not implemented yet.")
            }
            .navigationTitle("Hosting")
            .task {
                if let data = HostingCredentials.load(),
                   let connection = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    status = "Saved hosting: \(connection["host"] as? String ?? "")."
                }
            }
            .sheet(item: $source) { source in
                QRPicturePicker(source: source) { result in
                    self.source = nil
                    switch result {
                    case .success(let code):
                        Task {
                            do { status = try await model.importHostingQR(code) }
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

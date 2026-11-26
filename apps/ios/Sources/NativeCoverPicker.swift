import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct NativeCoverOptions: Sendable {
    let basename: String
    let alt: String
    let width: Int?
    let quality: Int?
}

@MainActor
struct NativeCoverPicker: UIViewControllerRepresentable {
    let model: NativeEditorModel
    let options: NativeCoverOptions
    let completion: ([String: Any]?, String?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 1
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ picker: PHPickerViewController, context: Context) { context.coordinator.parent = self }

    @MainActor
    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        var parent: NativeCoverPicker
        init(_ parent: NativeCoverPicker) { self.parent = parent }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let provider = results.first?.itemProvider, let store = parent.model.store else {
                parent.completion(nil, nil)
                return
            }
            parent.model.busy = true
            let options = parent.options
            let identifier = provider.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: .image) == true } ?? UTType.image.identifier
            provider.loadFileRepresentation(forTypeIdentifier: identifier) { [weak self] url, error in
                let data: Data?
                let failure: String?
                do {
                    guard let url else { throw ContentError.invalid(error?.localizedDescription ?? "Cannot read cover image.") }
                    let media = try store.importMedia(url, kind: "image", typeIdentifier: identifier,
                        basename: options.basename, width: options.width, quality: options.quality, minimumWidth: 1200)
                    data = try JSONSerialization.data(withJSONObject: media)
                    failure = nil
                } catch {
                    data = nil
                    failure = error.localizedDescription
                }
                DispatchQueue.main.async {
                    guard let self else { return }
                    let media = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
                    self.parent.completion(media, failure)
                }
            }
        }
    }
}

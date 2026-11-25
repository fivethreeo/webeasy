import SwiftUI
import WebKit
import PhotosUI
import UniformTypeIdentifiers

@MainActor
struct NativeRichField: UIViewRepresentable {
    @Binding var content: String
    let model: NativeEditorModel

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.userContentController.add(context.coordinator, name: "webeasy")
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.scrollView.isScrollEnabled = true
        view.navigationDelegate = context.coordinator
        context.coordinator.view = view
        if let store = model.store { view.loadFileURL(store.websiteRoot.appendingPathComponent("field.html"), allowingReadAccessTo: store.websiteRoot) }
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
        if context.coordinator.ready && context.coordinator.value != content { context.coordinator.setValue(content) }
    }
    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        view.configuration.userContentController.removeScriptMessageHandler(forName: "webeasy")
        view.navigationDelegate = nil
    }

    @MainActor
    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate, PHPickerViewControllerDelegate {
        var parent: NativeRichField
        weak var view: WKWebView?
        var ready = false
        var value = ""
        private var pending: (String, String, String)?
        init(_ parent: NativeRichField) { self.parent = parent }

        func setValue(_ content: String) {
            value = content
            guard let data = try? JSONSerialization.data(withJSONObject: content, options: .fragmentsAllowed) else { return }
            view?.evaluateJavaScript("window.WebeasyField.setValue(\(String(decoding: data, as: UTF8.self)));")
        }
        private nonisolated static func response(_ id: String, _ result: Any = NSNull(), _ error: String? = nil) -> String {
            var body: [String: Any] = ["id": id, "result": result]
            if let error { body["error"] = error }
            let data = (try? JSONSerialization.data(withJSONObject: body)) ?? Data("{}".utf8)
            return String(decoding: data, as: UTF8.self)
        }
        private func send(_ id: String, _ result: Any = NSNull(), _ error: String? = nil) {
            view?.evaluateJavaScript("window.WebeasyBridge.receive(\(Self.response(id, result, error)));")
        }
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let root = parent.model.store?.websiteRoot, let url = action.request.url, url.isFileURL,
                  url.resolvingSymlinksInPath().path.hasPrefix(root.resolvingSymlinksInPath().path + "/") else { decisionHandler(.cancel); return }
            decisionHandler(.allow)
        }
        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.frameInfo.isMainFrame, let root = parent.model.store?.websiteRoot,
                  let url = message.frameInfo.request.url, url.isFileURL,
                  url.resolvingSymlinksInPath().path.hasPrefix(root.resolvingSymlinksInPath().path + "/"),
                  let serialized = message.body as? String, let data = serialized.data(using: .utf8),
                  let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let id = body["id"] as? String else { return }
            let payload = body["payload"] as? [String: Any] ?? [:]
            switch body["method"] as? String {
            case "ready": ready = true; setValue(parent.content); send(id)
            case "change":
                if let content = payload["content"] as? String { value = content; parent.content = content }
                send(id)
            case "resize": send(id)
            case "pageLinks":
                send(id, ["pages": parent.model.pages.map(\.json),
                          "homePageId": parent.model.homePageID.map { $0 as Any } ?? NSNull()])
            case "pickMedia":
                guard pending == nil, let kind = payload["kind"] as? String, ["image", "video"].contains(kind),
                      let rootController = view?.window?.rootViewController else { send(id, NSNull(), "Cannot open media picker."); return }
                var presenter = rootController
                while let presented = presenter.presentedViewController { presenter = presented }
                pending = (id, kind, payload["basename"] as? String ?? "")
                var config = PHPickerConfiguration()
                config.filter = kind == "image" ? .images : .videos
                config.selectionLimit = 1
                config.preferredAssetRepresentationMode = .current
                let picker = PHPickerViewController(configuration: config)
                picker.delegate = self
                presenter.present(picker, animated: true)
            default: send(id, NSNull(), "Unknown content-field operation.")
            }
        }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let pending else { picker.dismiss(animated: true); return }
            self.pending = nil
            let store = parent.model.store
            picker.dismiss(animated: true) { [weak self] in
                guard let self else { return }
                guard let provider = results.first?.itemProvider, let store else { self.send(pending.0); return }
                let type = pending.1 == "image" ? UTType.image : UTType.movie
                let identifier = provider.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: type) == true } ?? type.identifier
                provider.loadFileRepresentation(forTypeIdentifier: identifier) { [weak self] url, error in
                    let serialized: String
                    do {
                        guard let url else { throw ContentError.invalid(error?.localizedDescription ?? "Cannot read media.") }
                        let media = try store.importMedia(url, kind: pending.1, typeIdentifier: identifier, basename: pending.2)
                        serialized = Self.response(pending.0, media)
                    } catch { serialized = Self.response(pending.0, NSNull(), error.localizedDescription) }
                    DispatchQueue.main.async {
                        guard let self else { return }
                        if let data = serialized.data(using: .utf8), let message = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                           let media = message["result"] as? [String: Any] { self.parent.model.registerMedia(media) }
                        self.view?.evaluateJavaScript("window.WebeasyBridge.receive(\(serialized));")
                    }
                }
            }
        }
    }
}

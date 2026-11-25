import SwiftUI
import WebKit

struct WebsitePreview: UIViewRepresentable {
    let path: String
    var resourceRoot: URL? = nil

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView()
        view.navigationDelegate = context.coordinator
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        guard let root = resourceRoot ?? Bundle.main.url(forResource: "Website", withExtension: nil) else { return }
        let url = root.appendingPathComponent(path).standardizedFileURL
        guard url.resolvingSymlinksInPath().path.hasPrefix(root.resolvingSymlinksInPath().path + "/") else { return }
        context.coordinator.root = root.resolvingSymlinksInPath()
        context.coordinator.homePath = resourceRoot == nil ? "home.html" : "index.html"
        if context.coordinator.loadedPath != path {
            context.coordinator.loadedPath = path
            view.loadFileURL(url, allowingReadAccessTo: root)
        }
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        var root: URL?
        var loadedPath: String?
        var homePath = "index.html"

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url, let root else { decisionHandler(.cancel); return }
            if navigationAction.navigationType == .linkActivated {
                if ["https", "http", "mailto", "tel"].contains(url.scheme ?? "") {
                    decisionHandler(.cancel)
                    UIApplication.shared.open(url)
                    return
                }
                if url.isFileURL && url.path == "/" {
                    decisionHandler(.cancel)
                    webView.loadFileURL(root.appendingPathComponent(homePath), allowingReadAccessTo: root)
                    return
                }
            }
            if url.isFileURL && url.resolvingSymlinksInPath().path == root.path {
                decisionHandler(.cancel)
                webView.loadFileURL(root.appendingPathComponent(homePath), allowingReadAccessTo: root)
                return
            }
            guard url.isFileURL,
                  url.resolvingSymlinksInPath().path.hasPrefix(root.path + "/") else {
                decisionHandler(.cancel)
                return
            }
            if url.hasDirectoryPath {
                decisionHandler(.cancel)
                webView.loadFileURL(url.appendingPathComponent("index.html"), allowingReadAccessTo: root)
            } else { decisionHandler(.allow) }
        }
    }
}

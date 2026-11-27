import Foundation

func ui(_ text: String, bundle: Bundle = .main) -> String {
    bundle.localizedString(forKey: text, value: text, table: nil)
}

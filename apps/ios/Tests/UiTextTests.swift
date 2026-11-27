import Foundation

@main
struct UiTextTests {
    static func main() {
        let english = Bundle(path: "apps/ios/Sources/en.lproj")!
        let norwegian = Bundle(path: "apps/ios/Sources/nb.lproj")!
        precondition(ui("Pages", bundle: english) == "Pages")
        precondition(ui("Pages", bundle: norwegian) == "Sider")
        precondition(ui("Save", bundle: norwegian) == "Lagre")
        precondition(ui("My own title", bundle: norwegian) == "My own title")
        print("English and Bokmål interface localization tests passed.")
    }
}

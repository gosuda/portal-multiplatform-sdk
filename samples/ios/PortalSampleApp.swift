import SwiftUI

@main
struct PortalSampleApp: App {
    private let contents: SampleContents
    private let identityPath: String

    init() {
        let bundle = Bundle.main
        let siteDir = bundle.resourceURL!.appendingPathComponent("site").path
        let explainerDir = bundle.resourceURL!.appendingPathComponent("site-explainer").path
        contents = SampleContents(siteDir: siteDir, explainerDir: explainerDir)
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        identityPath = docs.appendingPathComponent("identity.json").path
    }

    var body: some Scene {
        WindowGroup {
            PortalHomeView(contents: contents, identityPath: identityPath)
        }
    }
}

import SwiftUI

@main
@MainActor
struct PortalSampleApp: App {
    // The app, not a navigation destination, owns the SDK client and demos.
    @StateObject private var model = PortalHomeModel()

    var body: some Scene {
        WindowGroup {
            PortalHomeView(model: model)
        }
    }
}

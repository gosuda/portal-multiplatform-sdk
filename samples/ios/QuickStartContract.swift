import Foundation
import PortalSDK

/// Compile-only contract for the documented iOS quick start.
///
/// This file is never invoked by the sample UI; it exists so a public API
/// change that breaks the documented easy path fails compilation instead of
/// silently drifting from the README. The publish body below is the
/// ≤15-app-code-line budget for exposing a loopback HTTP server.
final class QuickStartContract {

    private let client = PortalIosClient(allowRemoteTargets: false, defaultIdentityPath: nil)
    private var operation: PortalOperation?

    /// Publishes an HTTP server already listening on 127.0.0.1:8080.
    func publishLocalHttpServer() {
        let config = PortalIosConfigFactory.shared.http(
            targetAddress: "127.0.0.1:8080",
            name: "device-api"
        )
        operation = client.publish(config: config, timeoutMillis: 30_000) { session, failure in
            if let failure = failure {
                NSLog("portal publish failed: %@ %@", failure.code, failure.message)
                return
            }
            NSLog("portal public URL: %@", session?.snapshot.primaryPublicUrl ?? "pending")
        }
    }
}

import Foundation
import PortalSDK

/// Compile-only contract for the advanced low-level path.
///
/// The sample's primary flow uses `PortalIosConfigFactory` and `publish`;
/// this fixture keeps the raw `PortalConfig` initializer, `open`
/// (accepted-before-ready), `awaitReady`, and `observeState` compiled so the
/// escape hatch cannot drift from the exported API.
final class AdvancedContract {

    private let client = PortalIosClient(allowRemoteTargets: false, defaultIdentityPath: nil)
    private var operation: PortalOperation?
    private var subscription: PortalSubscription?

    /// Opens a tunnel without waiting for readiness: the caller observes
    /// CONNECTING through `observeState` and awaits a capability itself.
    func openAndObserve() {
        let config = PortalConfig(
            name: "advanced-site",
            identityJson: nil,
            identityPath: nil,
            relays: nil,
            discovery: KotlinBoolean(bool: true),
            maxActiveRelays: 3,
            banMitm: false, ech: false, overlay: false,
            udp: false, tcp: false,
            description: nil, tags: nil, owner: nil, thumbnail: nil, hide: false,
            staticDir: "/data/site", staticIndex: "index.html",
            targetAddr: nil, udpAddr: nil, httpRoutes: nil, x402: nil
        )
        operation = client.open(config: config) { [weak self] session, failure in
            guard let self else { return }
            if let failure {
                NSLog("portal open failed: %@ %@", failure.code, failure.message)
                return
            }
            subscription = session?.observeState { snapshot in
                NSLog("portal %@ %@", "\(snapshot.phase)", snapshot.primaryPublicUrl ?? "")
            }
            session?.awaitReady(capability: .staticSite, timeoutMillis: 15_000) { _, failure in
                if let failure { NSLog("awaitReady failed: %@", failure.code) }
            }
        }
    }
}

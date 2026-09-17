import SwiftUI
import PortalSDK

/// Minimal SwiftUI sample driving the KMP SDK through the callback facade.
/// The client outlives the view; observation cancel != tunnel stop.
@MainActor
final class PortalHomeModel: ObservableObject {
    @Published var phase: String = "idle"
    @Published var publicUrl: String = ""
    @Published var relays: String = ""
    @Published var warning: Bool = false

    private let client = PortalIosClient()
    private var session: PortalIosSession?
    private var observation: PortalSubscription?
    private var startOp: PortalOperation?

    func start(siteDir: String) {
        let config = PortalConfig(
            name: "ios-kmp-sample",
            identityJson: nil, identityPath: nil,
            relays: nil, discovery: true, maxActiveRelays: 2,
            banMitm: false, ech: false, overlay: false, udp: false, tcp: false,
            description: "Portal KMP iOS sample", tags: nil, owner: nil,
            thumbnail: nil, hide: false,
            staticDir: siteDir, staticIndex: "index.html",
            targetAddr: nil, udpAddr: nil, httpRoutes: nil, x402: nil
        )
        startOp = client.open(config: config) { [weak self] session, failure in
            guard let self else { return }
            if let failure {
                self.phase = "failed: \(failure.code)"
                return
            }
            self.session = session
            self.observation = session?.observeState { [weak self] s in
                self?.phase = "\(s.phase)"
                self?.publicUrl = s.primaryPublicUrl ?? ""
                self?.warning = s.hasSecurityWarning
                self?.relays = s.relays
                    .map { "\($0.relayUrl) [\($0.state)]" }
                    .joined(separator: "\n")
            }
        }
    }

    func stop() {
        session?.stop { [weak self] failure in
            if let failure { self?.phase = "stop failed: \(failure.code)" }
        }
    }

    func onDisappear() {
        observation?.cancel()   // stop observing; tunnel keeps running
        observation = nil
    }
}

struct PortalHomeView: View {
    @StateObject private var model = PortalHomeModel()
    let siteDir: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Button("Start") { model.start(siteDir: siteDir) }
                Button("Stop") { model.stop() }
            }
            Text("phase: \(model.phase)")
            if model.warning { Text("SECURITY WARNING").foregroundColor(.red) }
            Text(model.publicUrl).font(.footnote)
            Text(model.relays).font(.caption)
        }
        .padding()
        .onDisappear { model.onDisappear() }
    }
}

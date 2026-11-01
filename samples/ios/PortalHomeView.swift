import SwiftUI
import UIKit
import PortalSDK

@MainActor
final class PortalHomeModel: ObservableObject {
    @Published var mode: WorkbenchMode = .staticSite
    @Published var builtInDemo = true
    @Published var advancedSettings = false
    @Published var name = "ios-workbench"
    @Published var target = "127.0.0.1:8080"
    @Published var prefixOne = "/api"
    @Published var upstreamOne = "http://127.0.0.1:8080"
    @Published var prefixTwo = "/hooks"
    @Published var upstreamTwo = "http://127.0.0.1:8081"
    @Published var relayInput = ""
    @Published var discovery = true
    @Published var banMitm = false
    @Published var hide = false
    @Published var metaDescription = "Published from the iOS SDK workbench"
    @Published var metaTags = "sdk,ios"
    @Published var metaOwner = ""
    @Published var newRelay = ""
    @Published private(set) var snapshot: PortalSnapshot?
    @Published private(set) var lastError: String?
    @Published private(set) var events: [String] = []
    @Published private(set) var requests: [String] = []
    @Published private(set) var receivedCount = 0
    @Published private(set) var diagnostics: PortalDiagnostics?
    @Published private(set) var starting = false
    @Published private(set) var stopping = false

    private let client = PortalIosClient()
    private var session: PortalIosSession?
    private var stateSubscription: PortalSubscription?
    private var eventSubscription: PortalSubscription?
    private var payload: DemoPayload?
    private var attempt = UUID()

    var running: Bool { snapshot.map { !$0.isTerminal } ?? false }
    var editable: Bool { !starting && !stopping && !running }
    var operable: Bool { running && !starting && !stopping && snapshot?.phase != .stopping }
    var missingRelay: Bool { advancedSettings && !discovery && values(relayInput).isEmpty }
    var urls: [String] { running ? snapshot?.publicUrls ?? [] : [] }
    var localEndpoints: [String] { payload?.addresses ?? [] }

    func start() {
        guard editable else { return }
        let id = UUID()
        attempt = id
        lastError = nil
        events = []
        requests = []
        receivedCount = 0
        snapshot = nil
        detach()
        session = nil
        starting = true
        let localPayload = DemoPayload(mode: builtInDemo ? mode : .staticSite) { [weak self] message in
            guard let self, self.attempt == id else { return }
            self.receivedCount += 1
            self.requests.append(message)
            if self.requests.count > 40 { self.requests.removeFirst(self.requests.count - 40) }
        }
        payload = localPayload
        localPayload.start { [weak self] error in
            guard let self, self.attempt == id else { return }
            if let error {
                self.lastError = "Local demo could not start: \(error.localizedDescription)"
                self.starting = false
                self.releasePayload()
                return
            }
            do {
                let config = try self.makeConfig()
                _ = self.client.publish(config: config) { [weak self] session, failure in
                    MainActor.assumeIsolated {
                        guard let self, self.attempt == id else { return }
                        self.starting = false
                        if let session { self.adopt(session) }
                        else if let retained = self.client.sessions.first { self.adopt(retained) }
                        if let failure { self.lastError = self.describe(failure) }
                        else if self.session == nil { self.lastError = "SDK publish returned neither a session nor a failure." }
                        if self.session?.snapshot.isTerminal != false { self.releasePayload() }
                    }
                }
            } catch {
                self.lastError = error.localizedDescription
                self.starting = false
                self.releasePayload()
            }
        }
    }

    func stop() {
        guard !stopping, let session else { return }
        if session.snapshot.isTerminal { apply(session.snapshot); return }
        stopping = true
        session.stop { [weak self] failure in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.stopping = false
                self.starting = false
                if let snapshot = self.session?.snapshot { self.apply(snapshot) }
                if let failure {
                    self.lastError = "Stop failed; session and local demo are retained. Retry Stop.\n\(self.describe(failure))"
                }
                self.loadDiagnostics()
            }
        }
    }

    func refresh() {
        session?.refresh { [weak self] failure in
            MainActor.assumeIsolated {
                guard let self, let session = self.session else { return }
                self.apply(session.snapshot)
                if let failure { self.lastError = self.describe(failure) }
            }
        }
    }

    func updateMetadata() {
        let metadata = PortalMetadata(description: nonempty(metaDescription), tags: optionalValues(metaTags),
                                      owner: nonempty(metaOwner), thumbnail: nil, hide: KotlinBoolean(bool: hide))
        session?.updateMetadata(metadata: metadata) { [weak self] failure in
            MainActor.assumeIsolated {
                if let failure { self?.lastError = self?.describe(failure) }
            }
        }
    }

    func addRelay() {
        guard let relay = nonempty(newRelay), let session else { return }
        session.addRelay(relayUrl: relay) { [weak self] failure in
            MainActor.assumeIsolated {
                guard let self else { return }
                if let failure { self.lastError = self.describe(failure) }
                else { self.newRelay = "" }
                if let snapshot = self.session?.snapshot { self.apply(snapshot) }
            }
        }
    }

    func removeRelay(_ relay: String) {
        session?.removeRelay(relayUrl: relay) { [weak self] failure in
            MainActor.assumeIsolated {
                guard let self else { return }
                if let failure { self.lastError = self.describe(failure) }
                if let snapshot = self.session?.snapshot { self.apply(snapshot) }
            }
        }
    }

    func loadDiagnostics() { diagnostics = client.diagnostics() }

    private func makeConfig() throws -> PortalConfig {
        func address() throws -> String {
            guard builtInDemo else { return target.trimmingCharacters(in: .whitespacesAndNewlines) }
            guard let address = payload?.addresses.first else {
                throw NSError(domain: "PortalSample", code: 2, userInfo: [NSLocalizedDescriptionKey: "Local demo endpoint is not ready."])
            }
            return address
        }
        let factory = PortalIosConfigFactory.shared
        let base: PortalConfig
        switch mode {
        case .staticSite:
            guard let directory = Bundle.main.url(forResource: "site", withExtension: nil) else {
                throw NSError(domain: "PortalSample", code: 1, userInfo: [NSLocalizedDescriptionKey: "Bundled site folder is missing. Check Xcode resource configuration."])
            }
            base = factory.staticSite(directory: directory.path)
        case .http: base = factory.http(targetAddress: try address())
        case .tcp: base = factory.tcp(targetAddress: try address())
        case .udp: base = factory.udp(targetAddress: try address())
        case .routes:
            if builtInDemo {
                guard let addresses = payload?.addresses, addresses.count == 2 else {
                    throw NSError(domain: "PortalSample", code: 2, userInfo: [NSLocalizedDescriptionKey: "Both local route endpoints must be ready."])
                }
                base = factory.routes(routes: [
                    factory.httpRoute(prefix: "/api", upstream: "http://\(addresses[0])"),
                    factory.httpRoute(prefix: "/hooks", upstream: "http://\(addresses[1])")
                ])
            } else {
                base = factory.routes(routes: [route(prefix: prefixOne, upstream: upstreamOne),
                                                route(prefix: prefixTwo, upstream: upstreamTwo)])
            }
        }
        guard advancedSettings else { return base }
        return factory.custom(
            name: nonempty(name), identityJson: nil, identityPath: nil,
            relays: optionalValues(relayInput), discovery: discovery, maxActiveRelays: 3,
            banMitm: banMitm, ech: false, udp: base.udp, tcp: base.tcp,
            description: nonempty(metaDescription), tags: optionalValues(metaTags),
            owner: nonempty(metaOwner), thumbnail: nil, hide: hide,
            staticDir: base.staticDir, staticIndex: base.staticIndex,
            targetAddr: base.targetAddr, udpAddr: base.udpAddr, httpRoutes: base.httpRoutes, x402: nil)
    }

    private func route(prefix: String, upstream: String) -> PortalHTTPRoute {
        PortalIosConfigFactory.shared.httpRoute(
            prefix: prefix.trimmingCharacters(in: .whitespacesAndNewlines),
            upstream: upstream.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    private func adopt(_ session: PortalIosSession) {
        detach()
        self.session = session
        apply(session.snapshot)
        // The SDK guarantees Main delivery; assert that boundary without
        // adding a second asynchronous hop that could reorder session state.
        stateSubscription = session.observeState { [weak self] snapshot in
            MainActor.assumeIsolated { self?.apply(snapshot) }
        }
        eventSubscription = session.observeEvents { [weak self] event in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.events.append(self.describeEvent(event))
                if self.events.count > 50 { self.events.removeFirst(self.events.count - 50) }
            }
        }
    }

    private func apply(_ snapshot: PortalSnapshot) {
        self.snapshot = snapshot
        if snapshot.isTerminal { releasePayload() }
    }

    private func releasePayload() { payload?.stop(); payload = nil }
    private func detach() {
        stateSubscription?.cancel()
        eventSubscription?.cancel()
        stateSubscription = nil
        eventSubscription = nil
    }
    private func nonempty(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
    private func values(_ value: String) -> [String] { value.split(separator: ",").compactMap { nonempty(String($0)) } }
    private func optionalValues(_ value: String) -> [String]? {
        let result = values(value)
        return result.isEmpty ? nil : result
    }
    private func describe(_ failure: PortalFailure) -> String {
        var message = "\(failure.code): \(failure.message)"
        if let operation = failure.operation { message += "\nOperation: \(operation)" }
        if let original = failure.readinessFailure { message += "\nReadiness: \(original.code): \(original.message)" }
        return message
    }
    private func describeEvent(_ event: PortalEvent) -> String {
        switch event {
        case let event as PortalEvent.Started: return "STARTED \(event.name)"
        case is PortalEvent.Stopped: return "STOPPED"
        case let event as PortalEvent.StatusChanged: return "STATUS_CHANGED active=\(event.status.active) relays=\(event.status.relays.count)"
        case let event as PortalEvent.MitmSuspected: return "MITM_SUSPECTED \(event.relayUrl)"
        case let event as PortalEvent.RelayAdded: return "RELAY_ADDED \(event.relayUrl)"
        case let event as PortalEvent.RelayRemoved: return "RELAY_REMOVED \(event.relayUrl)"
        case let event as PortalEvent.Error: return "ERROR \(event.message)"
        case let event as PortalEvent.Unknown: return "UNKNOWN \(event.type)"
        default: return "EVENT"
        }
    }
}

@MainActor
struct PortalHomeView: View {
    @ObservedObject var model: PortalHomeModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            Form {
                Section("Foreground workbench") {
                    Text("Serve real traffic from this app through Portal relays. Keep the app open and explicitly stop when finished. iOS can suspend networking when backgrounded or locked.")
                    if scenePhase != .active {
                        Label("Background execution is not guaranteed.", systemImage: "exclamationmark.triangle")
                    }
                }
                configuration
                sessionSection
                if model.running || model.receivedCount > 0 { requestSection }
                if model.running || model.advancedSettings { metadataSection }
                if model.running { relaySection }
                diagnosticsSection
                Section("SDK capabilities") {
                    Text("Static directories, HTTP forwarding, prefix-based HTTP routes, raw TCP and UDP forwarding, live metadata, relay management, authoritative state and bounded events are exercised here. Relay support and network reachability still determine availability.")
                    Text("Persistent identity is managed by the SDK in Application Support/Portal/identity.json. Editing the display name never regenerates or overwrites identity. Hide is a listing preference, not access control. ECH, x402, method-constrained routes and overlay are unsupported by this engine.")
                }
            }
            .navigationTitle("Portal SDK Workbench")
            .onAppear { model.loadDiagnostics() }
        }
    }

    private var configuration: some View {
        Section("1 · Choose a capability") {
            Group {
                Picker("Mode", selection: $model.mode) {
                    ForEach(WorkbenchMode.allCases) { mode in Text(mode.title).tag(mode) }
                }
                Text(model.mode.explanation).font(.footnote).foregroundStyle(.secondary)
                if model.mode != .staticSite {
                    Toggle("Use built-in loopback demo", isOn: $model.builtInDemo)
                    if model.builtInDemo {
                        Text(demoInstructions).font(.caption).textSelection(.enabled)
                    } else if model.mode == .routes {
                        TextField("First prefix", text: $model.prefixOne)
                        TextField("First upstream URL", text: $model.upstreamOne)
                        TextField("Second prefix", text: $model.prefixTwo)
                        TextField("Second upstream URL", text: $model.upstreamTwo)
                        Text("The longest matching path prefix wins; / is a catch-all fallback. Run both HTTP services on this device first; upstreams must use loopback URLs.").font(.caption)
                    } else {
                        TextField("Loopback host:port", text: $model.target)
                        Text("Start a matching \(model.mode == .http ? "HTTP" : model.mode == .tcp ? "TCP" : "UDP") service on this device first. This sample never substitutes a fake upstream. Simulator loopback reaches your Mac; a physical device's loopback does not.").font(.caption)
                    }
                }
                Toggle("Advanced settings", isOn: $model.advancedSettings)
                if model.advancedSettings {
                    TextField("Display name", text: $model.name)
                    Toggle("Discover relays", isOn: $model.discovery)
                    TextField("Relay URLs (comma-separated)", text: $model.relayInput)
                    Toggle("Ban suspected MITM relays", isOn: $model.banMitm)
                }
            }
            .disabled(!model.editable)
            if model.missingRelay { Text("With discovery off, configure at least one relay.").foregroundStyle(.red) }
            if model.starting {
                ProgressView("Publishing… SDK waits for readiness")
            } else {
                Button("Publish") { model.start() }.disabled(!model.editable || model.missingRelay)
            }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
    }

    private var sessionSection: some View {
        Section("2 · Live session") {
            if let snapshot = model.snapshot {
                LabeledContent("Phase", value: String(describing: snapshot.phase))
                Text("Revision \(snapshot.revision) · dropped events \(snapshot.droppedEventCount)").font(.caption)
                if snapshot.hasSecurityWarning { Label("Sticky security warning: inspect relay state before sharing.", systemImage: "exclamationmark.shield").foregroundStyle(.red) }
                if let failure = snapshot.lastFailure { Text("\(failure.code): \(failure.message)").foregroundStyle(.red) }
                ForEach(model.urls, id: \.self) { address in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(address).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                        HStack {
                            Button("Copy") { UIPasteboard.general.string = address }
                            ShareLink(item: address) { Text("Share") }
                            if let url = URL(string: address), ["http", "https"].contains(url.scheme?.lowercased() ?? "") { Link("Open", destination: url) }
                        }.buttonStyle(.borderless)
                    }
                }
                HStack {
                    Button("Refresh") { model.refresh() }.disabled(!model.operable)
                    Spacer()
                    Button(model.snapshot?.phase == .stopping ? "Retry Stop" : "Stop", role: .destructive) { model.stop() }
                        .disabled(!model.running || model.stopping || model.starting)
                }.buttonStyle(.borderless)
                if model.stopping { ProgressView("Stopping owned session…") }
                if snapshot.phase == .stopping { Text("Cleanup has not completed. The session and its local demo remain owned and retryable; do not start another session.").font(.caption) }
            } else {
                Text("Choose a mode and tap Publish. The SDK waits for readiness and rolls back failed publication; upstream application health is checked separately.").foregroundStyle(.secondary)
            }
            if let error = model.lastError { Text(error).foregroundStyle(.red).textSelection(.enabled) }
        }
    }

    private var requestSection: some View {
        Section("3 · Exercise the demo") {
            Text(exerciseCommands).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
            ForEach(model.localEndpoints, id: \.self) { address in
                Text("Local listener: \(address)").font(.system(.caption, design: .monospaced))
            }
            Text("Received \(model.receivedCount) HTTP requests / echo chunks / datagrams. Showing the latest 40. No request bodies, headers or query strings are retained.").font(.caption)
            ForEach(Array(model.requests.enumerated()), id: \.offset) { _, request in Text(request).font(.system(.caption, design: .monospaced)) }
        }
    }

    private var metadataSection: some View {
        Section(model.advancedSettings ? "Metadata · start settings and live updates" : "Live metadata edits") {
            Group {
                TextField("Description", text: $model.metaDescription)
                TextField("Tags, comma-separated", text: $model.metaTags)
                TextField("Owner", text: $model.metaOwner)
                Toggle("Hide from listings (not authentication)", isOn: $model.hide)
            }.disabled(model.starting || model.stopping || (model.running && !model.operable))
            Button("Apply metadata to live session") { model.updateMetadata() }.disabled(!model.operable)
        }
    }

    private var relaySection: some View {
        Section("Relay endpoints and live operations") {
            if let relays = model.snapshot?.relays {
                ForEach(Array(relays.enumerated()), id: \.offset) { _, relay in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(relay.relayUrl).font(.caption).textSelection(.enabled)
                        Text("State: \(relay.state)").font(.caption)
                        if let tcp = relay.tcpAddr { endpoint("TCP", tcp) }
                        if let udp = relay.udpAddr { endpoint("UDP", udp) }
                        if let failure = relay.failure { Text(failure).foregroundStyle(.red).font(.caption) }
                        if let error = relay.error { Text(error).foregroundStyle(.red).font(.caption) }
                        Button("Remove relay", role: .destructive) { model.removeRelay(relay.relayUrl) }.disabled(!model.operable)
                    }
                }
            }
            TextField("https://relay.example", text: $model.newRelay).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Add relay") { model.addRelay() }.disabled(!model.operable || model.newRelay.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private func endpoint(_ label: String, _ address: String) -> some View {
        HStack {
            Text("\(label): \(address)").font(.system(.caption, design: .monospaced)).textSelection(.enabled)
            Spacer()
            Button("Copy") { UIPasteboard.general.string = address }.buttonStyle(.borderless)
        }
    }

    private var diagnosticsSection: some View {
        Section("Diagnostics and auxiliary events") {
            Button("Read diagnostics") { model.loadDiagnostics() }
            if let diagnostics = model.diagnostics {
                Text("SDK \(diagnostics.sdkVersion) · engine \(diagnostics.engineVersion) · ABI \(diagnostics.abiVersion) · wire \(diagnostics.wireSchemaVersion)\nOwned sessions \(diagnostics.activeSessions) · orphan drops \(diagnostics.droppedOrphanEvents)")
                    .font(.system(.caption, design: .monospaced)).textSelection(.enabled)
            }
            Text("Snapshot state is authoritative; auxiliary events are bounded and not replayed. Latest 50:").font(.caption)
            ForEach(Array(model.events.enumerated()), id: \.offset) { _, event in Text(event).font(.system(.caption, design: .monospaced)) }
        }
    }

    private var demoInstructions: String {
        switch model.mode {
        case .staticSite: return "Shared arcade: Snake, memory pairs, reaction testing and sloshing water fill; engine-served, no listener."
        case .http: return "Built-in loopback API on an automatically assigned port. GET returns JSON; POST receives a webhook."
        case .routes: return "Prefix routes: /api → local API; /hooks → local webhook receiver, each on an automatically assigned port."
        case .tcp: return "Loopback TCP echo on an automatically assigned port. Max 16 connections, 4096-byte chunks, 10-second connection lifetime."
        case .udp: return "Loopback UDP echo on an automatically assigned port. Max 16 peers, 4096-byte datagrams, 10-second peer lifetime."
        }
    }

    private var exerciseCommands: String {
        if model.mode != .staticSite && !model.builtInDemo {
            return "Custom upstream selected. Exercise the public HTTP URL or relay TCP/UDP endpoint with your service's protocol. The custom service controls responses and its requests are not included in the built-in receive log."
        }
        switch model.mode {
        case .staticSite: return "Open the public HTTPS URL to play four keyboard/touch games. Each visitor plays independently; scores stay in that browser tab."
        case .http: return "curl 'PUBLIC_URL/'\ncurl -X POST 'PUBLIC_URL/hooks' -H 'Content-Type: application/json' --data '{\"event\":\"hello\"}'\nBuilt-in HTTP: 16 KiB total request limit, Content-Length only, no keep-alive; bodies are discarded."
        case .routes: return "curl 'PUBLIC_URL/api'\ncurl -X POST 'PUBLIC_URL/hooks' --data 'hello'\nAPI and webhook return different service labels. Unmatched prefixes are not a fallback route."
        case .tcp: return "Use the relay TCP host and port below:\nprintf 'hello\\n' | nc -w 2 TCP_HOST TCP_PORT\nExpected: the same bytes."
        case .udp: return "Use the relay UDP host and port below:\nprintf 'hello\\n' | nc -u -w 2 UDP_HOST UDP_PORT\nExpected: the same datagram."
        }
    }
}

import SwiftUI
import UIKit
import PortalSDK

@MainActor
final class PortalHomeModel: ObservableObject {
    @Published var mode: WorkbenchMode = .staticSite
    @Published var builtInDemo = true
    @Published var advancedSettings = false
    @Published var domainPrefix = "ios-demo"
    @Published var selectedModelId = ""
    @Published var llmPassword = ""
    let modelStore = ModelStore()
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
    private var llmServer: LocalLlmServer?
    private var llmAddress: String?
    private var attempt = UUID()

    init() {
        do { llmPassword = try LocalLlmPassword.generate() }
        catch { lastError = error.localizedDescription }
    }

    func generateLlmPassword() {
        guard editable else { return }
        do {
            llmPassword = try LocalLlmPassword.generate()
            lastError = nil
        } catch { lastError = error.localizedDescription }
    }

    func copyLlmPassword() {
        guard passwordValid else { return }
        UIPasteboard.general.setItems(
            [["public.utf8-plain-text": llmPassword]],
            options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
    }

    var running: Bool { snapshot.map { !$0.isTerminal } ?? false }
    var editable: Bool { !starting && !stopping && !running }
    var operable: Bool { running && !starting && !stopping && snapshot?.phase != .stopping }
    var missingRelay: Bool { advancedSettings && !discovery && values(relayInput).isEmpty }
    var primaryPublicUrl: String? { running && !stopping ? snapshot?.primaryPublicUrl : nil }
    var localEndpoints: [String] { payload?.addresses ?? [] }
    var selectedModel: LocalModelSpec? { modelStore.models.first { $0.id == selectedModelId } ?? modelStore.models.first }
    var modelReady: Bool { selectedModel.flatMap { modelStore.cachedFile($0) } != nil }
    var passwordValid: Bool { LocalLlmPassword.isValid(llmPassword) }

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
        if mode == .localLlm {
            guard let model = selectedModel, let file = modelStore.cachedFile(model) else {
                lastError = "Download and verify a model before publishing."
                starting = false
                return
            }
            do {
                let server = try LocalLlmServer(model: model, modelFile: file, password: llmPassword) { [weak self] message in
                    guard let self, self.attempt == id else { return }
                    self.lastError = message
                }
                llmServer = server
                server.start { [weak self] result in
                    guard let self, self.attempt == id else { return }
                    switch result {
                    case .success(let address): self.llmAddress = address; self.publish(id: id)
                    case .failure(let error):
                        self.lastError = error.localizedDescription
                        self.starting = false
                        self.releasePayload()
                    }
                }
            } catch {
                lastError = error.localizedDescription
                starting = false
                releasePayload()
            }
        } else {
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
                } else { self.publish(id: id) }
            }
        }
    }

    private func publish(id: UUID) {
        do {
            let config = try makeConfig()
            _ = client.publish(config: config) { [weak self] session, failure in
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
            lastError = error.localizedDescription
            starting = false
            releasePayload()
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
        case .localLlm:
            guard let address = llmAddress else { throw LocalLlmError.message("The local model server is not ready.") }
            base = factory.http(targetAddress: address)
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
        let config: PortalConfig
        if advancedSettings {
            config = factory.custom(
            name: nil, identityJson: nil, identityPath: nil,
            relays: optionalValues(relayInput), discovery: discovery, maxActiveRelays: 3,
            banMitm: banMitm, ech: false, udp: base.udp, tcp: base.tcp,
            description: nonempty(metaDescription), tags: optionalValues(metaTags),
            owner: nonempty(metaOwner), thumbnail: nil, hide: hide,
            staticDir: base.staticDir, staticIndex: base.staticIndex,
            targetAddr: base.targetAddr, udpAddr: base.udpAddr, httpRoutes: base.httpRoutes, x402: nil)
        } else {
            config = base
        }
        return config.withDomainPrefix(prefix: nonempty(domainPrefix))
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

    private func releasePayload() {
        payload?.stop()
        payload = nil
        llmServer?.close()
        llmServer = nil
        llmAddress = nil
    }
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
    @ObservedObject private var store: ModelStore

    init(model: PortalHomeModel) {
        self.model = model
        self.store = model.modelStore
    }
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                VStack(alignment: .leading, spacing: 10) {
                    Text("PORTAL / IOS").font(.system(.caption, design: .monospaced).weight(.semibold)).tracking(3)
                    Text("Your device.\nOn the web.").font(.system(size: 42, weight: .bold, design: .rounded)).tracking(-1.5)
                    Text("A local-first workbench for real public connections.").font(.title3).foregroundStyle(.secondary)
                }.padding(.vertical, 16)
                MonoCard("FOREGROUND FIRST") {
                    Text("Keep this app open while serving. iOS can suspend networking and inference when backgrounded or locked. Stop your session when finished.")
                    if scenePhase != .active {
                        Label("Background execution is not guaranteed.", systemImage: "exclamationmark.triangle")
                    }
                }
                configuration
                if model.mode == .localLlm {
                    ModelLibraryView(store: store, selectedId: $model.selectedModelId, editable: model.editable)
                    llmPasswordSection
                }
                sessionSection
                if model.running || model.receivedCount > 0 { requestSection }
                if model.running || model.advancedSettings { metadataSection }
                if model.running { relaySection }
                diagnosticsSection
                MonoCard("SDK / OWNERSHIP") {
                    Text("Static sites, HTTP, prefix routes, TCP and UDP use the same SDK. Local inference is sample-only native code, separate from the transport SDK.")
                    Text("The SDK keeps identity in Application Support/Portal/identity.json. A domain prefix never replaces those keys. Hide is a listing preference, not authentication. ECH, x402, method-constrained routes and overlay are unsupported.").font(.caption).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: 760)
            .padding(20)
            .frame(maxWidth: .infinity)
        }
        .clipped()
        .background(Color(uiColor: .systemBackground))
        .foregroundStyle(.primary)
        .tint(.primary)
        .buttonStyle(MonoButtonStyle())
        .textFieldStyle(MonoTextFieldStyle())
        .onAppear { model.loadDiagnostics() }
    }

    private var configuration: some View {
        MonoCard("01 / CONFIGURE") {
            Group {
                Picker("Mode", selection: $model.mode) {
                    ForEach(WorkbenchMode.allCases) { mode in Text(mode.title).tag(mode) }
                }
                Text(model.mode.explanation).font(.footnote).foregroundStyle(.secondary)
                if model.mode != .staticSite && model.mode != .localLlm {
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
                Text("DOMAIN PREFIX").font(.system(.caption, design: .monospaced).weight(.semibold))
                TextField("Domain prefix", text: $model.domainPrefix)
                Text("Optional: 1–63 ASCII letters, digits or hyphens; no leading/trailing hyphen or reserved 40-hex identity shape. A requested prefix is not permanently reserved. If another identity owns it, the canonical URL is used instead.").font(.caption)
                Toggle("Advanced settings", isOn: $model.advancedSettings)
                if model.advancedSettings {
                    Toggle("Discover relays", isOn: $model.discovery)
                    TextField("Relay URLs (comma-separated)", text: $model.relayInput)
                    Toggle("Ban suspected MITM relays", isOn: $model.banMitm)
                }
            }
            .disabled(!model.editable)
            if model.missingRelay { Label("With discovery off, configure at least one relay.", systemImage: "exclamationmark.triangle") }
            if model.starting {
                ProgressView(model.mode == .localLlm ? "Loading device model / publishing…" : "Publishing / waiting for readiness…")
            } else {
                Button("Publish to the web") { model.start() }
                    .buttonStyle(MonoButtonStyle(primary: true))
                    .disabled(!model.editable || model.missingRelay || (model.mode == .localLlm && (!model.modelReady || !model.passwordValid)))
            }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
    }

    private var llmPasswordSection: some View {
        MonoCard("LOCAL LLM / PASSWORD") {
            SecureField("Password", text: $model.llmPassword)
                .textContentType(.oneTimeCode)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .disabled(!model.editable)
            Text("Use 12–128 Unicode code points. A securely generated password is recommended. Enter this password separately on the webpage; sharing the public URL alone does not grant inference.").font(.caption)
            HStack {
                Button("Generate password") { model.generateLlmPassword() }.disabled(!model.editable)
                Button("Copy password") { model.copyLlmPassword() }.disabled(!model.passwordValid)
            }
            if !model.passwordValid {
                Label("Password must contain 12–128 Unicode code points.", systemImage: "exclamationmark.triangle").font(.caption)
            }
            Text("Kept only in app memory, reused until changed, and replaced on relaunch. Editing and generation are disabled while publishing or serving. Copied passwords stay on this device's clipboard for two minutes.").font(.caption).foregroundStyle(.secondary)
        }
    }

    private var sessionSection: some View {
        MonoCard("02 / LIVE SESSION") {
            if let snapshot = model.snapshot {
                LabeledContent("Phase", value: String(describing: snapshot.phase))
                Text("Revision \(snapshot.revision) · dropped events \(snapshot.droppedEventCount)").font(.caption)
                if snapshot.hasSecurityWarning { Label("Sticky security warning: inspect relay state before sharing.", systemImage: "exclamationmark.shield") }
                if let failure = snapshot.lastFailure { Text("\(failure.code): \(failure.message)") }
                if let address = model.primaryPublicUrl {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(model.mode == .localLlm ? "Public URL · password shared separately" : "Public URL").font(.caption).foregroundStyle(.secondary)
                        Text(address).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                        HStack {
                            Button("Copy") { UIPasteboard.general.string = address }
                            ShareLink(item: address) { Text("Share") }
                            if let url = URL(string: address), ["http", "https"].contains(url.scheme?.lowercased() ?? "") { Link("Open", destination: url) }
                        }.buttonStyle(.borderless)
                    }
                }
                if model.running {
                    ForEach(snapshot.publicUrls.filter { $0 != model.primaryPublicUrl }, id: \.self) { address in
                        Text("Canonical identity-bound URL: \(address)")
                            .font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary).textSelection(.enabled)
                    }
                }
                Text("The primary URL uses an actual relay-granted alias only while ACTIVE; otherwise it falls back to the canonical URL. Aliases are lease-scoped and can change after reconnecting.").font(.caption)
                HStack {
                    Button("Refresh") { model.refresh() }.disabled(!model.operable)
                    Spacer()
                    Button(model.snapshot?.phase == .stopping ? "Retry Stop" : "Stop") { model.stop() }
                        .disabled(!model.running || model.stopping || model.starting)
                }.buttonStyle(.borderless)
                if model.stopping { ProgressView("Stopping owned session…") }
                if snapshot.phase == .stopping { Text("Cleanup has not completed. The session and its local demo remain owned and retryable; do not start another session.").font(.caption) }
            } else {
                Text("Choose a mode and tap Publish. The SDK waits for readiness and rolls back failed publication; upstream application health is checked separately.").foregroundStyle(.secondary)
            }
            if let error = model.lastError {
                Label("Operation needs attention", systemImage: "exclamationmark.triangle").font(.headline)
                Text(error).textSelection(.enabled)
            }
        }
    }

    private var requestSection: some View {
        MonoCard("03 / EXERCISE") {
            Text(exerciseCommands).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
            ForEach(model.localEndpoints, id: \.self) { address in
                Text("Local listener: \(address)").font(.system(.caption, design: .monospaced))
            }
            if model.mode == .localLlm {
                Text("Prompts, responses, authorization headers and passwords are not logged. Each request is independent. One generation at a time; 1024 context tokens and at most 128 output tokens.").font(.caption)
            } else {
                Text("Received \(model.receivedCount) HTTP requests / echo chunks / datagrams. Showing the latest 40. No request bodies, headers or query strings are retained.").font(.caption)
                ForEach(Array(model.requests.enumerated()), id: \.offset) { _, request in Text(request).font(.system(.caption, design: .monospaced)) }
            }
        }
    }

    private var metadataSection: some View {
        MonoCard(model.advancedSettings ? "METADATA / START + LIVE" : "METADATA / LIVE") {
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
        MonoCard("RELAYS / LIVE OPERATIONS") {
            if let relays = model.snapshot?.relays {
                ForEach(Array(relays.enumerated()), id: \.offset) { _, relay in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(relay.relayUrl).font(.caption).textSelection(.enabled)
                        Text("State: \(relay.state)").font(.caption)
                        if let tcp = relay.tcpAddr { endpoint("TCP", tcp) }
                        if let udp = relay.udpAddr { endpoint("UDP", udp) }
                        if let failure = relay.failure { Text(failure).font(.caption) }
                        if let error = relay.error { Text(error).font(.caption) }
                        Button("Remove relay") { model.removeRelay(relay.relayUrl) }.disabled(!model.operable)
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
        MonoCard("DIAGNOSTICS / AUXILIARY EVENTS") {
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
        case .localLlm: return "Explicitly download a catalog model once, then reuse verified device storage. Open the public webpage and enter the separate password."
        case .http: return "Built-in loopback API on an automatically assigned port. GET returns JSON; POST receives a webhook."
        case .routes: return "Prefix routes: /api → local API; /hooks → local webhook receiver, each on an automatically assigned port."
        case .tcp: return "Loopback TCP echo on an automatically assigned port. Max 16 connections, 4096-byte chunks, 10-second connection lifetime."
        case .udp: return "Loopback UDP echo on an automatically assigned port. Max 16 peers, 4096-byte datagrams, 10-second peer lifetime."
        }
    }

    private var exerciseCommands: String {
        if model.mode != .staticSite && model.mode != .localLlm && !model.builtInDemo {
            return "Custom upstream selected. Exercise the public HTTP URL or relay TCP/UDP endpoint with your service's protocol. The custom service controls responses and its requests are not included in the built-in receive log."
        }
        let publicUrl = model.primaryPublicUrl ?? "PUBLIC_URL"
        switch model.mode {
        case .staticSite: return "Open \(publicUrl) to play four keyboard/touch games. Each visitor plays independently; scores stay in that browser tab."
        case .localLlm: return "Open \(publicUrl) and enter the separately configured password. Requests run on this device's CPU while the app stays foregrounded; no cloud or external inference daemon is used.\ncurl -u 'portal:YOUR_PASSWORD' '\(publicUrl)/api/status'"
        case .http: return "curl '\(publicUrl)/'\ncurl -X POST '\(publicUrl)/hooks' -H 'Content-Type: application/json' --data '{\"event\":\"hello\"}'\nBuilt-in HTTP: 16 KiB total request limit, Content-Length only, no keep-alive; bodies are discarded."
        case .routes: return "curl '\(publicUrl)/api'\ncurl -X POST '\(publicUrl)/hooks' --data 'hello'\nAPI and webhook return different service labels. Unmatched prefixes are not a fallback route."
        case .tcp: return "Use the relay TCP host and port below:\nprintf 'hello\\n' | nc -w 2 TCP_HOST TCP_PORT\nExpected: the same bytes."
        case .udp: return "Use the relay UDP host and port below:\nprintf 'hello\\n' | nc -u -w 2 UDP_HOST UDP_PORT\nExpected: the same datagram."
        }
    }
}

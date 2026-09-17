import SwiftUI
import PortalSDK

/// Feature-rich SwiftUI sample driving the KMP SDK through the callback
/// facade. The client outlives the view; observation cancel != tunnel stop.
@MainActor
final class PortalHomeModel: ObservableObject {
    // Config
    @Published var name = "ios-kmp-sample"
    @Published var discovery = true
    @Published var udp = false
    @Published var tcp = false
    @Published var ech = false
    @Published var banMitm = false
    @Published var hide = false
    @Published var configDescription = "Portal KMP iOS sample"
    @Published var configTags = "demo,kmp"
    @Published var relays = ""

    // Session state
    @Published var phase: String = "idle"
    @Published var revision: Int64 = 0
    @Published var publicUrl: String = ""
    @Published var relays: [PortalRelayStatus] = []
    @Published var warning = false
    @Published var lastFailure: String?
    @Published var lastError: String?

    // Identity / metadata / relays
    @Published var identityAddress: String = ""
    @Published var metaDescription = ""
    @Published var metaTags = ""
    @Published var metaOwner = ""
    @Published var metaHide = false
    @Published var newRelay = ""

    // Events + diagnostics
    @Published var eventLog: [String] = []
    @Published var diagnosticsText = ""

    private let client = PortalIosClient()
    private var session: PortalIosSession?
    private var stateSub: PortalSubscription?
    private var eventSub: PortalSubscription?
    private var startOp: PortalOperation?

    var isTerminal: Bool { session?.snapshot.isTerminal ?? true }
    var hasSession: Bool { session != nil && !isTerminal }

    // ---- actions -----------------------------------------------------------

    func start(siteDir: String, identityPath: String) {
        lastError = nil
        let config = PortalConfig(
            name: name.isEmpty ? nil : name,
            identityJson: nil,
            identityPath: identityPath,
            relays: relays.isEmpty ? nil : relays.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) },
            discovery: discovery,
            maxActiveRelays: 3,
            banMitm: banMitm, ech: ech, overlay: false,
            udp: udp, tcp: tcp,
            description: configDescription.isEmpty ? nil : configDescription,
            tags: configTags.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) },
            owner: nil, thumbnail: nil, hide: hide,
            staticDir: siteDir, staticIndex: "index.html",
            targetAddr: nil, udpAddr: nil, httpRoutes: nil, x402: nil
        )
        startOp = client.open(config: config) { [weak self] session, failure in
            guard let self else { return }
            if let failure {
                self.lastError = "start failed: \(failure.code) \(failure.message)"
                return
            }
            self.session = session
            self.attach(session)
        }
    }

    func stop() {
        session?.stop { [weak self] failure in
            if let failure { self?.lastError = "stop failed: \(failure.code)" }
        }
    }

    func refresh() {
        session?.refresh { [weak self] failure in
            if let failure { self?.lastError = "refresh failed: \(failure.code)" }
        }
    }

    func awaitReady() {
        session?.awaitReady(capability: .staticSite, timeoutMillis: 15_000) { [weak self] _, failure in
            if let failure { self?.lastError = "awaitReady failed: \(failure.code)" }
        }
    }

    func generateIdentity() {
        // PortalIdentity.generate uses the platform engine; on iOS it needs
        // the linked libportaltunnel.a.
        do {
            let identity = try PortalIdentity.companion.generate(name: "ios-kmp-sample")
            identityAddress = identity.address
        } catch {
            lastError = "identity failed: \(error.localizedDescription)"
        }
    }

    func updateMetadata() {
        let metadata = PortalMetadata(
            description: metaDescription.isEmpty ? nil : metaDescription,
            tags: metaTags.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) },
            owner: metaOwner.isEmpty ? nil : metaOwner,
            thumbnail: nil,
            hide: metaHide
        )
        session?.updateMetadata(metadata: metadata) { [weak self] failure in
            if let failure { self?.lastError = "metadata failed: \(failure.code)" }
        }
    }

    func addRelay() {
        guard !newRelay.isEmpty else { return }
        session?.addRelay(relayUrl: newRelay) { [weak self] failure in
            if let failure { self?.lastError = "addRelay failed: \(failure.code)" }
        }
        newRelay = ""
    }

    func removeRelay(_ url: String) {
        session?.removeRelay(relayUrl: url) { [weak self] failure in
            if let failure { self?.lastError = "removeRelay failed: \(failure.code)" }
        }
    }

    func loadDiagnostics() {
        let d = client.diagnostics()
        diagnosticsText = "sdk=\(d.sdkVersion) abi=\(d.abiVersion) wire=\(d.wireSchemaVersion)\n" +
            "sessions=\(d.activeSessions) orphanDrops=\(d.droppedOrphanEvents)"
    }

    func onDisappear() {
        stateSub?.cancel()
        eventSub?.cancel()
        stateSub = nil
        eventSub = nil
    }

    // ---- plumbing ------------------------------------------------------------

    private func attach(_ session: PortalIosSession?) {
        stateSub?.cancel()
        eventSub?.cancel()
        stateSub = session?.observeState { [weak self] s in
            guard let self else { return }
            self.phase = "\(s.phase)".lowercased()
            self.revision = s.revision
            self.publicUrl = s.primaryPublicUrl ?? ""
            self.relays = s.relays
            self.warning = s.hasSecurityWarning
            self.lastFailure = s.lastFailure.map { "\($0.code): \($0.message)" }
        }
        eventSub = session?.observeEvents { [weak self] event in
            self?.appendLog(describe(event))
        }
    }

    private func appendLog(_ line: String) {
        eventLog.append(line)
        if eventLog.count > 50 { eventLog.removeFirst(eventLog.count - 50) }
    }

    private func describe(_ event: PortalEvent) -> String {
        switch event {
        case let e as PortalEvent.Started: return "STARTED \(e.name)"
        case is PortalEvent.Stopped: return "STOPPED"
        case let e as PortalEvent.StatusChanged:
            return "STATUS_CHANGED active=\(e.status.active) relays=\(e.status.relays.count)"
        case let e as PortalEvent.MitmSuspected: return "MITM_SUSPECTED \(e.relayUrl)"
        case let e as PortalEvent.Error: return "ERROR \(e.message)"
        case let e as PortalEvent.Unknown: return "UNKNOWN \(e.type)"
        default: return "EVENT"
        }
    }
}

// MARK: - View

struct PortalHomeView: View {
    @StateObject private var model = PortalHomeModel()
    let siteDir: String
    let identityPath: String

    var body: some View {
        NavigationStack {
            List {
                configSection
                sessionSection
                identitySection
                publicUrlSection
                metadataSection
                relaysSection
                eventsSection
                diagnosticsSection
            }
            .navigationTitle("Portal Sample")
            .onDisappear { model.onDisappear() }
        }
    }

    private var configSection: some View {
        Section {
            TextField("name", text: $model.name)
            Text("public name — becomes <name>.portal.<relay>")
                .font(.caption2).foregroundStyle(.secondary)
            TextField("description", text: $model.configDescription)
            Text("shown in the public directory")
                .font(.caption2).foregroundStyle(.secondary)
            TextField("tags (comma-separated)", text: $model.configTags)
            Text("search keywords for discovery")
                .font(.caption2).foregroundStyle(.secondary)
            TextField("relays (comma-separated)", text: $model.relays)
            Text("relay URLs; empty = public pool")
                .font(.caption2).foregroundStyle(.secondary)
            Toggle("discovery", isOn: $model.discovery)
            Text("list in the public directory")
                .font(.caption2).foregroundStyle(.secondary)
            Toggle("udp", isOn: $model.udp)
            Text("relay UDP traffic (games, QUIC)")
                .font(.caption2).foregroundStyle(.secondary)
            Toggle("tcp", isOn: $model.tcp)
            Text("relay raw TCP ports")
                .font(.caption2).foregroundStyle(.secondary)
            Toggle("ech", isOn: $model.ech)
            Text("hide SNI from relays (privacy)")
                .font(.caption2).foregroundStyle(.secondary)
            Toggle("ban_mitm", isOn: $model.banMitm)
            Text("refuse relays that intercept TLS")
                .font(.caption2).foregroundStyle(.secondary)
            Toggle("hide", isOn: $model.hide)
            Text("unlisted; only reachable by direct URL")
                .font(.caption2).foregroundStyle(.secondary)
            Button("Start tunnel") {
                model.start(siteDir: siteDir, identityPath: identityPath)
            }
            .disabled(model.hasSession)
            Text("iOS suspends apps in the background — the tunnel stops when the app is backgrounded. Keep the app in the foreground to serve.")
                .font(.caption2).foregroundStyle(.secondary)
        } header: {
            Text("Tunnel config")
        } footer: {
            Text("Serve a site or game from this device. The URL rotates as relays join/leave.")
        }
    }

    private var sessionSection: some View {
        Section("Session") {
            HStack {
                Text(model.phase)
                    .font(.headline)
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(phaseColor.opacity(0.2))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                Spacer()
                Text("rev \(model.revision)")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if model.warning {
                Label("MITM suspected — treat endpoints as untrusted", systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.red).font(.caption)
            }
            if let f = model.lastFailure { Text(f).font(.caption).foregroundStyle(.red) }
            if let e = model.lastError { Text(e).font(.caption).foregroundStyle(.red) }
            HStack {
                Button("Stop") { model.stop() }.disabled(!model.hasSession)
                Button("Refresh") { model.refresh() }.disabled(!model.hasSession)
                Button("Await ready") { model.awaitReady() }.disabled(!model.hasSession)
            }
        }
    }
    private var publicUrlSection: some View {
        Section {
            Text(model.publicUrl.isEmpty ? "no public url yet" : model.publicUrl)
                .font(.system(.body, design: .monospaced))
                .textSelection(.enabled)
        } header: {
            Text("Public URL")
        } footer: {
            Text("The URL changes as relays join/leave. Copy the current one.")
        }
    }

    private var identitySection: some View {
        Section("Identity") {
            if model.identityAddress.isEmpty {
                Text("No identity generated. The tunnel creates one at identity_path on first start.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Text(model.identityAddress).font(.caption).textSelection(.enabled)
            }
            Button("Generate identity") { model.generateIdentity() }
        }
    }

    private var publicUrlSection: some View {
        Section("Public URL") {
            Text(model.publicUrl.isEmpty ? "no public url yet" : model.publicUrl)
                .font(.system(.body, design: .monospaced))
                .textSelection(.enabled)
        }
    }

    private var metadataSection: some View {
        Section("Metadata (live update)") {
            TextField("description", text: $model.metaDescription)
            TextField("tags", text: $model.metaTags)
            TextField("owner", text: $model.metaOwner)
            Toggle("hide", isOn: $model.metaHide)
            Button("Update metadata") { model.updateMetadata() }
                .disabled(!model.hasSession)
        }
    }

    private var relaysSection: some View {
        Section("Relays (\(model.relays.count))") {
            ForEach(model.relays, id: \.relayUrl) { relay in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(relay.relayUrl).font(.caption).lineLimit(1)
                        Spacer()
                        Text(relay.isMitm ? "mitm" : relay.state)
                            .font(.caption2)
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(relay.isMitm || relay.isFailed ? Color.red.opacity(0.2) : Color.green.opacity(0.2))
                            .clipShape(RoundedRectangle(cornerRadius: 4))
                        Button("×") { model.removeRelay(relay.relayUrl) }
                            .disabled(!model.hasSession)
                    }
                    if let url = relay.publicUrl {
                        Text(url).font(.caption2).foregroundStyle(.secondary)
                    }
                    if let err = relay.error {
                        Text(err).font(.caption2).foregroundStyle(.red)
                    }
                }
            }
            HStack {
                TextField("relay url", text: $model.newRelay)
                Button("Add") { model.addRelay() }
                    .disabled(!model.hasSession || model.newRelay.isEmpty)
            }
        }
    }

    private var eventsSection: some View {
        Section("Events") {
            if model.eventLog.isEmpty {
                Text("no events yet").font(.caption).foregroundStyle(.secondary)
            } else {
                ForEach(model.eventLog.suffix(20).reversed().map { $0 }, id: \.self) { line in
                    Text(line).font(.system(.caption2, design: .monospaced))
                }
            }
        }
    }

    private var diagnosticsSection: some View {
        Section("Diagnostics") {
            if !model.diagnosticsText.isEmpty {
                Text(model.diagnosticsText)
                    .font(.system(.caption, design: .monospaced))
            }
            Button("Load diagnostics") { model.loadDiagnostics() }
        }
    }
}

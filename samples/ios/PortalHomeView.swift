import SwiftUI
import UIKit
import PortalSDK

/// Owns the client independently of the selected destination. Observation is
/// view-scoped; cancelling a subscription does not stop the native session.
@MainActor
final class PortalHomeModel: ObservableObject {
    @Published var name = "ios-kmp-sample"
    @Published var discovery = true
    @Published var udp = false
    @Published var tcp = false
    @Published var ech = false
    @Published var banMitm = false
    @Published var hide = false
    @Published var configDescription = "Portal KMP iOS sample"
    @Published var configTags = "demo,kmp"
    @Published var relayInput = ""
    @Published var contentId = "snake"

    @Published private(set) var phase = "idle"
    @Published private(set) var revision: Int64 = 0
    @Published private(set) var publicUrls: [String] = []
    @Published private(set) var relayStatuses: [PortalRelayStatus] = []
    @Published private(set) var warning = false
    @Published private(set) var isTerminal = true
    @Published private(set) var busy: String?
    @Published private(set) var lastFailure: String?
    @Published private(set) var lastError: String?
    @Published private(set) var notice: String?

    @Published private(set) var identityAddress = ""
    @Published var metaDescription = ""
    @Published var metaTags = ""
    @Published var metaOwner = ""
    @Published var metaHide = false
    @Published var newRelay = ""
    @Published private(set) var eventLog: [String] = []
    @Published private(set) var diagnosticsText = ""

    private let client = PortalIosClient()
    private var session: PortalIosSession?
    private var stateSub: PortalSubscription?
    private var eventSub: PortalSubscription?
    private var startOp: PortalOperation?
    private var activeContent: SampleContent?
    private var observing = false

    var hasSession: Bool { session != nil && !isTerminal }
    var canManage: Bool { hasSession && phase != "stopping" && busy == nil }
    var missingRelay: Bool { !discovery && commaSeparated(relayInput).isEmpty }

    var phaseTitle: String {
        switch phase {
        case "starting": return "Preparing to publish"
        case "connecting": return "Connecting to relays"
        case "active": return "Publishing"
        case "stopping": return "Waiting for shutdown"
        case "stopped": return "Publishing stopped"
        case "failed": return "Connection failed"
        default: return "Ready to publish"
        }
    }
    func start(contents: SampleContents, identityPath: String) {
        guard busy == nil, !hasSession else { return }
        guard !missingRelay else {
            lastError = "Enable discovery or enter a relay URL."
            return
        }
        detach()
        session = nil
        publicUrls = []
        relayStatuses = []
        warning = false
        lastFailure = nil
        lastError = nil
        notice = nil
        revision = 0
        phase = "starting"
        busy = "Preparing your site"
        let relayUrls = commaSeparated(relayInput)
        let initialMetadata = (description: configDescription, tags: configTags, hide: hide)
        guard let content = contents.byId(contentId) else {
            lastError = "Unknown content: \(contentId)"
            return
        }
        content.start()
        let config = PortalConfig(
            name: name.isEmpty ? nil : name,
            identityJson: nil,
            identityPath: identityPath,
            relays: relayUrls.isEmpty ? nil : relayUrls,
            discovery: KotlinBoolean(bool: discovery),
            maxActiveRelays: 3,
            banMitm: banMitm, ech: ech, overlay: false,
            udp: udp, tcp: content.tcp,
            description: configDescription.isEmpty ? nil : configDescription,
            tags: commaSeparated(configTags),
            owner: nil, thumbnail: nil, hide: hide,
            staticDir: content.staticDir, staticIndex: content.staticIndex,
            targetAddr: content.targetAddr, udpAddr: nil, httpRoutes: nil, x402: nil
        )
        startOp = client.open(config: config) { [weak self] session, failure in
            guard let self else { return }
            self.startOp = nil
            self.busy = nil
            if let failure {
                content.stop()
                self.phase = "failed"
                self.lastError = self.failureText("Start publishing", failure)
                return
            }
            guard let session else {
                self.phase = "failed"
                self.lastError = "Start publishing: the SDK did not return a session."
                return
            }
            self.activeContent = content
            self.session = session
            self.metaDescription = initialMetadata.description
            self.metaTags = initialMetadata.tags
            self.metaOwner = ""
            self.metaHide = initialMetadata.hide
            self.apply(session.snapshot)
            if self.observing { self.attach() }
        }
    }

    func stop() {
        guard busy == nil, hasSession, let session else { return }
        begin("Requesting shutdown")
        session.stop { [weak self] failure in
            guard let self else { return }
            self.apply(session.snapshot)
            self.finish("Stop publishing", failure)
        }
    }

    func refresh() {
        guard canManage, let session else { return }
        begin("Checking the latest status")
        session.refresh { [weak self] failure in
            guard let self else { return }
            self.apply(session.snapshot)
            self.finish("Refresh status", failure)
        }
    }

    func awaitReady() {
        guard canManage, let session else { return }
        begin("Checking site readiness · up to 15 seconds")
        session.awaitReady(capability: .staticSite, timeoutMillis: 15_000) { [weak self] snapshot, failure in
            guard let self else { return }
            self.apply(snapshot ?? session.snapshot)
            self.finish("Check site readiness", failure)
        }
    }

    func generateIdentity() {
        guard busy == nil else { return }
        lastError = nil
        notice = nil
        do {
            let identity = try PortalIdentity.companion.generate(name: name.isEmpty ? "ios-kmp-sample" : name)
            identityAddress = identity.address
            notice = "Created a separate identity. Your current session identity has not changed."
        } catch {
            lastError = "Generate identity: \(error.localizedDescription)"
        }
    }

    func updateMetadata() {
        guard canManage, let session else { return }
        let metadata = PortalMetadata(
            description: metaDescription.isEmpty ? nil : metaDescription,
            tags: commaSeparated(metaTags),
            owner: metaOwner.isEmpty ? nil : metaOwner,
            thumbnail: nil,
            hide: KotlinBoolean(bool: metaHide)
        )
        begin("Applying public information")
        session.updateMetadata(metadata: metadata) { [weak self] failure in
            self?.finish("Update public information", failure)
        }
    }

    func addRelay() {
        let url = newRelay.trimmingCharacters(in: .whitespacesAndNewlines)
        guard canManage, !url.isEmpty, let session else { return }
        begin("Adding relay")
        session.addRelay(relayUrl: url) { [weak self] failure in
            guard let self else { return }
            if failure == nil, self.newRelay.trimmingCharacters(in: .whitespacesAndNewlines) == url {
                self.newRelay = ""
            }
            self.apply(session.snapshot)
            self.finish("Add relay", failure)
        }
    }

    func removeRelay(_ url: String) {
        guard canManage, let session else { return }
        begin("Removing relay")
        session.removeRelay(relayUrl: url) { [weak self] failure in
            guard let self else { return }
            self.apply(session.snapshot)
            self.finish("Remove relay", failure)
        }
    }

    func loadDiagnostics() {
        let d = client.diagnostics()
        diagnosticsText = "SDK \(d.sdkVersion) · ABI \(d.abiVersion) · Wire \(d.wireSchemaVersion)\n" +
            "Active sessions \(d.activeSessions) · Orphan events dropped \(d.droppedOrphanEvents)"
    }

    func onAppear() {
        observing = true
        attach()
    }

    func onDisappear() {
        observing = false
        detach()
    }

    private func attach() {
        detach()
        guard let session else { return }
        apply(session.snapshot)
        stateSub = session.observeState { [weak self] snapshot in
            self?.apply(snapshot)
        }
        eventSub = session.observeEvents { [weak self] event in
            guard let self else { return }
            self.eventLog.append(self.describe(event))
            if self.eventLog.count > 50 { self.eventLog.removeFirst(self.eventLog.count - 50) }
        }
    }

    private func detach() {
        stateSub?.cancel()
        eventSub?.cancel()
        stateSub = nil
        eventSub = nil
    }

    private func apply(_ snapshot: PortalSnapshot) {
        phase = "\(snapshot.phase)".lowercased()
        revision = snapshot.revision
        isTerminal = snapshot.isTerminal
        if snapshot.isTerminal {
            activeContent?.stop()
            activeContent = nil
        }
        relayStatuses = snapshot.relays
        // Terminal snapshots can retain old endpoints. Never offer those as live links.
        var seen = Set<String>()
        publicUrls = snapshot.isTerminal ? [] :
            (snapshot.publicUrls + snapshot.relays.filter { !$0.isFailed }.compactMap { $0.publicUrl })
                .filter { !$0.isEmpty && seen.insert($0).inserted }
        warning = snapshot.hasSecurityWarning
        lastFailure = snapshot.lastFailure.map { "\($0.code): \($0.message)" }
    }

    private func begin(_ message: String) {
        busy = message
        lastError = nil
        notice = nil
    }

    private func finish(_ action: String, _ failure: PortalFailure?) {
        busy = nil
        if let failure {
            lastError = failureText(action, failure)
        } else {
            notice = "\(action): request completed. Check the current status."
        }
    }

    private func failureText(_ action: String, _ failure: PortalFailure) -> String {
        "\(action) failed · \(failure.code)\n\(failure.message)"
    }

    private func commaSeparated(_ value: String) -> [String] {
        value.split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private func describe(_ event: PortalEvent) -> String {
        switch event {
        case let e as PortalEvent.Started: return "Started · \(e.name)"
        case is PortalEvent.Stopped: return "Stopped"
        case let e as PortalEvent.StatusChanged:
            return "Status changed · Active \(e.status.active) · Relays \(e.status.relays.count)"
        case let e as PortalEvent.MitmSuspected: return "Suspected TLS interception · \(e.relayUrl)"
        case let e as PortalEvent.Error: return "Error · \(e.message)"
        case let e as PortalEvent.Unknown: return "Unknown event · \(e.type)"
        default: return "Event received"
        }
    }
}

private enum PortalStyle {
    static let background = Color(red: 8 / 255, green: 15 / 255, blue: 29 / 255)
    static let card = Color(red: 18 / 255, green: 34 / 255, blue: 56 / 255)
    static let cyan = Color(red: 100 / 255, green: 216 / 255, blue: 242 / 255)
    static let mint = Color(red: 117 / 255, green: 227 / 255, blue: 189 / 255)
    static let muted = Color(red: 167 / 255, green: 186 / 255, blue: 206 / 255)
}

@MainActor
struct PortalHomeView: View {
    @StateObject private var model = PortalHomeModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var destination = 0
    let contents: SampleContents
    let identityPath: String
    var body: some View {
        TabView(selection: $destination) {
            page(title: "From your device to the web", subtitle: "PORTAL / PUBLISH") {
                publishContent
            }
            .tabItem { Label("Publish", systemImage: "arrow.up.right.circle.fill") }
            .tag(0)

            page(title: "Publish your way", subtitle: "PORTAL / SETTINGS") {
                settingsContent
            }
            .tabItem { Label("Settings", systemImage: "slider.horizontal.3") }
            .tag(1)

            page(title: "Your connection at a glance", subtitle: "PORTAL / ACTIVITY") {
                activityContent
            }
            .tabItem { Label("Activity", systemImage: "waveform.path") }
            .tag(2)
        }
        .tint(PortalStyle.cyan)
        .preferredColorScheme(.dark)
        .onAppear { model.onAppear() }
        .onDisappear { model.onDisappear() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.onAppear() }
        }
    }

    private var phaseColor: Color {
        if model.warning || model.phase == "failed" { return .orange }
        if model.phase == "active" { return PortalStyle.mint }
        return PortalStyle.cyan
    }

    private func page<Content: View>(title: String, subtitle: String, @ViewBuilder content: () -> Content) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(subtitle).font(.caption.weight(.semibold)).tracking(2).foregroundStyle(PortalStyle.cyan)
                    Text(title).font(.largeTitle.bold())
                }
                .padding(.top, 20)
                content()
            }
            .frame(maxWidth: 680, alignment: .leading)
            .padding(.horizontal, 22)
            .padding(.bottom, 28)
            .frame(maxWidth: .infinity)
        }
        .background(PortalStyle.background)
        .toolbarBackground(PortalStyle.card, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
    }

    private var publishContent: some View {
        Group {
            VStack(alignment: .leading, spacing: 22) {
                HStack(spacing: 8) {
                    Circle().fill(phaseColor).frame(width: 8, height: 8)
                    Text(model.phaseTitle).font(.subheadline.weight(.semibold)).foregroundStyle(phaseColor)
                    Spacer()
                    Image(systemName: "network").font(.title2).foregroundStyle(PortalStyle.cyan)
                }
                Text(model.phase == "active" ? "Your site,\nconnected to the world." : "A new home\nfor your small site.")
                    .font(.system(.largeTitle, design: .rounded).weight(.bold))
                    .fixedSize(horizontal: false, vertical: true)
                Text(heroDescription).foregroundStyle(PortalStyle.muted).font(.subheadline)
                if let busy = model.busy {
                    HStack(spacing: 10) {
                        ProgressView().tint(PortalStyle.cyan)
                        Text(busy).font(.subheadline)
                    }
                    .accessibilityElement(children: .combine)
                }
                Button {
                    if model.hasSession { model.stop() }
                    else { model.start(contents: contents, identityPath: identityPath) }
                } label: {
                    Label(model.hasSession ? (model.phase == "stopping" ? "Retry shutdown" : "Stop publishing") : "Publish your site",
                          systemImage: model.hasSession ? "stop.fill" : "arrow.up.right")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.borderedProminent)
                .tint(model.hasSession ? PortalStyle.card : PortalStyle.cyan)
                .foregroundStyle(model.hasSession ? Color.white : PortalStyle.background)
                .disabled(model.busy != nil || (!model.hasSession && model.missingRelay))
            }
            .padding(24)
            .background(LinearGradient(colors: [PortalStyle.card, Color(red: 14 / 255, green: 48 / 255, blue: 62 / 255)], startPoint: .topLeading, endPoint: .bottomTrailing))
            .clipShape(RoundedRectangle(cornerRadius: 26))

            card {
                sectionHeading("What to publish", "Choose which bundled content to serve.")
                VStack(spacing: 10) {
                    ForEach(contents.all, id: \.id) { content in
                        contentChoiceRow(content)
                    }
                }
            }
            feedback

            card {
                sectionHeading("Public URLs", "Only URLs from the current session appear here. URLs may change when relays change.")
                if model.publicUrls.isEmpty {
                    Label(model.hasSession ? "No URLs received yet" : "Your URLs will appear when you publish", systemImage: "link")
                        .font(.headline).padding(.top, 8)
                    Text(model.hasSession ? "Check the connection in Activity. Once a URL appears, open it in your browser to verify access." : "Choose a name and connection settings, then publish your site.")
                        .font(.subheadline).foregroundStyle(PortalStyle.muted)
                    if !model.hasSession && model.busy == nil {
                        Button("Explore publishing settings") { destination = 1 }
                    }
                } else {
                    ForEach(model.publicUrls, id: \.self) { url in
                        PortalPublicURLRow(value: url)
                    }
                }
            }
            card {
                Label("Keep the app open", systemImage: "iphone")
                    .font(.headline)
                Text("iOS limits background app execution. Leaving the app or locking the screen may interrupt the connection, but shutdown is not guaranteed to happen immediately. Keep this app in the foreground while serving your site.")
                    .font(.subheadline).foregroundStyle(PortalStyle.muted)
            }
        }
    }

    private var heroDescription: String {
        switch model.phase {
        case "active": return "The SDK reports an active session. Copy a current URL below or open it in your browser."
        case "starting", "connecting": return "Connecting your site to relays. Connection results reflect the actual SDK state."
        case "stopping": return "Waiting for shutdown to complete. If an error appears, you can request shutdown again."
        case "stopped": return "This publishing session has ended. You can adjust settings and start again."
        case "failed": return "The connection could not be completed. Review the error and your settings, then try again."
        default: return "Publish a static site from this device through a relay, without setting up a separate web server."
        }
    }

    @ViewBuilder
    private var feedback: some View {
        if model.warning {
            card {
                Label("Connection security warning", systemImage: "exclamationmark.shield.fill").foregroundStyle(.orange).font(.headline)
                Text("TLS interception is suspected on a relay. Check relay status in Activity before trusting its URLs or sending sensitive information.")
                    .font(.subheadline).foregroundStyle(PortalStyle.muted)
            }
        }
        if let error = model.lastError ?? model.lastFailure {
            card {
                Label("Something needs your attention", systemImage: "exclamationmark.triangle.fill").font(.headline).foregroundStyle(.orange)
                Text(error).font(.subheadline).textSelection(.enabled)
            }
        } else if let notice = model.notice {
            Text(notice).font(.subheadline).foregroundStyle(PortalStyle.mint)
                .accessibilityAddTraits(.updatesFrequently)
        }
    }

    private var settingsContent: some View {
        Group {
            Text("Changes apply the next time you publish. Your entries stay saved while you switch tabs.")
                .font(.subheadline).foregroundStyle(PortalStyle.muted)
            card {
                sectionHeading("Site information", "Choose a recognizable name and introduction.")
                field("Public name", hint: "The name for a new identity. If a saved identity exists, its original name may be used.", placeholder: "my-site", text: $model.name)
                field("Site description", hint: "A short description sent as public metadata.", placeholder: "What is your site about?", text: $model.configDescription)
                field("Tags", hint: "Separate public keywords with commas. Tags are independent of relay discovery.", placeholder: "portfolio,blog", text: $model.configTags)
            }
            card {
                sectionHeading("Relay selection", "Choose the relays that connect your device to visitors.")
                field("Manual relays", hint: "Separate relay URLs with commas. At least one is required when discovery is off.", placeholder: "https://relay.example", text: $model.relayInput)
                setting("Discover relays", explanation: "Automatically find relays to connect to. This does not list your site in a public directory.", value: $model.discovery)
                if model.missingRelay {
                    Text("Enable discovery or enter a relay URL.")
                        .font(.subheadline).foregroundStyle(.orange)
                }
            }
            card {
                sectionHeading("Connection and privacy", "Enable only what you need and review what you share.")
                setting("UDP forwarding", explanation: "Request UDP traffic forwarding. Availability depends on engine and relay support.", value: $model.udp)
                setting("TCP forwarding", explanation: "Request raw TCP traffic forwarding. This sample primarily publishes a static site.", value: $model.tcp)
                setting("Use ECH", explanation: "Encrypt TLS ClientHello information on supported connections. This does not hide all traffic information or your identity.", value: $model.ech)
                setting("Block TLS-intercepting relays", explanation: "Request blocking of relays suspected of TLS interception by the SDK's checks. Detection of every threat is not guaranteed.", value: $model.banMitm)
                setting("Hide from public listings", explanation: "Reduce directory visibility. This is not authentication or access control: anyone with the URL may still connect.", value: $model.hide)
            }
            card {
                sectionHeading("Files on this device", "Uses paths supplied by the host app. The site folder must contain index.html.")
                pathRow("Site folder", siteDir)
                pathRow("Session identity file", identityPath)
            }
        }
    }

    private var activityContent: some View {
        Group {
            card {
                HStack {
                    Label(model.phaseTitle, systemImage: "waveform.path").foregroundStyle(phaseColor).font(.headline)
                    Spacer()
                    Text("Revision #\(model.revision)").font(.caption).foregroundStyle(PortalStyle.muted)
                }
                Text("\(model.relayStatuses.count) relays · \(model.publicUrls.count) current URLs")
                    .font(.subheadline).foregroundStyle(PortalStyle.muted)
                if let busy = model.busy {
                    HStack { ProgressView(); Text(busy).font(.subheadline) }
                }
                if model.hasSession && model.phase != "stopping" {
                    Button { model.refresh() } label: {
                        Label("Refresh status", systemImage: "arrow.clockwise")
                    }
                    .disabled(!model.canManage)
                    Button { model.awaitReady() } label: {
                        Label("Check site readiness · up to 15 seconds", systemImage: "checkmark.circle")
                    }
                    .disabled(!model.canManage)
                } else {
                    Text(model.phase == "stopping" ? "You can retry shutdown from the Publish tab." : "Publish your site to access connection management.")
                        .font(.subheadline).foregroundStyle(PortalStyle.muted)
                }
            }
            feedback
            card {
                DisclosureGroup {
                    relayManagement.padding(.top, 16)
                } label: {
                    Label("Manage relays · \(model.relayStatuses.count)", systemImage: "point.3.connected.trianglepath.dotted")
                        .font(.headline)
                }
            }
            if model.hasSession && model.phase != "stopping" {
                card {
                    DisclosureGroup {
                        VStack(alignment: .leading, spacing: 20) {
                            Text("Applies to the current session only. Change defaults for your next session in Settings.")
                                .font(.subheadline).foregroundStyle(PortalStyle.muted)
                            field("Site description", hint: "Update the description in your public metadata.", placeholder: "Site description", text: $model.metaDescription)
                            field("Tags", hint: "Public keywords, separated by commas.", placeholder: "portfolio,blog", text: $model.metaTags)
                            field("Owner", hint: "Public owner information, not authentication credentials.", placeholder: "Owner information", text: $model.metaOwner)
                            setting("Hide from public listings", explanation: "Controls directory visibility only. It does not block access through the URL.", value: $model.metaHide)
                            Button("Apply to current session") { model.updateMetadata() }.disabled(!model.canManage)
                        }.padding(.top, 16)
                    } label: {
                        Label("Edit public information", systemImage: "square.and.pencil").font(.headline)
                    }
                }
            }
            card {
                DisclosureGroup {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("At startup, the session reads its identity from the specified file or creates one. This tool creates a separate identity and displays its address; it does not save it or apply it to the current session.")
                            .font(.subheadline).foregroundStyle(PortalStyle.muted)
                        if !model.identityAddress.isEmpty {
                            Text(model.identityAddress).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                        }
                        Button("Generate separate identity") { model.generateIdentity() }.disabled(model.busy != nil)
                    }.padding(.top, 16)
                } label: {
                    Label("Identity tools", systemImage: "person.crop.square").font(.headline)
                }
            }
            card {
                DisclosureGroup {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Showing the latest 20 events. Events missed while observation was paused are not replayed.")
                            .font(.caption).foregroundStyle(PortalStyle.muted)
                        if model.eventLog.isEmpty {
                            Text("No events received yet.").foregroundStyle(PortalStyle.muted)
                        }
                        ForEach(Array(model.eventLog.suffix(20).reversed().enumerated()), id: \.offset) { entry in
                            Text(entry.element).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                            Divider()
                        }
                    }.padding(.top, 16)
                } label: {
                    Label("Recent events", systemImage: "text.alignleft").font(.headline)
                }
            }
            card {
                DisclosureGroup {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Check SDK versions and session counts when investigating connection problems.")
                            .font(.subheadline).foregroundStyle(PortalStyle.muted)
                        if !model.diagnosticsText.isEmpty {
                            Text(model.diagnosticsText).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                        }
                        Button("Load diagnostics") { model.loadDiagnostics() }
                    }.padding(.top, 16)
                } label: {
                    Label("SDK diagnostics", systemImage: "stethoscope").font(.headline)
                }
            }
        }
    }

    private var relayManagement: some View {
        VStack(alignment: .leading, spacing: 20) {
            if model.relayStatuses.isEmpty {
                Text("No relays reported yet. Choose relays in Settings before starting.")
                    .font(.subheadline).foregroundStyle(PortalStyle.muted)
            }
            ForEach(Array(model.relayStatuses.enumerated()), id: \.offset) { entry in
                let relay = entry.element
                VStack(alignment: .leading, spacing: 10) {
                    Text(relay.relayUrl).font(.system(.subheadline, design: .monospaced)).textSelection(.enabled)
                    Text(relay.isMitm ? "Suspected TLS interception" : relay.isReady ? "Ready" : relay.isConnecting ? "Connecting" : relay.isFailed ? "Failed" : relay.state)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(relay.isMitm || relay.isFailed ? Color.orange : PortalStyle.mint)
                    if let error = relay.error { Text(error).font(.caption).foregroundStyle(.orange) }
                    if let failure = relay.failure { Text("Failure details · \(failure)").font(.caption).foregroundStyle(.orange) }
                    if let version = relay.version { Text("Relay version · \(version)").font(.caption).foregroundStyle(PortalStyle.muted) }
                    if model.hasSession {
                        if let address = relay.udpAddr { pathRow("UDP address", address) }
                        if let address = relay.tcpAddr { pathRow("TCP address", address) }
                    }
                    if model.hasSession && model.phase != "stopping" {
                        Button("Remove this relay", role: .destructive) { model.removeRelay(relay.relayUrl) }
                            .disabled(!model.canManage)
                    }
                }
                Divider()
            }
            if model.hasSession && model.phase != "stopping" {
                field("Add relay", hint: "Enter one relay URL to connect to the current session.", placeholder: "https://relay.example", text: $model.newRelay)
                Button("Add relay to current session") { model.addRelay() }
                    .disabled(!model.canManage || model.newRelay.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 18, content: content)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(22)
            .background(PortalStyle.card)
            .clipShape(RoundedRectangle(cornerRadius: 22))
    }

    private func sectionHeading(_ title: String, _ explanation: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.title3.bold())
            Text(explanation).font(.subheadline).foregroundStyle(PortalStyle.muted)
        }
    }

    private func field(_ title: String, hint: String, placeholder: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(title).font(.subheadline.weight(.semibold))
            Text(hint).font(.caption).foregroundStyle(PortalStyle.muted)
            TextField(placeholder, text: text, axis: .vertical)
                .font(.body)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(14)
                .background(PortalStyle.background)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .accessibilityLabel(title)
        }
    }

    private func setting(_ title: String, explanation: String, value: Binding<Bool>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(title, isOn: value).font(.subheadline.weight(.semibold)).tint(PortalStyle.mint)
            Text(explanation).font(.caption).foregroundStyle(PortalStyle.muted)
        }
        .padding(.vertical, 5)
    }

    private func pathRow(_ title: String, _ path: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption).foregroundStyle(PortalStyle.muted)
            Text(path).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
        }
    }

    private func contentChoiceRow(_ content: SampleContent) -> some View {
        let active = model.contentId == content.id
        return Button {
            model.contentId = content.id
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(content.title)
                    .font(.headline)
                    .foregroundStyle(active ? PortalStyle.cyan : Color.white)
                Text(content.summary)
                    .font(.subheadline)
                    .foregroundStyle(PortalStyle.muted)
                if let detail = content.detail {
                    Text(detail)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(PortalStyle.mint)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(active ? PortalStyle.cyan.opacity(0.12) : PortalStyle.background)
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .disabled(model.hasSession || model.busy != nil)
    }
}

private struct PortalPublicURLRow: View {
    let value: String
    @State private var copied = false

    private var browserURL: URL? {
        guard let url = URL(string: value),
              let scheme = url.scheme?.lowercased(),
              ["http", "https"].contains(scheme), url.host != nil else { return nil }
        return url
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(value).font(.system(.subheadline, design: .monospaced)).textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 18) { actions }
                VStack(alignment: .leading, spacing: 14) { actions }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(PortalStyle.background)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    @ViewBuilder
    private var actions: some View {
        Button {
            UIPasteboard.general.string = value
            copied = true
            UIAccessibility.post(notification: .announcement, argument: "URL copied")
        } label: {
            Label(copied ? "Copied" : "Copy URL", systemImage: copied ? "checkmark" : "doc.on.doc")
        }
        .buttonStyle(.bordered)
        .accessibilityLabel(copied ? "URL copied, copy again" : "Copy URL")
        if let url = browserURL {
            Link(destination: url) {
                Label("Open", systemImage: "arrow.up.right.square")
            }
            .accessibilityLabel("Open URL in browser")
        }
    }
}

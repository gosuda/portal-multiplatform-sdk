import SwiftUI
import UIKit
import PortalSDK

/// Owns the client independently of the selected destination. Observation is
/// view-scoped; cancelling a subscription does not stop the native session.
/// Mirrors the Android sample's MainActivity + SampleScreen split.
@MainActor
final class PortalHomeModel: ObservableObject {
    // Start settings — drafts survive destination switches.
    @Published var name = "snake-game"
    @Published var configDescription = "Snake game served from this device"
    @Published var configTags = "game,snake"
    @Published var relayInput = ""
    @Published var discovery = true
    @Published var udp = false
    @Published var tcp = false
    @Published var ech = false
    @Published var banMitm = false
    @Published var hide = false
    @Published var contentId = "snake"

    // Live metadata edits (apply to the running session only).
    @Published var metaDescription = ""
    @Published var metaTags = ""
    @Published var metaOwner = ""
    @Published var metaHide = false
    @Published var newRelay = ""

    // Observed state — the snapshot is authoritative.
    @Published private(set) var snapshot: PortalSnapshot?
    @Published private(set) var lastError: String?
    @Published private(set) var identity: PortalIdentity?
    @Published private(set) var eventLog: [String] = []
    @Published private(set) var diagnostics: PortalDiagnostics?
    @Published private(set) var busy = false
    @Published private(set) var starting = false
    @Published private(set) var toast: String?

    private let client = PortalIosClient(allowRemoteTargets: false, defaultIdentityPath: nil)
    private var session: PortalIosSession?
    private var stateSub: PortalSubscription?
    private var eventSub: PortalSubscription?
    private var startOp: PortalOperation?
    private var activeContent: SampleContent?
    private var pendingContent: SampleContent?
    private var observing = false
    private var toastTask: Task<Void, Never>?

    var running: Bool { snapshot.map { !$0.isTerminal } ?? false }
    var editable: Bool { !running && !busy }
    var operable: Bool { running && !busy && snapshot?.phase != .stopping }
    var missingRelay: Bool { !discovery && commaSeparated(relayInput).isEmpty }
    var urls: [String] { running ? (snapshot?.publicUrls ?? []) : [] }

    // ---- actions -----------------------------------------------------------

    func start(contents: SampleContents, identityPath: String) {
        guard !busy, !running else { return }
        lastError = nil
        guard let content = contents.byId(contentId) else {
            lastError = "Unknown content: \(contentId)"
            return
        }
        busy = true
        starting = true
        do {
            try content.start()
        } catch {
            busy = false
            starting = false
            lastError = "Could not start publishing: \(error.localizedDescription)"
            return
        }
        pendingContent = content
        let resolvedIdentityPath: String
        do {
            resolvedIdentityPath = try resolveIdentityFile(path: identityPath)
        } catch {
            busy = false
            starting = false
            pendingContent = nil
            content.stop()
            lastError = "Could not prepare identity: \(error.localizedDescription)"
            return
        }
        let config = PortalIosConfigFactory.shared.custom(
            name: name.trimmingCharacters(in: .whitespaces).isEmpty ? nil : name.trimmingCharacters(in: .whitespaces),
            identityJson: nil,
            identityPath: resolvedIdentityPath,
            relays: commaValues(relayInput),
            discovery: discovery,
            maxActiveRelays: 3,
            banMitm: banMitm, ech: ech,
            udp: udp, tcp: content.tcp,
            description: configDescription.isEmpty ? nil : configDescription,
            tags: commaValues(configTags),
            owner: nil, thumbnail: nil, hide: hide,
            staticDir: content.staticDir, staticIndex: content.staticIndex,
            targetAddr: content.targetAddr, udpAddr: nil, httpRoutes: nil, x402: nil
        )
        startOp = client.publish(config: config, timeoutMillis: 30_000) { [weak self] session, failure in
            guard let self else { return }
            self.startOp = nil
            self.busy = false
            self.starting = false
            self.pendingContent = nil
            if let failure {
                content.stop()
                self.lastError = "Could not start publishing: \(failure.code) \(failure.message)"
                return
            }
            guard let session else {
                content.stop()
                self.lastError = "Could not start publishing: the SDK did not return a session."
                return
            }
            self.activeContent = content
            self.session = session
            self.metaDescription = self.configDescription
            self.metaTags = self.configTags
            self.metaOwner = ""
            self.metaHide = self.hide
            self.apply(session.snapshot)
            if self.observing { self.attach() }
        }
    }

    /// The public URL is `<identity-name>.<relay-domain>`, so the name field
    /// only takes effect when the identity itself changes. When the saved
    /// identity's name differs from the configured name, a fresh identity is
    /// generated and written over the file; an empty name keeps whatever is
    /// on disk (or lets the engine create one).
    private func resolveIdentityFile(path: String) throws -> String {
        let wanted = name.trimmingCharacters(in: .whitespaces)
        guard !wanted.isEmpty else { return path }
        let url = URL(fileURLWithPath: path)
        let current: String? = try? {
            guard FileManager.default.fileExists(atPath: path) else { return nil }
            let data = try Data(contentsOf: url)
            return try PortalIdentity.companion.parse(identityJson: String(decoding: data, as: UTF8.self)).name
        }()
        if current != wanted {
            let fresh = try PortalIdentity.companion.generate(name: wanted)
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try fresh.document.write(to: url, atomically: true, encoding: .utf8)
        }
        return path
    }

    /// Cancels an in-flight start; the SDK rolls the native handle back.
    func cancelStart() {
        startOp?.cancel()
        startOp = nil
        pendingContent?.stop()
        pendingContent = nil
        busy = false
        starting = false
    }

    func stop() {
        guard !busy, running, let session else { return }
        busy = true
        session.stop { [weak self] failure in
            guard let self else { return }
            self.busy = false
            self.apply(session.snapshot)
            self.lastError = failure.map { "Could not stop. Try again: \($0.code) \($0.message)" }
        }
    }

    func refresh() {
        guard let session else { return }
        session.refresh { [weak self] failure in
            guard let self else { return }
            self.apply(session.snapshot)
            if let failure {
                self.lastError = "refresh failed: \(failure.code) \(failure.message)"
            }
        }
    }

    func awaitReady() {
        guard let session else { return }
        session.awaitActive(timeoutMillis: 15_000) { [weak self] snapshot, failure in
            guard let self else { return }
            self.apply(snapshot ?? session.snapshot)
            if let failure {
                self.lastError = "awaitReady failed: \(failure.code) \(failure.message)"
            }
        }
    }

    func generateIdentity() {
        do {
            identity = try PortalIdentity.companion.generate(name: "kmp-sample")
        } catch {
            lastError = "identity failed: \(error.localizedDescription)"
        }
    }

    func updateMetadata() {
        guard let session else { return }
        let metadata = PortalMetadata(
            description: metaDescription.isEmpty ? nil : metaDescription,
            tags: commaValues(metaTags),
            owner: metaOwner.isEmpty ? nil : metaOwner,
            thumbnail: nil,
            hide: KotlinBoolean(bool: metaHide)
        )
        session.updateMetadata(metadata: metadata) { [weak self] failure in
            if let failure {
                self?.lastError = "metadata failed: \(failure.code) \(failure.message)"
            }
        }
    }

    func addRelay() {
        let url = newRelay.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty, let session else { return }
        session.addRelay(relayUrl: url) { [weak self] failure in
            guard let self else { return }
            if failure == nil, self.newRelay.trimmingCharacters(in: .whitespacesAndNewlines) == url {
                self.newRelay = ""
            }
            self.apply(session.snapshot)
            if let failure {
                self.lastError = "addRelay failed: \(failure.code) \(failure.message)"
            }
        }
    }

    func removeRelay(_ url: String) {
        guard let session else { return }
        session.removeRelay(relayUrl: url) { [weak self] failure in
            guard let self else { return }
            self.apply(session.snapshot)
            if let failure {
                self.lastError = "removeRelay failed: \(failure.code) \(failure.message)"
            }
        }
    }

    func loadDiagnostics() {
        diagnostics = client.diagnostics()
    }

    /// Snackbar-equivalent transient message.
    func notify(_ message: String) {
        toastTask?.cancel()
        toast = message
        toastTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            self?.toast = nil
        }
    }

    // ---- observation ---------------------------------------------------------

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
        self.snapshot = snapshot
        if snapshot.isTerminal {
            activeContent?.stop()
            activeContent = nil
        }
    }

    // ---- helpers -------------------------------------------------------------

    private func commaSeparated(_ value: String) -> [String] {
        value.split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private func commaValues(_ value: String) -> [String]? {
        commaSeparated(value).isEmpty ? nil : commaSeparated(value)
    }

    private func describe(_ event: PortalEvent) -> String {
        switch event {
        case let e as PortalEvent.Started: return "STARTED \(e.name)"
        case is PortalEvent.Stopped: return "STOPPED"
        case let e as PortalEvent.StatusChanged:
            return "STATUS_CHANGED active=\(e.status.active) relays=\(e.status.relays.count)"
        case let e as PortalEvent.MitmSuspected: return "MITM_SUSPECTED \(e.relayUrl)"
        case let e as PortalEvent.RelayAdded: return "RELAY_ADDED \(e.relayUrl)"
        case let e as PortalEvent.RelayRemoved: return "RELAY_REMOVED \(e.relayUrl)"
        case let e as PortalEvent.Error: return "ERROR \(e.message)"
        case let e as PortalEvent.Unknown: return "UNKNOWN \(e.type)"
        default: return "EVENT"
        }
    }
}

// MARK: - Design tokens (mirrors the Android sample palette)

private enum PortalStyle {
    static let bg = Color(red: 8 / 255, green: 15 / 255, blue: 29 / 255)
    static let surface1 = Color(red: 17 / 255, green: 30 / 255, blue: 48 / 255)
    static let surface2 = Color(red: 27 / 255, green: 45 / 255, blue: 66 / 255)
    static let cyan = Color(red: 100 / 255, green: 220 / 255, blue: 236 / 255)
    static let mint = Color(red: 160 / 255, green: 240 / 255, blue: 204 / 255)
    static let textPrimary = Color(red: 240 / 255, green: 245 / 255, blue: 252 / 255)
    static let textSecondary = Color(red: 167 / 255, green: 184 / 255, blue: 206 / 255)
    static let danger = Color(red: 255 / 255, green: 162 / 255, blue: 155 / 255)
}

// MARK: - Screen

@MainActor
struct PortalHomeView: View {
    @StateObject private var model = PortalHomeModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var destination = 0
    @State private var expandedPanel = "session"
    let contents: SampleContents
    let identityPath: String

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    header
                    attentionPanel
                    if destination == 0 { publishItems }
                    else if destination == 1 { settingsItems }
                    else { activityItems }
                }
                .padding(.horizontal, 20)
                .padding(.top, 24)
                .padding(.bottom, 24)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
            }
            .background(PortalStyle.bg)
            .overlay(alignment: .bottom) { toastView }
            bottomBar
        }
        .background(PortalStyle.bg.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .onAppear { model.onAppear() }
        .onDisappear { model.onDisappear() }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active: model.onAppear()
            case .background: model.onDisappear()
            default: break
            }
        }
    }

    // ---- header & attention ---------------------------------------------------

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("PORTAL / from this device to the web")
                .font(.caption.weight(.medium)).tracking(1)
                .foregroundStyle(PortalStyle.cyan)
            Text(destination == 1 ? "Your settings" : destination == 2 ? "Publishing activity" : "Small game, wide world")
                .font(.system(size: 27, weight: .bold))
        }
    }

    @ViewBuilder
    private var attentionPanel: some View {
        if model.lastError != nil || model.snapshot?.lastFailure != nil || model.snapshot?.hasSecurityWarning == true {
            panel("Needs attention") {
                if model.snapshot?.hasSecurityWarning == true {
                    Text("TLS interception suspected on a relay. Do not trust that endpoint; check the connection.")
                        .foregroundStyle(PortalStyle.danger)
                }
                if let failure = model.snapshot?.lastFailure {
                    Text("\(failure.code): \(failure.message)").foregroundStyle(PortalStyle.danger)
                }
                if let error = model.lastError {
                    Text(error).foregroundStyle(PortalStyle.danger)
                }
            }
        }
    }

    // ---- Publish ---------------------------------------------------------------

    @ViewBuilder
    private var publishItems: some View {
        publishHero
        panel("What to publish") {
            ForEach(contents.all, id: \.id) { content in
                contentChoice(content)
            }
        }
        panel(model.urls.isEmpty ? "Waiting for a public link" : "Share this link now") {
            if model.urls.isEmpty {
                Text(model.running
                     ? "The relay will send an address here. No link to share yet."
                     : "Tap 'Publish' below to publish from this device. Share the address you receive after the relay connects.")
                    .foregroundStyle(PortalStyle.textSecondary).font(.subheadline)
            } else {
                Text("The address can change as relays join/leave. Share the current link.")
                    .foregroundStyle(PortalStyle.textSecondary).font(.caption)
                ForEach(Array(model.urls.enumerated()), id: \.offset) { entry in
                    publicUrlRow(index: entry.offset, url: entry.element)
                }
            }
        }
        panel("Before you publish") {
            Text("This device serves the content. The app and network must stay connected for visitors to reach it — iOS suspends apps in the background.")
                .foregroundStyle(PortalStyle.textSecondary)
            Text("Keep the app in the foreground while serving.")
                .foregroundStyle(PortalStyle.mint).font(.caption)
            Button("Review publish settings →") { destination = 1 }
                .foregroundStyle(PortalStyle.cyan)
        }
    }

    private var publishHero: some View {
        VStack(alignment: .leading, spacing: 12) {
            phaseBadge(model.snapshot?.phase)
            Text(model.running ? "Publishing from this device" : "This device is\nthe site's start.")
                .font(.system(size: model.running ? 24 : 32, weight: .bold))
                .tracking(-1)
                .fixedSize(horizontal: false, vertical: true)
            Text(heroName)
                .foregroundStyle(PortalStyle.mint).font(.headline)
            if !model.running {
                contentIllustration
                Text("\(selectedContentTitle) · preview")
                    .foregroundStyle(PortalStyle.textSecondary).font(.caption)
            }
        }
        .padding(22)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(
            colors: [Color(red: 25 / 255, green: 57 / 255, blue: 74 / 255), PortalStyle.surface1],
            startPoint: .topLeading, endPoint: .bottomTrailing))
        .clipShape(RoundedRectangle(cornerRadius: 28))
    }

    private var heroName: String {
        let n = model.snapshot?.nativeStatus?.name ?? model.name
        return n.isEmpty ? "My site" : n
    }

    private var selectedContentTitle: String {
        contents.byId(model.contentId)?.title ?? "Content"
    }

    /// Dot-grid board with a per-content motif — the Android sample draws a
    /// Snake preview; other contents get a matching minimal glyph.
    private var contentIllustration: some View {
        Canvas { ctx, size in
            let board = Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 18)
            ctx.fill(board, with: .color(PortalStyle.bg.opacity(0.75)))
            let cell: CGFloat = 17
            let left = (size.width - cell * 11) / 2
            let top = (size.height - cell * 6) / 2
            for x in 0...10 {
                for y in 0...5 {
                    ctx.fill(
                        Path(ellipseIn: CGRect(x: left + CGFloat(x) * cell - 0.5, y: top + CGFloat(y) * cell - 0.5, width: 1, height: 1)),
                        with: .color(PortalStyle.textSecondary.opacity(0.17)))
                }
            }
            func block(_ gx: CGFloat, _ gy: CGFloat, _ color: Color) {
                let rect = CGRect(x: left + gx * cell - cell * 0.43, y: top + gy * cell - cell * 0.43,
                                  width: cell * 0.86, height: cell * 0.86)
                ctx.fill(Path(roundedRect: rect, cornerRadius: 4), with: .color(color))
            }
            switch model.contentId {
            case "minecraft":
                // Blocky pickaxe: handle diagonal + head row.
                for (i, p) in [(4.0, 4.0), (5.0, 3.0), (6.0, 2.0)].enumerated() {
                    block(p.0, p.1, PortalStyle.cyan.opacity(0.45 + Double(i) * 0.15))
                }
                block(6, 1, PortalStyle.mint); block(7, 1, PortalStyle.mint); block(7, 2, PortalStyle.mint)
            case "ondevice":
                // Chip: body + pins.
                block(4, 2, PortalStyle.cyan); block(5, 2, PortalStyle.cyan)
                block(4, 3, PortalStyle.cyan); block(5, 3, PortalStyle.cyan)
                block(5, 2.5, PortalStyle.mint)
                for gx in [3.0, 6.0] { block(gx, 2, PortalStyle.mint.opacity(0.6)); block(gx, 3, PortalStyle.mint.opacity(0.6)) }
            case "explainer":
                // device → relay → visitor link.
                block(2, 3, PortalStyle.cyan); block(5, 2, PortalStyle.mint); block(8, 3, PortalStyle.cyan)
                var link = Path()
                link.move(to: CGPoint(x: left + 3 * cell, y: top + 3 * cell))
                link.addLine(to: CGPoint(x: left + 5 * cell, y: top + 2.5 * cell))
                link.addLine(to: CGPoint(x: left + 8 * cell, y: top + 3 * cell))
                ctx.stroke(link, with: .color(PortalStyle.textSecondary.opacity(0.6)), lineWidth: 1.5)
            default:
                // Snake path + food, same as the Android illustration.
                let snake = [(1, 4), (2, 4), (3, 4), (3, 3), (3, 2), (4, 2), (5, 2), (6, 2), (7, 2)]
                for (i, p) in snake.enumerated() {
                    block(CGFloat(p.0), CGFloat(p.1),
                          i == snake.count - 1 ? PortalStyle.mint : PortalStyle.cyan.opacity(0.45 + Double(i) * 0.05))
                }
                ctx.fill(Path(ellipseIn: CGRect(x: left + 7.18 * cell - 1.7, y: top + 1.83 * cell - 1.7, width: 3.4, height: 3.4)),
                         with: .color(PortalStyle.bg))
                ctx.fill(Path(ellipseIn: CGRect(x: left + 9 * cell - 15, y: top + 2 * cell - 15, width: 30, height: 30)),
                         with: .color(PortalStyle.danger.opacity(0.12)))
                ctx.fill(Path(ellipseIn: CGRect(x: left + 9 * cell - 5, y: top + 2 * cell - 5, width: 10, height: 10)),
                         with: .color(PortalStyle.danger))
            }
        }
        .frame(height: 146)
        .accessibilityLabel("\(selectedContentTitle) preview")
    }

    private func publicUrlRow(index: Int, url: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Public address \(index + 1)")
                .font(.caption).foregroundStyle(PortalStyle.textSecondary)
            Text(url)
                .font(.system(.subheadline, design: .monospaced))
                .foregroundStyle(PortalStyle.mint)
                .textSelection(.enabled)
            HStack(spacing: 8) {
                actionButton("Copy link") {
                    UIPasteboard.general.string = url
                    model.notify("Copied public address \(index + 1).")
                }
                .accessibilityLabel("Copy public address \(index + 1)")
                if let browserURL = URL(string: url),
                   let scheme = browserURL.scheme?.lowercased(),
                   ["http", "https"].contains(scheme), browserURL.host != nil {
                    actionButton("Open") { UIApplication.shared.open(browserURL) }
                        .accessibilityLabel("Open public address \(index + 1) in browser")
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PortalStyle.bg)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    // ---- Settings ---------------------------------------------------------------

    @ViewBuilder
    private var settingsItems: some View {
        Text(model.running
             ? "Cannot edit start settings while publishing. Stop publishing below, then edit. Live info can be changed in Activity."
             : "Change what you need, then publish with the button below.")
            .foregroundStyle(PortalStyle.textSecondary)
        panel("01 / Basics") {
            portalField("Public name", hint: "Becomes the address prefix (<name>.<relay>). Changing it creates a new identity on next publish.",
                        text: $model.name, enabled: model.editable)
            portalField("Description", hint: "A sentence describing the site. May appear in the public directory.",
                        text: $model.configDescription, enabled: model.editable)
            portalField("Tags", hint: "Comma-separated. e.g. game, snake",
                        text: $model.configTags, enabled: model.editable)
        }
        panel("02 / Relay connection") {
            Text("Relays carry traffic between this device and visitors.")
                .foregroundStyle(PortalStyle.textSecondary)
            settingRow("Auto-discover relays", hint: "Finds relays to use. Does not list your site in the public directory.",
                       value: $model.discovery, enabled: model.editable)
            portalField("Relay address (manual)", hint: "Comma-separated. At least one required when auto-discovery is off.",
                        text: $model.relayInput, enabled: model.editable, isError: model.missingRelay)
        }
        panel("03 / Visibility & security") {
            settingRow("Hide from public directory", hint: "Reduces listing exposure. Not authentication or access control — anyone with the URL can connect.",
                       value: $model.hide, enabled: model.editable)
            settingRow("Use ECH", hint: "Requests TLS ClientHello protection. Depends on relay support; does not guarantee full traffic anonymity.",
                       value: $model.ech, enabled: model.editable)
            settingRow("Block MITM relays", hint: "Rejects relays where TLS interception is detected. Does not guarantee all connections are safe.",
                       value: $model.banMitm, enabled: model.editable)
        }
        panel("04 / Protocols") {
            Text("Static sites are served over the web. Enable extra protocols only if you need other traffic.")
                .foregroundStyle(PortalStyle.textSecondary)
            settingRow("UDP relay", hint: "Requests UDP relay for games, QUIC, etc.",
                       value: $model.udp, enabled: model.editable)
            settingRow("TCP relay", hint: "Requests raw TCP relay beyond web publishing. The Minecraft content enables it automatically.",
                       value: $model.tcp, enabled: model.editable)
        }
        panel("05 / Files on this device") {
            Text("Paths supplied by the host app. The site folder must contain index.html.")
                .foregroundStyle(PortalStyle.textSecondary)
            pathRow("Site folder", contents.siteDir)
            pathRow("Explainer folder", contents.explainerDir)
            pathRow("Session identity file", identityPath)
        }
    }

    // ---- Activity ---------------------------------------------------------------

    @ViewBuilder
    private var activityItems: some View {
        Text("Inspect the connection and manage live publish info.")
            .foregroundStyle(PortalStyle.textSecondary)
        activityPanel("session", title: "Connection status") {
            phaseBadge(model.snapshot?.phase)
            if let s = model.snapshot {
                Text("Revision \(s.revision) · dropped events \(s.droppedEventCount)")
                    .foregroundStyle(PortalStyle.textSecondary).font(.caption)
            }
            if model.running {
                actionButton("Refresh status") { model.refresh() }.disabled(!model.operable)
                actionButton("Await ready") { model.awaitReady() }.disabled(!model.operable)
            } else {
                Text("No active publish. Start publishing to see connection status.")
                    .foregroundStyle(PortalStyle.textSecondary)
            }
        }
        activityPanel("metadata", title: "Edit public info") {
            Text(model.running
                 ? "Changes metadata for the currently running publish. Separate from next-start settings."
                 : "Change description, tags, owner, and listing visibility live after publishing.")
                .foregroundStyle(PortalStyle.textSecondary)
            portalField("New description", hint: "Description shown in the public directory. Empty values are excluded from the update.",
                        text: $model.metaDescription, enabled: model.operable)
            portalField("New tags", hint: "Comma-separated. Empty values are excluded from the update.",
                        text: $model.metaTags, enabled: model.operable)
            portalField("Owner", hint: "Name of who runs this publish. Empty values are excluded from the update.",
                        text: $model.metaOwner, enabled: model.operable)
            settingRow("Hide from public directory", hint: "Does not block URL access or add authentication.",
                       value: $model.metaHide, enabled: model.operable)
            if model.running {
                actionButton("Apply public info") { model.updateMetadata() }.disabled(!model.operable)
            }
        }
        activityPanel("relays", title: "Relay management · \(model.snapshot?.relays.count ?? 0)") {
            Text("Add or remove relays while running. Public addresses may change as connections shift.")
                .foregroundStyle(PortalStyle.textSecondary)
            ForEach(Array((model.snapshot?.relays ?? []).enumerated()), id: \.offset) { entry in
                relayRow(entry.element)
            }
            if model.running {
                portalField("Relay address to add", hint: "Enter one relay address to connect.",
                            text: $model.newRelay, enabled: model.operable)
                actionButton("Add relay") { model.addRelay() }
                    .disabled(!model.operable || model.newRelay.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            } else {
                Text("Set start relays in Settings.").foregroundStyle(PortalStyle.mint)
            }
        }
        activityPanel("identity", title: "Device identity") {
            if let identity = model.identity {
                Text("Name: \(identity.name)\nAddress: \(identity.address)")
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(PortalStyle.mint)
                    .textSelection(.enabled)
            } else {
                Text("No identity to show yet. One is created on first start or a saved identity is used.")
                    .foregroundStyle(PortalStyle.textSecondary)
            }
            Text("Generate a new identity to inspect. Does not change the running publish's identity.")
                .foregroundStyle(PortalStyle.textSecondary)
            actionButton("Generate new identity") { model.generateIdentity() }.disabled(!model.editable)
        }
        activityPanel("events", title: "Event history · \(model.eventLog.count)") {
            if model.eventLog.isEmpty {
                Text("No history yet. Connection activity will appear here.")
                    .foregroundStyle(PortalStyle.textSecondary)
            } else {
                Text(model.eventLog.joined(separator: "\n"))
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(PortalStyle.textSecondary)
                    .textSelection(.enabled)
            }
        }
        activityPanel("diagnostics", title: "Diagnostics") {
            if let d = model.diagnostics {
                Text("SDK \(d.sdkVersion) · ABI \(d.abiVersion) · wire schema \(d.wireSchemaVersion)\nActive sessions \(d.activeSessions) · orphan drops \(d.droppedOrphanEvents)")
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(PortalStyle.textSecondary)
                    .textSelection(.enabled)
            } else {
                Text("Load SDK and session info when troubleshooting.")
                    .foregroundStyle(PortalStyle.textSecondary)
            }
            actionButton("Load diagnostics") { model.loadDiagnostics() }.disabled(model.busy)
        }
    }

    private func relayRow(_ relay: PortalRelayStatus) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(!model.running ? "Ended"
                 : relay.isMitm ? "MITM suspected"
                 : relay.isFailed ? "Failed"
                 : relay.isReady ? "Ready"
                 : "State: \(relay.state)")
                .font(.caption.weight(.medium))
                .foregroundStyle(relay.isMitm || relay.isFailed ? PortalStyle.danger : PortalStyle.textSecondary)
            Text(relay.relayUrl)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(PortalStyle.textPrimary)
                .textSelection(.enabled)
            if let error = relay.error {
                Text(error).foregroundStyle(PortalStyle.danger).font(.caption)
            }
            if model.running {
                actionButton("Remove this relay") { model.removeRelay(relay.relayUrl) }
                    .disabled(!model.operable)
                    .accessibilityLabel("Remove relay \(relay.relayUrl)")
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PortalStyle.bg)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    // ---- Bottom bar ---------------------------------------------------------------

    private var bottomBar: some View {
        VStack(spacing: 0) {
            VStack(spacing: 8) {
                if model.missingRelay && !model.running {
                    Text("Enable auto-discovery or enter a relay address.")
                        .font(.caption).foregroundStyle(PortalStyle.danger)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Button {
                    if model.running { model.stop() }
                    else { model.start(contents: contents, identityPath: identityPath) }
                } label: {
                    Text(model.busy ? "Processing…"
                         : model.snapshot?.phase == .stopping ? "Retry stop"
                         : model.running ? "Stop publishing" : "Publish")
                        .font(.system(size: 17, weight: .bold))
                        .frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.borderedProminent)
                .tint(model.running ? PortalStyle.surface2 : PortalStyle.cyan)
                .foregroundStyle(model.running ? PortalStyle.textPrimary : PortalStyle.bg)
                .clipShape(RoundedRectangle(cornerRadius: 18))
                .disabled(model.busy || (!model.running && model.missingRelay))
                if model.starting {
                    Button("Cancel start") { model.cancelStart() }
                        .font(.subheadline).foregroundStyle(PortalStyle.textSecondary)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            tabBar
        }
        .background(PortalStyle.bg)
        .shadow(color: .black.opacity(0.4), radius: 12, y: -2)
    }

    private var tabBar: some View {
        HStack(spacing: 0) {
            tabItem(0, "Publish", "arrow.up.right.circle.fill")
            tabItem(1, "Settings", "slider.horizontal.3")
            tabItem(2, "Activity", "waveform.path")
        }
        .padding(.bottom, 4)
    }

    private func tabItem(_ index: Int, _ label: String, _ icon: String) -> some View {
        Button { destination = index } label: {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.body)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
                    .background(destination == index ? PortalStyle.surface2 : .clear)
                    .clipShape(Capsule())
                Text(label).font(.caption.weight(.semibold))
            }
            .foregroundStyle(destination == index ? PortalStyle.cyan : PortalStyle.textSecondary)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var toastView: some View {
        if let toast = model.toast {
            Text(toast)
                .font(.subheadline)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(PortalStyle.surface2)
                .clipShape(Capsule())
                .padding(.bottom, 12)
                .transition(.opacity)
        }
    }

    // ---- Shared components ---------------------------------------------------------------

    private func phaseBadge(_ phase: TunnelPhase?) -> some View {
        let label: String
        switch phase {
        case .starting: label = "Starting"
        case .connecting: label = "Connecting to relay"
        case .active: label = "Publishing"
        case .stopping: label = "Stopping"
        case .stopped: label = "Stopped"
        case .failed: label = "Failed"
        default: label = "Ready to publish"
        }
        let color: Color = phase == .active ? PortalStyle.mint
            : phase == .failed ? PortalStyle.danger : PortalStyle.cyan
        return Text(label)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(color.opacity(0.12))
            .clipShape(RoundedRectangle(cornerRadius: 30))
    }

    private func panel<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.system(size: 17, weight: .semibold))
            content()
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PortalStyle.surface1)
        .clipShape(RoundedRectangle(cornerRadius: 22))
    }

    private func activityPanel<Content: View>(_ key: String, title: String,
                                              @ViewBuilder content: () -> Content) -> some View {
        VStack(spacing: 0) {
            Button {
                expandedPanel = expandedPanel == key ? "" : key
            } label: {
                HStack {
                    Text(title)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(PortalStyle.textPrimary)
                    Spacer()
                    Text(expandedPanel == key ? "Collapse" : "Expand")
                        .font(.subheadline)
                        .foregroundStyle(PortalStyle.cyan)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 14)
                .frame(minHeight: 64)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if expandedPanel == key {
                VStack(alignment: .leading, spacing: 14, content: content)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 20)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PortalStyle.surface1)
        .clipShape(RoundedRectangle(cornerRadius: 22))
    }

    private func portalField(_ label: String, hint: String, text: Binding<String>,
                             enabled: Bool = true, isError: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField(label, text: text)
                .font(.body)
                .padding(12)
                .background(PortalStyle.bg)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14)
                    .stroke(isError ? PortalStyle.danger : PortalStyle.surface2))
                .disabled(!enabled)
                .foregroundStyle(enabled ? PortalStyle.textPrimary : PortalStyle.textSecondary)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Text(hint)
                .font(.caption)
                .foregroundStyle(isError ? PortalStyle.danger : PortalStyle.textSecondary)
        }
    }

    private func settingRow(_ label: String, hint: String, value: Binding<Bool>,
                            enabled: Bool = true) -> some View {
        Toggle(isOn: value) {
            VStack(alignment: .leading, spacing: 5) {
                Text(label)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(PortalStyle.textPrimary)
                Text(hint)
                    .font(.caption)
                    .foregroundStyle(PortalStyle.textSecondary)
            }
        }
        .tint(PortalStyle.cyan)
        .disabled(!enabled)
        .padding(.vertical, 8)
    }

    private func actionButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(PortalStyle.cyan)
            .frame(maxWidth: .infinity, minHeight: 48)
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(PortalStyle.surface2))
    }

    private func pathRow(_ title: String, _ path: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption).foregroundStyle(PortalStyle.textSecondary)
            Text(path).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
        }
    }

    private func contentChoice(_ content: SampleContent) -> some View {
        let active = model.contentId == content.id
        return Button { model.contentId = content.id } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(content.title)
                    .font(.body.weight(active ? .bold : .medium))
                    .foregroundStyle(active ? PortalStyle.cyan : PortalStyle.textPrimary)
                Text(content.summary)
                    .font(.caption)
                    .foregroundStyle(PortalStyle.textSecondary)
                if let detail = content.detail {
                    Text(detail)
                        .font(.system(.caption2, design: .monospaced))
                        .foregroundStyle(PortalStyle.mint)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(active ? PortalStyle.cyan.opacity(0.12) : PortalStyle.bg)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!model.editable)
    }
}

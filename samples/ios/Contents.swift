import Foundation

// MARK: - Publishable content
//
// One publishable thing the sample can serve through a tunnel. A content
// owns its local payload (bundled assets or a loopback server) and the
// PortalConfig fields that expose it. Everything else — relays, identity,
// metadata — stays user-controlled.

protocol SampleContent {
    var id: String { get }
    var title: String { get }
    var summary: String { get }
    var detail: String? { get }

    /// Starts the local payload. Called before the tunnel opens.
    func start()
    /// Releases the local payload. Idempotent.
    func stop()

    /// PortalConfig overrides for this content.
    var staticDir: String? { get }
    var staticIndex: String? { get }
    var targetAddr: String? { get }
    var tcp: Bool { get }
}

extension SampleContent {
    var detail: String? { nil }
    var staticDir: String? { nil }
    var staticIndex: String? { nil }
    var targetAddr: String? { nil }
    var tcp: Bool { false }
}

// MARK: - Static site contents

/// Bundled Snake game served via `static_dir`.
struct SnakeGameContent: SampleContent {
    let id = "snake"
    let title = "Snake game"
    let summary = "Static HTML5 game bundled in the app"
    let detail: String? = "static_dir → site/"
    let siteDir: String

    func start() {}
    func stop() {}
    var staticDir: String? { siteDir }
    var staticIndex: String? { "index.html" }
}

/// "How Portal works" explainer page served via `static_dir`.
struct ExplainerContent: SampleContent {
    let id = "explainer"
    let title = "How Portal works"
    let summary = "Explains the device → relay → visitor flow"
    let detail: String? = "static_dir → site-explainer/"
    let explainerDir: String

    func start() {}
    func stop() {}
    var staticDir: String? { explainerDir }
    var staticIndex: String? { "index.html" }
}

// MARK: - On-device model content

/// On-device text-generation model served over HTTP, exposed via
/// `target_addr`. Order-2 Markov chain over an embedded corpus — tiny, but
/// the req/res path is the same shape as a hosted LLM API.
/// Endpoints: /, /v1/generate, /v1/model, /v1/health.
///
/// iOS note: LiteRT-LM is Android-only; this sample uses the embedded
/// Markov fallback. A real on-device model would need a different runtime
/// (e.g. MLX, llama.cpp) — the endpoint shape stays identical.
final class OnDeviceModelContent: SampleContent {
    let id = "ondevice"
    let title = "On-device model"
    let summary = "Tiny embedded text model — real inference, no cloud"
    let detail: String? = "target_addr → 127.0.0.1:\(port) · /v1/generate"

    let port: UInt16 = 18080
    private var listener: SocketListener?
    private var requests = 0
    private var startedAt = Date()
    private let model = MarkovModel(corpus: Self.corpus)

    func start() {
        guard listener == nil else { return }
        requests = 0
        startedAt = Date()
        listener = SocketListener(port: port) { [weak self] request in
            self?.route(request) ?? Self.notFound
        }
    }

    func stop() {
        listener?.stop()
        listener = nil
    }

    var targetAddr: String? { "127.0.0.1:\(port)" }

    private func route(_ request: SocketListener.Request) -> SocketListener.Response {
        requests += 1
        switch request.path {
        case "/":
            return .init(status: "200 OK", contentType: "text/html; charset=utf-8", body: Self.indexHtml)
        case "/v1/generate":
            let params = Self.parseQuery(request.query)
            let prompt = params["prompt"].flatMap { $0.isEmpty ? nil : $0 } ?? "Portal"
            let maxTokens = min(max(Int(params["max_tokens"] ?? "") ?? 40, 1), 200)
            let seed = params["seed"].flatMap { Int($0) }
            let text = model.generate(prompt: prompt, maxTokens: maxTokens, seed: seed)
            return .json("200 OK", #"{"model":"portal-markov-2","prompt":"\#(Self.jsonEscape(prompt))","text":"\#(Self.jsonEscape(text))","max_tokens":\#(maxTokens),"seed":\#(seed.map(String.init) ?? "null"),"served_from":"this device"}"#)
        case "/v1/model":
            return .json("200 OK", #"{"id":"portal-markov-2","type":"markov-chain","order":2,"parameters":\#(model.stateCount),"vocab":\#(model.vocabSize),"context":"embedded corpus, no weights file","note":"a real on-device model — tiny, but the req/res path is the same shape as a hosted LLM API"}"#)
        case "/v1/health":
            let uptime = Int(Date().timeIntervalSince(startedAt))
            return .json("200 OK", #"{"status":"ok","model":"portal-markov-2","uptime_seconds":\#(uptime),"requests":\#(requests)}"#)
        default:
            return .json("404 Not Found", #"{"error":"not_found","path":"\#(Self.jsonEscape(request.path))","endpoints":["/","/v1/generate","/v1/model","/v1/health"]}"#)
        }
    }

    private static let notFound = SocketListener.Response(status: "404 Not Found", contentType: "application/json", body: #"{"error":"not_found"}"#)

    private static func parseQuery(_ query: String) -> [String: String] {
        var out: [String: String] = [:]
        for pair in query.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            if kv.count == 2 {
                out[String(kv[0])] = String(kv[1]).removingPercentEncoding ?? String(kv[1])
            }
        }
        return out
    }

    private static func jsonEscape(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
    }

    private static let indexHtml = """
    <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>On-device model · Portal</title><style>body{background:#080f1d;color:#f0f5fc;font-family:system-ui,sans-serif;max-width:640px;margin:60px auto;padding:0 24px;line-height:1.6}h1{font-size:32px}code{background:rgba(100,220,236,.12);color:#64dcec;padding:2px 8px;border-radius:6px}a{color:#64dcec}.card{background:#111e30;border-radius:14px;padding:20px;margin:14px 0}input,button{font:inherit;padding:10px 14px;border-radius:10px;border:1px solid #1b2d42;background:#0a0e1e;color:#f0f5fc;width:100%;box-sizing:border-box}button{background:#64dcec;color:#080f1d;font-weight:700;border:none;cursor:pointer;margin-top:10px}#out{background:#0a0e1e;border-radius:10px;padding:14px;margin-top:14px;font-family:monospace;font-size:13px;white-space:pre-wrap;display:none}</style></head>
    <body><h1>On-device model</h1><p>A tiny text model running <b>inside this app</b>. Your prompt crosses a Portal relay, inference happens on the device, and the completion comes back — no cloud.</p>
    <div class="card"><b>Try it</b><br><input id="p" placeholder="prompt — e.g. the relay" value="the relay"><button onclick="go()">Generate</button><div id="out"></div></div>
    <div class="card"><b>Endpoints</b><br><a href="/v1/generate?prompt=the%20relay">/v1/generate?prompt=…</a> — completion<br><a href="/v1/model">/v1/model</a> — model card<br><a href="/v1/health">/v1/health</a> — liveness</div>
    <p style="color:#a7b8ce;font-size:14px">Model: <code>portal-markov-2</code> — order-2 Markov chain, embedded corpus. Small, but the API shape is the same as a hosted LLM.</p>
    <script>async function go(){const out=document.getElementById('out');out.style.display='block';out.textContent='…';const r=await fetch('/v1/generate?prompt='+encodeURIComponent(document.getElementById('p').value));const j=await r.json();out.textContent=j.text+'\\n\\n— '+j.model+' · '+j.served_from;}</script></body></html>
    """

    private static let corpus = """
    Portal turns any device into a public server. Your phone, your laptop,
    a Raspberry Pi behind a NAT — all of them can serve a website, an API,
    or a game server without a cloud account, a public IP, or port
    forwarding. The tunnel connects your device to a relay. The relay
    gives you a public address. Visitors load your site through that
    address, and the traffic flows back to your device. You stay in
    control: stop the tunnel and the address dies instantly. Rotate
    relays, update metadata, or hide from the public directory — all
    live, no restart. The SDK is one Kotlin Multiplatform library. The
    same PortalClient API works on Android, iOS, and desktop. The native
    engine handles the transport; the SDK handles the lifecycle. Serve a
    static site, proxy a local HTTP server, forward raw TCP or UDP. A
    Minecraft server, a webhook receiver, a demo API — all from the
    device in your pocket. No infrastructure. No DNS. No TLS certs. The
    relay handles the public endpoint; you handle the content.
    """
}

/// Order-2 Markov chain over an embedded corpus.
private final class MarkovModel {
    private let chain: [[String]: [String]]
    let vocabSize: Int
    var stateCount: Int { chain.count }

    init(corpus: String) {
        let words = corpus.lowercased().split(whereSeparator: { !$0.isLetter && $0 != "'" }).map(String.init)
        vocabSize = Set(words).count
        var map: [[String]: [String]] = [:]
        for i in 0..<max(words.count - 2, 0) {
            map[[words[i], words[i + 1]], default: []].append(words[i + 2])
        }
        chain = map
    }

    func generate(prompt: String, maxTokens: Int, seed: Int?) -> String {
        var rng = seed.map { SeededRNG(seed: $0) } ?? SeededRNG(seed: Int.random(in: 0...Int.max))
        let promptWords = prompt.lowercased().split(whereSeparator: { !$0.isLetter && $0 != "'" }).map(String.init)
        var state = promptWords.suffix(2).count == 2 ? [promptWords[promptWords.count - 2], promptWords[promptWords.count - 1]] : chain.keys.randomElement()!
        var out: [String] = []
        for _ in 0..<maxTokens {
            guard let next = chain[state]?.randomElement(using: &rng) else { break }
            out.append(next)
            state = [state[1], next]
        }
        return out.isEmpty ? "(no continuation — try a different prompt)" : out.joined(separator: " ")
    }
}

/// Deterministic RNG for reproducible generation.
private struct SeededRNG: RandomNumberGenerator {
    private var state: UInt64
    init(seed: Int) { state = UInt64(bitPattern: Int64(seed)) &* 0x9E3779B97F4A7C15 }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}

// MARK: - Minecraft server content

/// Mock Minecraft server answering the server-list ping protocol, exposed
/// via `tcp=true` + `target_addr`. The same port also answers HTTP GETs
/// with a status page.
final class MinecraftContent: SampleContent {
    let id = "minecraft"
    let title = "Minecraft server"
    let summary = "Server-list ping a real MC client can see"
    let detail: String? = "tcp → 127.0.0.1:\(port) (also answers HTTP)"

    let port: UInt16 = 25565
    private var listener: SocketListener?
    private var pings = 0
    private var startedAt = Date()

    func start() {
        guard listener == nil else { return }
        pings = 0
        startedAt = Date()
        listener = SocketListener(port: port, rawHandler: { [weak self] data, respond in
            self?.handleMinecraft(data: data, respond: respond)
        })
    }

    func stop() {
        listener?.stop()
        listener = nil
    }

    var targetAddr: String? { "127.0.0.1:\(port)" }
    var tcp: Bool { true }

    /// Handles one raw connection: HTTP GET → status page; otherwise MC ping.
    private func handleMinecraft(data: Data, respond: (Data) -> Void) {
        if data.first == 0x47 { // 'G' → HTTP GET
            respond(Self.httpStatusPage(pings: pings).data(using: .utf8)!)
            return
        }
        // Minecraft packet stream: handshake → status request → ping.
        var offset = 0
        guard let handshake = Self.readPacket(data, &offset),
              let hs = Self.parseHandshake(handshake), hs.nextState == 1,
              let _ = Self.readPacket(data, &offset) else { return }
        pings += 1
        respond(Self.mcPacket(id: 0x00, body: Self.statusJson(pings: pings, uptime: Int(Date().timeIntervalSince(startedAt))).data(using: .utf8)!))
        // Optional ping → pong echo.
        guard let ping = Self.readPacket(data, &offset), ping.count >= 9, ping[0] == 0x01 else { return }
        var pong = Data([0x01])
        pong.append(ping.subdata(in: 1..<9))
        respond(Self.mcRawPacket(pong))
    }

    // MARK: MC protocol helpers

    private static func readVarInt(_ data: Data, _ offset: inout Int) -> Int? {
        var value = 0, shift = 0
        while offset < data.count {
            let b = Int(data[offset]); offset += 1
            value |= (b & 0x7F) << shift
            if b & 0x80 == 0 { return value }
            shift += 7
            if shift > 35 { return nil }
        }
        return nil
    }

    private static func writeVarInt(_ value: Int) -> Data {
        var v = value, out = Data()
        while true {
            if v & ~0x7F == 0 { out.append(UInt8(v)); return out }
            out.append(UInt8((v & 0x7F) | 0x80)); v >>= 7
        }
    }

    private static func readPacket(_ data: Data, _ offset: inout Int) -> Data? {
        guard let len = readVarInt(data, &offset), len > 0, len <= 1_048_576,
              offset + len <= data.count else { return nil }
        let packet = data.subdata(in: offset..<(offset + len))
        offset += len
        return packet
    }

    private static func parseHandshake(_ packet: Data) -> (protocol: Int, nextState: Int)? {
        var off = 0
        guard let _ = readVarInt(packet, &off),           // packet id
              let proto = readVarInt(packet, &off),
              let addrLen = readVarInt(packet, &off),
              off + addrLen + 2 < packet.count else { return nil }
        off += addrLen + 2                                 // skip addr + port
        guard let next = readVarInt(packet, &off) else { return nil }
        return (proto, next)
    }

    private static func mcPacket(id: Int, body: Data) -> Data {
        var payload = writeVarInt(id)
        payload.append(writeVarInt(body.count))
        payload.append(body)
        return mcRawPacket(payload)
    }

    private static func mcRawPacket(_ payload: Data) -> Data {
        var out = writeVarInt(payload.count)
        out.append(payload)
        return out
    }

    private static func statusJson(pings: Int, uptime: Int) -> String {
        #"{"version":{"name":"Portal 1.21","protocol":767},"players":{"max":20,"online":1,"sample":[{"name":"portal-device","id":"00000000-0000-0000-0000-000000000000"}]},"description":{"text":"§bPortal §f— this \"server\" is a phone"},"enforcesSecureChat":false,"_portal":{"pings":\#(pings),"uptime_seconds":\#(uptime)}}"#
    }

    private static func httpStatusPage(pings: Int) -> String {
        """
        HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Portal Minecraft sample</title><style>body{background:#080f1d;color:#f0f5fc;font-family:system-ui,sans-serif;max-width:640px;margin:60px auto;padding:0 24px;line-height:1.6}code{background:rgba(100,220,236,.12);color:#64dcec;padding:2px 8px;border-radius:6px}.card{background:#111e30;border-radius:14px;padding:20px;margin:14px 0}</style></head><body><h1>Minecraft ping from a phone</h1><p>This device answers the Minecraft server-list protocol on <code>127.0.0.1:25565</code>, exposed through the relay's TCP address.</p><div class="card"><b>To see it in Minecraft:</b><br>1. Copy the <code>tcp_addr</code> from the Activity tab<br>2. Multiplayer → Add Server → paste it<br>3. The MOTD and player count come from this device</div><p style="color:#a7b8ce">Pings so far: \(pings)</p></body></html>
        """
    }
}

// MARK: - Socket listener (POSIX)

/// Minimal loopback TCP listener. One accept loop per instance; each
/// connection is read fully, routed, answered, and closed.
final class SocketListener {
    struct Request {
        let path: String
        let query: String
    }
    struct Response {
        let status: String
        let contentType: String
        let body: String

        static func json(_ status: String, _ body: String) -> Response {
            .init(status: status, contentType: "application/json", body: body)
        }
    }

    private let fd: Int32
    private var running = true
    private let queue = DispatchQueue(label: "portal.sample.socket", qos: .utility)

    /// HTTP mode: handler receives a parsed request, returns a response.
    init(port: UInt16, handler: @escaping (Request) -> Response) {
        fd = Self.bind(port: port)
        queue.async { [weak self] in self?.acceptLoop { data in
            guard let text = String(data: data, encoding: .utf8),
                  let line = text.split(separator: "\r\n").first else { return nil }
            let parts = line.split(separator: " ")
            let target = parts.count > 1 ? String(parts[1]) : "/"
            let path = target.split(separator: "?").first.map(String.init) ?? "/"
            let query = target.split(separator: "?").dropFirst().first.map(String.init) ?? ""
            let response = handler(Request(path: path, query: query))
            let body = response.body.data(using: .utf8) ?? Data()
            var out = "HTTP/1.1 \(response.status)\r\nContent-Type: \(response.contentType)\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n".data(using: .utf8)!
            out.append(body)
            return out
        } }
    }

    /// Raw mode: handler receives the full request bytes, returns response bytes.
    init(port: UInt16, rawHandler: @escaping (Data, (Data) -> Void) -> Void) {
        fd = Self.bind(port: port)
        queue.async { [weak self] in self?.acceptLoopRaw(handler: rawHandler) }
    }

    func stop() {
        running = false
        close(fd)
    }

    private static func bind(port: UInt16) -> Int32 {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        var opt: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY
        withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                _ = Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        listen(fd, 8)
        return fd
    }

    private func acceptLoop(handler: @escaping (Data) -> Data?) {
        while running {
            var clientAddr = sockaddr()
            var len = socklen_t(MemoryLayout<sockaddr>.size)
            let client = accept(fd, &clientAddr, &len)
            guard client >= 0 else { break }
            queue.async { [weak self] in
                guard self != nil else { close(client); return }
                var data = Data()
                var buf = [UInt8](repeating: 0, count: 8192)
                while true {
                    let n = recv(client, &buf, buf.count, 0)
                    if n <= 0 { break }
                    data.append(contentsOf: buf[0..<n])
                    if n < buf.count { break }
                }
                if let response = handler(data) {
                    _ = response.withUnsafeBytes { send(client, $0.baseAddress, response.count, 0) }
                }
                close(client)
            }
        }
    }

    private func acceptLoopRaw(handler: @escaping (Data, (Data) -> Void) -> Void) {
        while running {
            var clientAddr = sockaddr()
            var len = socklen_t(MemoryLayout<sockaddr>.size)
            let client = accept(fd, &clientAddr, &len)
            guard client >= 0 else { break }
            queue.async { [weak self] in
                guard self != nil else { close(client); return }
                var data = Data()
                var buf = [UInt8](repeating: 0, count: 8192)
                // Read until timeout or buffer full — MC protocol has no EOF marker.
                var tv = timeval(tv_sec: 2, tv_usec: 0)
                setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
                while true {
                    let n = recv(client, &buf, buf.count, 0)
                    if n <= 0 { break }
                    data.append(contentsOf: buf[0..<n])
                    if n < buf.count { break }
                }
                handler(data) { response in
                    _ = response.withUnsafeBytes { send(client, $0.baseAddress, response.count, 0) }
                }
                close(client)
            }
        }
    }
}

// MARK: - Registry

/// Every content the sample can publish, in picker order.
struct SampleContents {
    let all: [SampleContent]

    init(siteDir: String, explainerDir: String) {
        all = [
            SnakeGameContent(siteDir: siteDir),
            ExplainerContent(explainerDir: explainerDir),
            OnDeviceModelContent(),
            MinecraftContent()
        ]
    }

    func byId(_ id: String) -> SampleContent? {
        all.first { $0.id == id }
    }
}

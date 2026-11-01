import Foundation
import Network

/// Local demonstrations only; production servers belong to the embedding app.
enum WorkbenchMode: String, CaseIterable, Identifiable {
    case staticSite, http, routes, tcp, udp
    var id: String { rawValue }
    var title: String {
        switch self {
        case .staticSite: return "Web games / static site"
        case .http: return "HTTP API / webhook"
        case .routes: return "HTTP prefix routes"
        case .tcp: return "TCP echo"
        case .udp: return "UDP echo"
        }
    }
    var explanation: String {
        switch self {
        case .staticSite: return "Publish Snake, memory pairs, reaction testing and sloshing water fill. The native engine serves the bundled files directly; no game server or downloads."
        case .http: return "Forward HTTP to a loopback API. The built-in demo returns JSON and accepts bounded webhook requests; it never logs request bodies or query strings."
        case .routes: return "Prefixes select different loopback API and webhook services. The longest matching prefix wins; custom prefixes and upstreams are configurable."
        case .tcp: return "Forward a raw TCP stream to a loopback listener. The built-in listener echoes bytes; use the relay's TCP address, not the HTTPS URL."
        case .udp: return "Forward UDP datagrams to a loopback listener. The built-in listener echoes datagrams up to 4096 bytes; use the relay's UDP address."
        }
    }
}

/// Owns all loopback listeners for one publishing attempt. Retained until the
/// SDK session is terminal, including failed native stops in STOPPING.
@MainActor
final class DemoPayload {
    private var servers: [DemoServer] = []
    private var stopped = false
    private(set) var addresses: [String] = []

    init(mode: WorkbenchMode, record: @escaping (String) -> Void) {
        switch mode {
        case .staticSite: break
        case .http: servers = [DemoServer(kind: .http("api"), record: record)]
        case .routes:
            servers = [DemoServer(kind: .http("api"), record: record),
                       DemoServer(kind: .http("webhook"), record: record)]
        case .tcp: servers = [DemoServer(kind: .tcp, record: record)]
        case .udp: servers = [DemoServer(kind: .udp, record: record)]
        }
    }

    func start(completion: @escaping (Error?) -> Void) {
        startNext(at: 0, completion: completion)
    }

    private func startNext(at index: Int, completion: @escaping (Error?) -> Void) {
        guard !stopped else { return }
        guard index < servers.count else { completion(nil); return }
        servers[index].start { [weak self] address, error in
            guard let self, !self.stopped else { return }
            if let error { completion(error) }
            else if let address {
                self.addresses.append(address)
                self.startNext(at: index + 1, completion: completion)
            }
        }
    }

    func stop() {
        stopped = true
        servers.forEach { $0.stop() }
    }
}

/// Deliberately small HTTP/echo transport, not a general-purpose web server.
/// All socket state is serialized on queue; UI callbacks are delivered on Main.
private final class DemoServer {
    enum Kind { case http(String), tcp, udp }
    private let kind: Kind
    private let record: (String) -> Void
    private let queue = DispatchQueue(label: "org.gosuda.portal.sample.listener")
    private var listener: NWListener?
    private var connections: [UUID: NWConnection] = [:]
    private var timeouts: [UUID: DispatchWorkItem] = [:]
    private var started = false
    private var stopped = false

    init(kind: Kind, record: @escaping (String) -> Void) {
        self.kind = kind
        self.record = record
    }

    func start(completion: @escaping (String?, Error?) -> Void) {
        queue.async { [self] in
            do {
                let parameters: NWParameters
                if case .udp = kind { parameters = .udp } else { parameters = .tcp }
                parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
                let listener = try NWListener(using: parameters)
                self.listener = listener
                listener.stateUpdateHandler = { [weak self] state in
                    guard let self, !self.stopped, !self.started else { return }
                    switch state {
                    case .ready:
                        self.started = true
                        let address = self.listener?.port.map { "127.0.0.1:\($0.rawValue)" }
                        DispatchQueue.main.async {
                            completion(address, address == nil ? POSIXError(.EADDRNOTAVAIL) : nil)
                        }
                    case .failed(let error):
                        self.started = true
                        DispatchQueue.main.async { completion(nil, error) }
                    default: break
                    }
                }
                listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
                listener.start(queue: queue)
            } catch {
                DispatchQueue.main.async { completion(nil, error) }
            }
        }
    }

    func stop() {
        queue.async { [self] in
            stopped = true
            listener?.cancel()
            listener = nil
            timeouts.values.forEach { $0.cancel() }
            timeouts.removeAll()
            connections.values.forEach { $0.cancel() }
            connections.removeAll()
        }
    }

    private func accept(_ connection: NWConnection) {
        guard !stopped, connections.count < 16 else { connection.cancel(); return }
        let id = UUID()
        connections[id] = connection
        let timeout = DispatchWorkItem { [weak self] in self?.finish(id) }
        timeouts[id] = timeout
        queue.asyncAfter(deadline: .now() + 10, execute: timeout)
        connection.stateUpdateHandler = { [weak self] state in
            if case .failed = state { self?.finish(id) }
            if case .cancelled = state { self?.finish(id) }
        }
        connection.start(queue: queue)
        switch kind {
        case .http(let service): receiveHTTP(connection, id: id, data: Data(), service: service)
        case .tcp: receiveTCP(connection, id: id)
        case .udp: receiveUDP(connection, id: id)
        }
    }

    private func finish(_ id: UUID) {
        timeouts.removeValue(forKey: id)?.cancel()
        connections.removeValue(forKey: id)?.cancel()
    }

    private func log(_ message: String) {
        DispatchQueue.main.async { [record] in record(message) }
    }

    private func receiveTCP(_ connection: NWConnection, id: UUID) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, complete, error in
            guard let self, self.connections[id] != nil else { return }
            guard let data, !data.isEmpty else { self.finish(id); return }
            self.log("TCP echoed \(data.count) bytes")
            connection.send(content: data, completion: .contentProcessed { [weak self] sendError in
                guard let self else { return }
                if complete || error != nil || sendError != nil { self.finish(id) }
                else { self.receiveTCP(connection, id: id) }
            })
        }
    }

    private func receiveUDP(_ connection: NWConnection, id: UUID) {
        connection.receiveMessage { [weak self] data, context, _, error in
            guard let self, self.connections[id] != nil else { return }
            guard error == nil, let data, data.count <= 4096 else { self.finish(id); return }
            self.log("UDP echoed \(data.count) bytes")
            connection.send(content: data, contentContext: context ?? .defaultMessage, isComplete: true,
                            completion: .contentProcessed { [weak self] sendError in
                guard let self else { return }
                if sendError != nil { self.finish(id) }
                else { self.receiveUDP(connection, id: id) }
            })
        }
    }

    private func receiveHTTP(_ connection: NWConnection, id: UUID, data: Data, service: String) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] chunk, _, complete, error in
            guard let self, self.connections[id] != nil else { return }
            var input = data
            if let chunk { input.append(chunk) }
            guard input.count <= 16_384 else {
                self.respond(connection, id: id, status: "413 Payload Too Large", json: ["error": "16 KiB request limit"])
                return
            }
            if let boundary = input.range(of: Data("\r\n\r\n".utf8)) {
                let headers = String(decoding: input[..<boundary.lowerBound], as: UTF8.self).components(separatedBy: "\r\n")
                let first = (headers.first ?? "").split(separator: " ")
                guard first.count == 3, ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"].contains(String(first[0])) else {
                    self.respond(connection, id: id, status: "400 Bad Request", json: ["error": "invalid request line"])
                    return
                }
                var length = 0
                var hasLength = false
                for header in headers.dropFirst() {
                    let pair = header.split(separator: ":", maxSplits: 1).map(String.init)
                    guard pair.count == 2 else { continue }
                    let key = pair[0].lowercased()
                    if key == "transfer-encoding" || key == "expect" {
                        self.respond(connection, id: id, status: "501 Not Implemented", json: ["error": "use Content-Length; chunked requests and Expect are unsupported"])
                        return
                    }
                    if key == "content-length" {
                        guard !hasLength, let value = Int(pair[1].trimmingCharacters(in: .whitespaces)), value >= 0,
                              value <= 16_384 - boundary.upperBound else {
                            self.respond(connection, id: id, status: "413 Payload Too Large", json: ["error": "invalid or oversized Content-Length"])
                            return
                        }
                        hasLength = true
                        length = value
                    }
                }
                if input.count >= boundary.upperBound + length {
                    let method = String(first[0])
                    // Exclude query strings and fragments; bound the visible path.
                    let path = first[1].prefix { $0 != "?" && $0 != "#" }.prefix(120)
                    self.log("\(service.uppercased()) \(method) \(path) · \(length) body bytes (not stored)")
                    self.respond(connection, id: id, status: "200 OK", json: ["service": service, "method": method,
                        "path": String(path), "receivedBytes": length, "message": service == "api" ? "Hello from the iOS SDK demo" : "Webhook accepted; body not retained"], head: method == "HEAD")
                    return
                }
            }
            guard error == nil, !complete else { self.finish(id); return }
            self.receiveHTTP(connection, id: id, data: input, service: service)
        }
    }

    private func respond(_ connection: NWConnection, id: UUID, status: String, json: [String: Any], head: Bool = false) {
        let body = (try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])) ?? Data()
        var response = Data("HTTP/1.1 \(status)\r\nContent-Type: application/json\r\nContent-Length: \(body.count)\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".utf8)
        if !head { response.append(body) }
        connection.send(content: response, completion: .contentProcessed { [weak self] _ in self?.finish(id) })
    }
}

import Foundation
import Network
import Security
import PortalSampleLlm

enum LocalLlmError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let message) = self { return message }; return nil }
}

/// C handle is touched only by the inference queue, except lock-protected cancellation.
private final class LocalLlmEngine {
    private let lock = NSLock()
    private var handle: OpaquePointer?
    private var cancelled = false

    func load(_ path: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        let loaded = path.withCString { PortalSampleLlmCreate($0, &error) }
        defer { if let error { PortalSampleLlmFree(error) } }
        guard let loaded else {
            throw LocalLlmError.message(error.map { String(cString: $0) } ?? "The native model could not be loaded.")
        }
        lock.lock()
        handle = loaded
        if cancelled { PortalSampleLlmCancel(loaded) }
        lock.unlock()
    }

    func generate(_ prompt: String, maxTokens: Int) throws -> String {
        lock.lock(); let current = cancelled ? nil : handle; lock.unlock()
        guard let current else { throw LocalLlmError.message("The local model is stopping.") }
        var error: UnsafeMutablePointer<CChar>?
        var length = 0
        var tokens: Int32 = 0
        var code: Int32 = 0
        let result = prompt.utf8CString.withUnsafeBufferPointer { buffer in
            PortalSampleLlmGenerate(current, buffer.baseAddress, buffer.count - 1, Int32(maxTokens),
                                    &length, &tokens, &code, &error)
        }
        defer {
            if let result { PortalSampleLlmFree(result) }
            if let error { PortalSampleLlmFree(error) }
        }
        guard let result else {
            let message = error.map { String(cString: $0) } ?? "Native generation failed."
            if code == 1 { throw GenerationError.invalid(message) }
            throw LocalLlmError.message(message)
        }
        return String(decoding: UnsafeRawBufferPointer(start: result, count: length), as: UTF8.self)
    }

    func cancel() {
        lock.lock()
        cancelled = true
        if let handle { PortalSampleLlmCancel(handle) }
        lock.unlock()
    }

    func close() {
        lock.lock(); let current = handle; handle = nil; lock.unlock()
        if let current { PortalSampleLlmDestroy(current) }
    }
}

private enum GenerationError: Error { case invalid(String) }

enum LocalLlmPassword {
    static func generate() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw LocalLlmError.message("Could not securely generate a password. Try again or enter your own.")
        }
        return Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func isValid(_ password: String) -> Bool {
        (12...128).contains(password.unicodeScalars.count)
    }
}

/// Bounded one-request-per-connection loopback HTTP server. No filesystem root is exposed.
final class LocalLlmServer {
    private struct GenerateInput: Decodable { let prompt: String; let maxTokens: Int? }
    private struct Asset { let mime: String; let data: Data }
    private let model: LocalModelSpec
    private let modelFile: URL
    private let engine = LocalLlmEngine()
    private let queue = DispatchQueue(label: "org.gosuda.portal.sample.llm.http")
    private let inference = DispatchQueue(label: "org.gosuda.portal.sample.llm.inference", qos: .userInitiated)
    private let report: @MainActor (String) -> Void
    private var listener: NWListener?
    private var connections: [UUID: NWConnection] = [:]
    private var deadlines: [UUID: DispatchWorkItem] = [:]
    private var assets: [String: Asset] = [:]
    private var stopped = false
    private var busy = false
    private var startCompleted = false
    private let expectedAuthorization: [UInt8]

    init(model: LocalModelSpec, modelFile: URL, password: String,
         report: @escaping @MainActor (String) -> Void) throws {
        guard LocalLlmPassword.isValid(password) else {
            throw LocalLlmError.message("Password must contain 12–128 Unicode code points.")
        }
        self.model = model
        self.modelFile = modelFile
        self.report = report
        expectedAuthorization = Array(("Basic " + Data(("portal:" + password).utf8).base64EncodedString()).utf8)
    }

    func start(completion: @escaping @MainActor (Result<String, Error>) -> Void) {
        inference.async { [self] in
            do {
                var loaded: [String: Asset] = [:]
                for (path, filename, mime) in [("/", "index.html", "text/html; charset=utf-8"),
                                                ("/llm.css", "llm.css", "text/css; charset=utf-8"),
                                                ("/llm.js", "llm.js", "text/javascript; charset=utf-8")] {
                    guard let root = Bundle.main.url(forResource: "llm", withExtension: nil) else {
                        throw LocalLlmError.message("The bundled local LLM webpage is missing.")
                    }
                    loaded[path] = Asset(mime: mime, data: try Data(contentsOf: root.appendingPathComponent(filename)))
                }
                try engine.load(modelFile.path)
                let readyAssets = loaded
                queue.async { [self] in
                    guard !stopped else { return }
                    assets = readyAssets
                    do {
                        let parameters = NWParameters.tcp
                        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
                        let listener = try NWListener(using: parameters)
                        self.listener = listener
                        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
                        listener.stateUpdateHandler = { [weak self] state in
                            guard let self, !self.stopped, !self.startCompleted else { return }
                            switch state {
                            case .ready:
                                self.startCompleted = true
                                if let port = self.listener?.port {
                                    DispatchQueue.main.async { completion(.success("127.0.0.1:\(port.rawValue)")) }
                                } else {
                                    DispatchQueue.main.async { completion(.failure(POSIXError(.EADDRNOTAVAIL))) }
                                }
                            case .failed(let error):
                                self.startCompleted = true
                                DispatchQueue.main.async { completion(.failure(error)) }
                            default: break
                            }
                        }
                        listener.start(queue: queue)
                    } catch { DispatchQueue.main.async { completion(.failure(error)) } }
                }
            } catch { DispatchQueue.main.async { completion(.failure(error)) } }
        }
    }

    func close() {
        // Cancel immediately; destruction is ordered after any load/generation already queued.
        engine.cancel()
        queue.async { [self] in
            guard !stopped else { return }
            stopped = true
            listener?.cancel()
            listener = nil
            deadlines.values.forEach { $0.cancel() }
            deadlines.removeAll()
            connections.values.forEach { $0.cancel() }
            connections.removeAll()
            inference.async { [self] in engine.close() }
        }
    }

    private func accept(_ connection: NWConnection) {
        guard !stopped, connections.count < 16 else { connection.cancel(); return }
        let id = UUID()
        connections[id] = connection
        let timeout = DispatchWorkItem { [weak self] in self?.finish(id) }
        deadlines[id] = timeout
        queue.asyncAfter(deadline: .now() + 180, execute: timeout)
        connection.stateUpdateHandler = { [weak self] state in
            if case .failed = state { self?.finish(id) }
            if case .cancelled = state { self?.finish(id) }
        }
        connection.start(queue: queue)
        receive(id, input: Data())
    }

    private func finish(_ id: UUID) {
        deadlines.removeValue(forKey: id)?.cancel()
        connections.removeValue(forKey: id)?.cancel()
    }

    private func receive(_ id: UUID, input: Data) {
        guard let connection = connections[id] else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] chunk, _, complete, error in
            guard let self, self.connections[id] != nil, !self.stopped else { return }
            var data = input
            if let chunk { data.append(chunk) }
            guard data.count <= 24_576 else { self.json(id, 413, ["error": "Request too large"]); return }
            if let boundary = data.range(of: Data("\r\n\r\n".utf8)) {
                guard boundary.upperBound <= 8192,
                      let header = String(data: data[..<boundary.lowerBound], encoding: .utf8) else {
                    self.json(id, 400, ["error": "Invalid headers"]); return
                }
                let lines = header.components(separatedBy: "\r\n")
                let first = (lines.first ?? "").split(separator: " ")
                guard first.count == 3, first[2] == "HTTP/1.1" || first[2] == "HTTP/1.0" else {
                    self.json(id, 400, ["error": "Invalid request"]); return
                }
                var headers: [String: String] = [:]
                for line in lines.dropFirst() {
                    let parts = line.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
                    guard parts.count == 2 else { self.json(id, 400, ["error": "Invalid headers"]); return }
                    let key = parts[0].lowercased()
                    guard headers[key] == nil else { self.json(id, 400, ["error": "Duplicate header"]); return }
                    headers[key] = parts[1].trimmingCharacters(in: .whitespaces)
                }
                guard headers["transfer-encoding"] == nil, headers["expect"] == nil,
                      let length = Int(headers["content-length"] ?? "0"), length >= 0 else {
                    self.json(id, 400, ["error": "Use Content-Length"]); return
                }
                guard length <= 16_384 else { self.json(id, 413, ["error": "Body exceeds 16 KiB"]); return }
                if data.count >= boundary.upperBound + length {
                    let body = Data(data[boundary.upperBound..<(boundary.upperBound + length)])
                    let path = String(first[1].prefix { $0 != "?" && $0 != "#" })
                    self.route(id, method: String(first[0]), path: path, headers: headers, body: body)
                    return
                }
            } else if data.count > 8192 { self.json(id, 400, ["error": "Headers too large"]); return }
            guard !complete, error == nil else { self.finish(id); return }
            self.receive(id, input: data)
        }
    }

    private func authorized(_ value: String?) -> Bool {
        let actual = (value ?? "").utf8
        var difference = actual.count ^ expectedAuthorization.count
        var iterator = actual.makeIterator()
        for expected in expectedAuthorization {
            difference |= Int((iterator.next() ?? 0) ^ expected)
        }
        return difference == 0
    }

    private func route(_ id: UUID, method: String, path: String, headers: [String: String], body: Data) {
        if let asset = assets[path] {
            guard method == "GET" else { json(id, 405, ["error": "Method not allowed"]); return }
            respond(id, status: 200, mime: asset.mime, body: asset.data)
            return
        }
        guard path == "/api/status" || path == "/api/generate" else { json(id, 404, ["error": "Not found"]); return }
        guard authorized(headers["authorization"]) else { json(id, 401, ["error": "Password required"]); return }
        if path == "/api/status" {
            guard method == "GET" else { json(id, 405, ["error": "Method not allowed"]); return }
            json(id, 200, ["model": model.title, "local": true, "maxTokens": 128, "contextTokens": 1024])
            return
        }
        guard method == "POST" else { json(id, 405, ["error": "Method not allowed"]); return }
        guard let request = try? JSONDecoder().decode(GenerateInput.self, from: body),
              !request.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              (1...128).contains(request.maxTokens ?? 96) else {
            json(id, 400, ["error": "Provide a nonblank prompt and maxTokens from 1 to 128"]); return
        }
        guard !busy else { json(id, 409, ["error": "One generation is already running"]); return }
        busy = true
        inference.async { [self] in
            let start = DispatchTime.now().uptimeNanoseconds
            let result = Result { try engine.generate(request.prompt, maxTokens: request.maxTokens ?? 96) }
            let elapsed = (DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
            queue.async { [self] in
                busy = false
                guard !stopped else { return }
                switch result {
                case .success(let text): json(id, 200, ["text": text, "model": model.title, "elapsedMs": elapsed])
                case .failure(GenerationError.invalid(let message)):
                    json(id, 400, ["error": "Prompt exceeds the model context budget"])
                    DispatchQueue.main.async { [report] in report(message) }
                case .failure(let error):
                    json(id, 503, ["error": "Local generation unavailable"])
                    DispatchQueue.main.async { [report] in report(error.localizedDescription) }
                }
            }
        }
    }

    private func json(_ id: UUID, _ status: Int, _ value: [String: Any]) {
        let body = (try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])) ?? Data()
        respond(id, status: status, mime: "application/json; charset=utf-8", body: body)
    }

    private func respond(_ id: UUID, status: Int, mime: String, body: Data) {
        guard let connection = connections[id] else { return }
        let reason = [200: "OK", 400: "Bad Request", 401: "Unauthorized", 404: "Not Found", 405: "Method Not Allowed",
                      409: "Conflict", 413: "Payload Too Large", 503: "Service Unavailable"][status] ?? "Error"
        var response = Data("HTTP/1.1 \(status) \(reason)\r\nContent-Type: \(mime)\r\nContent-Length: \(body.count)\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n\r\n".utf8)
        response.append(body)
        connection.send(content: response, completion: .contentProcessed { [weak self] _ in self?.finish(id) })
    }
}

import Foundation
import CryptoKit

struct LocalModelSpec: Decodable, Identifiable {
    let id: String
    let title: String
    let summary: String
    let parameters: String
    let quantization: String
    let languages: String
    let downloadBytes: Int64
    let url: String
    let sha256: String
    let license: String
    let source: String

    var sizeLabel: String { ByteCountFormatter.string(fromByteCount: downloadBytes, countStyle: .file) }
}

@MainActor
final class ModelStore: ObservableObject {
    @Published private(set) var models: [LocalModelSpec] = []
    @Published private(set) var downloadingId: String?
    @Published private(set) var receivedBytes: Int64 = 0
    @Published private(set) var phase = "Not downloaded"
    @Published private(set) var error: String?
    @Published private(set) var cachedIds: Set<String> = []
    private var worker: ModelDownloadWorker?
    private var directory: URL?

    init() {
        do {
            guard let catalog = Bundle.main.url(forResource: "models", withExtension: "json") else {
                throw LocalLlmError.message("The bundled model catalog is missing.")
            }
            models = try JSONDecoder().decode([LocalModelSpec].self, from: Data(contentsOf: catalog))
            let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                      appropriateFor: nil, create: true)
            let root = support.appendingPathComponent("PortalSample/models", isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            var excluded = root
            var attributes = URLResourceValues()
            attributes.isExcludedFromBackup = true
            try excluded.setResourceValues(attributes)
            directory = root
            cachedIds = Set(models.filter { cachedFile($0) != nil }.map(\.id))
        } catch { self.error = error.localizedDescription }
    }

    func cachedFile(_ model: LocalModelSpec) -> URL? {
        guard let file = directory?.appendingPathComponent(model.sha256 + ".gguf"),
              let values = try? file.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]),
              values.isRegularFile == true, Int64(values.fileSize ?? -1) == model.downloadBytes else { return nil }
        return file
    }

    func download(_ model: LocalModelSpec) {
        guard worker == nil, let directory else { return }
        guard cachedFile(model) == nil else { cachedIds.insert(model.id); return }
        downloadingId = model.id
        receivedBytes = 0
        phase = "Downloading"
        error = nil
        let download = ModelDownloadWorker(model: model, directory: directory, progress: { [weak self] bytes, verifying in
            self?.receivedBytes = bytes
            self?.phase = verifying ? "Verifying SHA-256" : "Downloading"
        }, completion: { [weak self] result in
            guard let self else { return }
            self.worker = nil
            self.downloadingId = nil
            switch result {
            case .success: self.cachedIds.insert(model.id); self.phase = "Cached · verified"
            case .failure(let error):
                self.phase = (error as? URLError)?.code == .cancelled ? "Cancelled" : "Download failed"
                self.error = (error as? URLError)?.code == .cancelled ? nil : error.localizedDescription
            }
        })
        worker = download
        download.start()
    }

    func cancelDownload() { worker?.cancel() }

    func delete(_ model: LocalModelSpec) {
        guard downloadingId != model.id, let file = cachedFile(model) else { return }
        do { try FileManager.default.removeItem(at: file); cachedIds.remove(model.id) }
        catch { self.error = error.localizedDescription }
    }
}

/// Download delegate owns its temporary file and verification work, never the UI.
private final class ModelDownloadWorker: NSObject, URLSessionDownloadDelegate {
    private let model: LocalModelSpec
    private let directory: URL
    private let progress: @MainActor (Int64, Bool) -> Void
    private let completion: @MainActor (Result<Void, Error>) -> Void
    private let lock = NSLock()
    private var cancelled = false
    private var finished = false
    private var session: URLSession?
    private var task: URLSessionDownloadTask?

    init(model: LocalModelSpec, directory: URL,
         progress: @escaping @MainActor (Int64, Bool) -> Void,
         completion: @escaping @MainActor (Result<Void, Error>) -> Void) {
        self.model = model
        self.directory = directory
        self.progress = progress
        self.completion = completion
    }

    func start() {
        guard let url = URL(string: model.url), url.scheme == "https" else {
            finish(.failure(LocalLlmError.message("Model download requires the catalog HTTPS URL."))); return
        }
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.timeoutIntervalForResource = 24 * 60 * 60
        let session = URLSession(configuration: config, delegate: self, delegateQueue: queue)
        self.session = session
        let task = session.downloadTask(with: url)
        self.task = task
        task.resume()
    }

    func cancel() {
        lock.lock(); cancelled = true; lock.unlock()
        task?.cancel()
    }

    private func checkCancellation() throws {
        lock.lock(); let value = cancelled; lock.unlock()
        if value { throw URLError(.cancelled) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(request.url?.scheme == "https" ? request : nil)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten > model.downloadBytes { cancel() }
        DispatchQueue.main.async { [progress] in progress(totalBytesWritten, false) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        do {
            try checkCancellation()
            guard (downloadTask.response as? HTTPURLResponse)?.statusCode == 200 else {
                throw LocalLlmError.message("The model host did not return a successful download.")
            }
            let size = try location.resourceValues(forKeys: [.fileSizeKey]).fileSize
            guard Int64(size ?? -1) == model.downloadBytes else {
                throw LocalLlmError.message("Model size does not match the pinned catalog. No cache file was installed.")
            }
            DispatchQueue.main.async { [progress, model] in progress(model.downloadBytes, true) }
            let input = try FileHandle(forReadingFrom: location)
            defer { try? input.close() }
            var hash = SHA256()
            while true {
                try checkCancellation()
                let data = try input.read(upToCount: 1_048_576) ?? Data()
                if data.isEmpty { break }
                hash.update(data: data)
            }
            guard hash.finalize().map({ String(format: "%02x", $0) }).joined() == model.sha256.lowercased() else {
                throw LocalLlmError.message("SHA-256 verification failed. No cache file was installed.")
            }
            try checkCancellation()
            // Stage within Application Support so final rename is same-filesystem atomic.
            let staging = directory.appendingPathComponent(UUID().uuidString + ".partial")
            defer { try? FileManager.default.removeItem(at: staging) }
            try FileManager.default.moveItem(at: location, to: staging)
            try checkCancellation()
            let destination = directory.appendingPathComponent(model.sha256 + ".gguf")
            if FileManager.default.fileExists(atPath: destination.path) {
                _ = try FileManager.default.replaceItemAt(destination, withItemAt: staging)
            } else { try FileManager.default.moveItem(at: staging, to: destination) }
            finish(.success(()))
        } catch { finish(.failure(error)) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { finish(.failure(error)) }
    }

    private func finish(_ result: Result<Void, Error>) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        lock.unlock()
        session?.finishTasksAndInvalidate()
        session = nil
        task = nil
        DispatchQueue.main.async { [completion] in completion(result) }
    }
}

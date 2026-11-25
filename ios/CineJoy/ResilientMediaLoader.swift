import AVFoundation
import Foundation
import UniformTypeIdentifiers

final class ResilientMediaLoader: NSObject, AVAssetResourceLoaderDelegate {

    static let resilientHTTPS = "cinejoy-resilient-https"
    static let resilientHTTP = "cinejoy-resilient-http"

    private let maxRetries = 5
    private let workQueue = DispatchQueue(label: "pk.cinejoy.ios.resilient-media", qos: .userInitiated)
    private let session: URLSession
    private var activeTasks: [ObjectIdentifier: URLSessionDataTask] = [:]
    private let customHeaders: [String: String]

    static func resilientURL(from originalURL: URL) -> URL {
        guard var components = URLComponents(url: originalURL, resolvingAgainstBaseURL: false) else {
            return originalURL
        }
        switch components.scheme?.lowercased() {
        case "https":
            components.scheme = resilientHTTPS
            return components.url ?? originalURL
        case "http":
            components.scheme = resilientHTTP
            return components.url ?? originalURL
        default:
            return originalURL
        }
    }

    static func originalURL(from customURL: URL) -> URL? {
        guard var components = URLComponents(url: customURL, resolvingAgainstBaseURL: false) else {
            return nil
        }
        switch components.scheme?.lowercased() {
        case resilientHTTPS:
            components.scheme = "https"
            return components.url
        case resilientHTTP:
            components.scheme = "http"
            return components.url
        default:
            return customURL
        }
    }

    init(customHeaders: [String: String] = [:]) {
        self.customHeaders = customHeaders
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15.0
        config.timeoutIntervalForResource = 45.0
        config.waitsForConnectivity = true
        config.httpMaximumConnectionsPerHost = 6
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        self.session = URLSession(configuration: config)
        super.init()
    }

    deinit {
        invalidate()
    }

    func invalidate() {
        workQueue.async { [weak self] in
            guard let self = self else { return }
            for task in self.activeTasks.values {
                task.cancel()
            }
            self.activeTasks.removeAll()
        }
        session.invalidateAndCancel()
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        guard let reqURL = loadingRequest.request.url,
              let targetURL = Self.originalURL(from: reqURL) else {
            return false
        }
        if AdBlockerRules.isBlocked(targetURL) {
            loadingRequest.finishLoading(with: URLError(.cancelled))
            return true
        }
        workQueue.async { [weak self] in
            self?.performRequest(for: loadingRequest, targetURL: targetURL, attempt: 0)
        }
        return true
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        let key = ObjectIdentifier(loadingRequest)
        workQueue.async { [weak self] in
            if let task = self?.activeTasks.removeValue(forKey: key) {
                task.cancel()
            }
        }
    }

    private func performRequest(
        for loadingRequest: AVAssetResourceLoadingRequest,
        targetURL: URL,
        attempt: Int
    ) {
        if loadingRequest.isCancelled || loadingRequest.isFinished {
            return
        }

        var urlRequest = URLRequest(url: targetURL)
        urlRequest.timeoutInterval = 15.0
        for (headerKey, headerVal) in customHeaders {
            urlRequest.setValue(headerVal, forHTTPHeaderField: headerKey)
        }

        let isPlaylist = targetURL.pathExtension.lowercased() == "m3u8"
        if !isPlaylist, let dataRequest = loadingRequest.dataRequest {
            let offset = dataRequest.requestedOffset
            let length = dataRequest.requestedLength
            if !dataRequest.requestsAllDataToEndOfResource && length > 0 {
                let end = offset + Int64(length) - 1
                urlRequest.setValue("bytes=\(offset)-\(end)", forHTTPHeaderField: "Range")
            } else if offset > 0 {
                urlRequest.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range")
            }
        }

        let reqKey = ObjectIdentifier(loadingRequest)
        let task = session.dataTask(with: urlRequest) { [weak self] data, response, error in
            guard let self = self else { return }
            self.workQueue.async {
                self.activeTasks.removeValue(forKey: reqKey)
                if loadingRequest.isCancelled || loadingRequest.isFinished {
                    return
                }

                let httpResponse = response as? HTTPURLResponse
                let statusCode = httpResponse?.statusCode ?? 0
                let isRetryableHTTP = statusCode == 408 || statusCode == 429 || (statusCode >= 500 && statusCode <= 599)
                let nsError = error as NSError?
                let isCanceledErr = nsError?.domain == NSURLErrorDomain && nsError?.code == NSURLErrorCancelled

                if !isCanceledErr && (error != nil || isRetryableHTTP) && attempt < self.maxRetries {
                    let baseDelay = min(0.35 * pow(2.0, Double(attempt)), 5.6)
                    let jitter = Double.random(in: 0.0...0.12)
                    self.workQueue.asyncAfter(deadline: .now() + baseDelay + jitter) {
                        self.performRequest(for: loadingRequest, targetURL: targetURL, attempt: attempt + 1)
                    }
                    return
                }

                if let err = error {
                    loadingRequest.finishLoading(with: err)
                    return
                }
                if statusCode >= 400 {
                    loadingRequest.finishLoading(with: URLError(.badServerResponse))
                    return
                }

                var payload = data ?? Data()
                let resolvedURL = httpResponse?.url ?? targetURL
                let mimeType = (httpResponse?.mimeType ?? "").lowercased()
                let isManifest = resolvedURL.pathExtension.lowercased() == "m3u8" ||
                    mimeType.contains("mpegurl") ||
                    Self.looksLikeM3U8Playlist(payload)

                if isManifest, let text = String(data: payload, encoding: .utf8) {
                    let rewritten = Self.rewritePlaylist(text, baseURL: resolvedURL)
                    if let rewrittenData = rewritten.data(using: .utf8) {
                        payload = rewrittenData
                    }
                }

                if let infoRequest = loadingRequest.contentInformationRequest {
                    infoRequest.contentType = Self.resolveUTI(mimeType: mimeType, url: resolvedURL, isManifest: isManifest)
                    if isManifest {
                        infoRequest.isByteRangeAccessSupported = false
                        infoRequest.contentLength = Int64(payload.count)
                    } else {
                        let acceptRanges = (httpResponse?.value(forHTTPHeaderField: "Accept-Ranges") ?? "").lowercased()
                        infoRequest.isByteRangeAccessSupported = acceptRanges.contains("bytes") || statusCode == 206
                        if let totalLength = Self.extractTotalLength(from: httpResponse, fallbackCount: payload.count) {
                            infoRequest.contentLength = totalLength
                        }
                    }
                }

                if let dataRequest = loadingRequest.dataRequest {
                    dataRequest.respond(with: payload)
                }
                loadingRequest.finishLoading()
            }
        }
        activeTasks[reqKey] = task
        task.resume()
    }

    private static func looksLikeM3U8Playlist(_ data: Data) -> Bool {
        guard data.count >= 7,
              let prefix = String(data: data.prefix(16), encoding: .utf8) else {
            return false
        }
        return prefix.hasPrefix("\u{23}EXTM3U")
    }

    private static func resolveUTI(mimeType: String, url: URL, isManifest: Bool) -> String {
        if isManifest {
            return "com.apple.m3u-playlist"
        }
        if mimeType.contains("mp2t") || url.pathExtension.lowercased() == "ts" {
            return "public.mpeg-2-transport-stream"
        }
        if let utType = UTType(mimeType: mimeType) {
            return utType.identifier
        }
        if let extType = UTType(filenameExtension: url.pathExtension) {
            return extType.identifier
        }
        return UTType.mpeg4Movie.identifier
    }

    private static func extractTotalLength(from response: HTTPURLResponse?, fallbackCount: Int) -> Int64? {
        guard let response = response else {
            return Int64(fallbackCount)
        }
        if let contentRange = response.value(forHTTPHeaderField: "Content-Range"),
           let slashIdx = contentRange.lastIndex(of: "/") {
            let totalPart = contentRange[contentRange.index(after: slashIdx)...].trimmingCharacters(in: .whitespaces)
            if let total = Int64(totalPart) {
                return total
            }
        }
        if response.expectedContentLength > 0 {
            return response.expectedContentLength
        }
        return Int64(fallbackCount)
    }

    private static func rewritePlaylist(_ content: String, baseURL: URL) -> String {
        let lines = content.components(separatedBy: .newlines)
        var rewrittenLines: [String] = []
        rewrittenLines.reserveCapacity(lines.count)
        let uriRegex = try? NSRegularExpression(pattern: "URI=\"([^\"]+)\"", options: [])

        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty {
                rewrittenLines.append(line)
                continue
            }
            if trimmed.hasPrefix("\u{23}") {
                if let regex = uriRegex,
                   let match = regex.firstMatch(in: line, options: [], range: NSRange(line.startIndex..., in: line)),
                   match.numberOfRanges >= 2,
                   let uriRange = Range(match.range(at: 1), in: line) {
                    let rawUri = String(line[uriRange])
                    if let resolved = URL(string: rawUri, relativeTo: baseURL)?.absoluteURL {
                        let custom = resilientURL(from: resolved).absoluteString
                        var updatedLine = line
                        updatedLine.replaceSubrange(uriRange, with: custom)
                        rewrittenLines.append(updatedLine)
                        continue
                    }
                }
                rewrittenLines.append(line)
                continue
            }
            if let resolved = URL(string: trimmed, relativeTo: baseURL)?.absoluteURL {
                rewrittenLines.append(resilientURL(from: resolved).absoluteString)
            } else {
                rewrittenLines.append(line)
            }
        }
        return rewrittenLines.joined(separator: "\n")
    }
}

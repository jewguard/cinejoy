import Foundation
import WebKit

enum AdBlockerRules {

    static let blockedHosts: Set<String> = [
        "a.wing.st",
        "plausible.io",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "adnxs.com",
        "adsrvr.org",
        "amazon-adsystem.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "mgid.com",
        "revcontent.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "smartadserver.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "propellerclick.com",
        "exoclick.com",
        "exosrv.com",
        "juicyads.com",
        "trafficjunky.com",
        "trafficstars.com",
        "adsterra.com",
        "highperformanceformat.com",
        "highcpmgate.com",
        "profitablegatecpm.com",
        "effectivegatecpm.com",
        "topcreativeformat.com",
        "acscdn.com",
        "onclickalgo.com",
        "onclickmax.com",
        "onclkds.com",
        "adcash.com",
        "admaven.com",
        "hilltopads.net",
        "clickadu.com",
        "monetag.com",
        "alwingulla.com",
        "dolohen.com",
        "histats.com",
        "mc.yandex.ru",
        "connect.facebook.net",
        "hotjar.com",
        "clarity.ms",
        "sentry.io",
        "mixpanel.com",
        "segment.io",
        "amplitude.com",
        "scorecardresearch.com",
        "quantserve.com",
        "chartbeat.com"
    ]

    static let blockedPathFragments: [String] = [
        "/pagead/",
        "/popunder",
        "/adserver/",
        "popads.js"
    ]

    static let mediaExtensions: [String] = [
        ".m3u8",
        ".mp4",
        ".ts",
        ".vtt",
        ".srt"
    ]

    static func isCineJoyHost(_ host: String?) -> Bool {
        guard let host = host, !host.isEmpty else {
            return false
        }
        let normalized = host.lowercased()
        return normalized == "cinejoy.pk" || normalized.hasSuffix(".cinejoy.pk")
    }

    static func isBlocked(_ url: URL?) -> Bool {
        guard let url = url, let host = url.host?.lowercased() else {
            return false
        }
        if isCineJoyHost(host) || host == "tmdb.org" || host.hasSuffix(".tmdb.org") {
            return false
        }
        let path = url.path.lowercased()
        for ext in mediaExtensions where path.hasSuffix(ext) {
            return false
        }
        if matchesBlockedDomain(host) {
            return true
        }
        let fullTarget = url.absoluteString.lowercased()
        for fragment in blockedPathFragments where fullTarget.contains(fragment) {
            return true
        }
        return false
    }

    static func compileContentRuleList(completion: @escaping (WKContentRuleList?) -> Void) {
        var rules: [[String: Any]] = []
        for domain in blockedHosts.sorted() {
            let escaped = NSRegularExpression.escapedPattern(for: domain)
            let rule: [String: Any] = [
                "trigger": [
                    "url-filter": "^https?://([^/]+\\.)?" + escaped + "(/|$)",
                    "unless-domain": ["*cinejoy.pk", "*tmdb.org"]
                ],
                "action": [
                    "type": "block"
                ]
            ]
            rules.append(rule)
        }
        for fragment in blockedPathFragments {
            let escaped = NSRegularExpression.escapedPattern(for: fragment)
            let rule: [String: Any] = [
                "trigger": [
                    "url-filter": escaped,
                    "unless-domain": ["*cinejoy.pk", "*tmdb.org"]
                ],
                "action": [
                    "type": "block"
                ]
            ]
            rules.append(rule)
        }
        guard let jsonData = try? JSONSerialization.data(withJSONObject: rules, options: []),
              let jsonString = String(data: jsonData, encoding: .utf8) else {
            DispatchQueue.main.async {
                completion(nil)
            }
            return
        }
        WKContentRuleListStore.default().compileContentRuleList(
            forIdentifier: "cinejoy-adblock-rules-v1",
            encodedContentRuleList: jsonString
        ) { list, _ in
            DispatchQueue.main.async {
                completion(list)
            }
        }
    }

    private static func matchesBlockedDomain(_ host: String) -> Bool {
        var current = host
        while !current.isEmpty {
            if blockedHosts.contains(current) {
                return true
            }
            guard let dotIndex = current.firstIndex(of: ".") else {
                break
            }
            current = String(current[current.index(after: dotIndex)...])
        }
        return false
    }
}

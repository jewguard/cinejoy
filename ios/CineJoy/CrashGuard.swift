import Foundation

func cinejoyHandleUncaughtException(_ exception: NSException) {
    exit(0)
}

func cinejoyHandleFatalSignal(_ signalCode: Int32) {
    exit(0)
}

enum CrashGuard {
    static let homeURL = URL(string: "https://cinejoy.pk")!
    private static let lastUrlKey = "cinejoy_last_valid_url"
    private static let lastRecoveryAtKey = "cinejoy_last_recovery_at"

    static func install() {
        NSSetUncaughtExceptionHandler(cinejoyHandleUncaughtException)
        signal(SIGPIPE, SIG_IGN)
        signal(SIGABRT, cinejoyHandleFatalSignal)
        signal(SIGILL, cinejoyHandleFatalSignal)
        signal(SIGSEGV, cinejoyHandleFatalSignal)
        signal(SIGFPE, cinejoyHandleFatalSignal)
        signal(SIGBUS, cinejoyHandleFatalSignal)
    }

    static func saveLastURL(_ urlString: String?) {
        guard let raw = urlString, !raw.isEmpty, raw != "about:blank" else {
            return
        }
        guard let url = URL(string: raw),
              let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http",
              AdBlockerRules.isCineJoyHost(url.host) else {
            return
        }
        UserDefaults.standard.set(raw, forKey: lastUrlKey)
    }

    static func consumeRecoveryURL() -> URL {
        let now = Date().timeIntervalSince1970
        let prev = UserDefaults.standard.double(forKey: lastRecoveryAtKey)
        if now - prev < 4.0 {
            UserDefaults.standard.removeObject(forKey: lastUrlKey)
            return homeURL
        }
        UserDefaults.standard.set(now, forKey: lastRecoveryAtKey)
        if let saved = UserDefaults.standard.string(forKey: lastUrlKey),
           let url = URL(string: saved),
           AdBlockerRules.isCineJoyHost(url.host) {
            return url
        }
        return homeURL
    }
}

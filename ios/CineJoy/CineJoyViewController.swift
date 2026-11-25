import UIKit
import WebKit

final class CineJoyViewController: UIViewController, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {

    private var webView: WKWebView!
    private var nativePlayerController: NativePlayerOverlayController!
    private var errorOverlayView: UIView?
    private var errorStatusLabel: UILabel?
    private var errorRetryTimer: Timer?
    private var failedNavigationAttempt = 0
    private var lastFailedURL: URL?
    private var isWatchRoute = false

    private let backgroundDark = UIColor(red: 5.0 / 255.0, green: 5.0 / 255.0, blue: 5.0 / 255.0, alpha: 1.0)
    private let iosSafariUserAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

    override var preferredStatusBarStyle: UIStatusBarStyle {
        return .lightContent
    }

    override var prefersStatusBarHidden: Bool {
        return isWatchRoute || (nativePlayerController?.isPresented ?? false)
    }

    override var prefersHomeIndicatorAutoHidden: Bool {
        return isWatchRoute || (nativePlayerController?.isPresented ?? false)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = backgroundDark

        nativePlayerController = NativePlayerOverlayController(hostViewController: self) { [weak self] _ in
            self?.setNeedsStatusBarAppearanceUpdate()
            self?.setNeedsUpdateOfHomeIndicatorAutoHidden()
        }

        configureWebView()
        AdBlockerRules.compileContentRuleList { [weak self] ruleList in
            guard let self = self, let list = ruleList else { return }
            self.webView.configuration.userContentController.add(list)
        }

        let initialURL = CrashGuard.consumeRecoveryURL()
        webView.load(URLRequest(url: initialURL))
    }

    deinit {
        errorRetryTimer?.invalidate()
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: "cinejoyNative")
        nativePlayerController?.close()
    }

    func releaseMediaResourcesForBackground() {
        nativePlayerController.releaseForBackground()
        webView.evaluateJavaScript("if (window.__cinejoyReleaseMedia) { window.__cinejoyReleaseMedia(true); }", completionHandler: nil)
    }

    func restoreMediaResourcesForForeground() {
        nativePlayerController.restoreFromBackground()
        webView.evaluateJavaScript("if (window.__cinejoyResumeMedia) { window.__cinejoyResumeMedia(); }", completionHandler: nil)
    }

    private func configureWebView() {
        let contentController = WKUserContentController()
        contentController.add(self, name: "cinejoyNative")
        contentController.addUserScript(
            WKUserScript(
                source: CineJoyWebScripts.documentStartSource,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: true
            )
        )
        contentController.addUserScript(
            WKUserScript(
                source: CineJoyWebScripts.documentEndSource,
                injectionTime: .atDocumentEnd,
                forMainFrameOnly: true
            )
        )

        let config = WKWebViewConfiguration()
        config.userContentController = contentController
        config.allowsInlineMediaPlayback = true
        config.allowsAirPlayForMediaPlayback = true
        config.allowsPictureInPictureMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.websiteDataStore = .default()
        config.applicationNameForUserAgent = "CineJoy/1.0"

        let wk = WKWebView(frame: .zero, configuration: config)
        wk.translatesAutoresizingMaskIntoConstraints = false
        wk.navigationDelegate = self
        wk.uiDelegate = self
        wk.customUserAgent = iosSafariUserAgent
        wk.isOpaque = false
        wk.backgroundColor = backgroundDark
        wk.scrollView.backgroundColor = backgroundDark
        wk.scrollView.contentInsetAdjustmentBehavior = .never
        wk.scrollView.bounces = false
        wk.allowsBackForwardNavigationGestures = true
        wk.isInspectable = false

        view.addSubview(wk)
        NSLayoutConstraint.activate([
            wk.topAnchor.constraint(equalTo: view.topAnchor),
            wk.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            wk.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            wk.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
        webView = wk
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "cinejoyNative",
              let dict = message.body as? [String: Any],
              let msgType = dict["type"] as? String else {
            return
        }
        switch msgType {
        case "routeChanged":
            let nextWatch = (dict["isWatch"] as? Bool) ?? false
            if isWatchRoute != nextWatch {
                isWatchRoute = nextWatch
                setNeedsStatusBarAppearanceUpdate()
                setNeedsUpdateOfHomeIndicatorAutoHidden()
            }
            if let urlStr = dict["url"] as? String {
                CrashGuard.saveLastURL(urlStr)
            }
        case "openNativePlayer":
            guard let streamUrl = dict["streamUrl"] as? String, !streamUrl.isEmpty else {
                return
            }
            let headers = (dict["headers"] as? [String: String]) ?? [:]
            let startPos = (dict["startPositionSeconds"] as? Double) ?? 0
            nativePlayerController.open(urlString: streamUrl, headers: headers, startPositionSeconds: startPos)
        default:
            break
        }
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel)
            return
        }
        let raw = url.absoluteString
        if raw == "about:blank" {
            decisionHandler(.allow)
            return
        }
        let scheme = url.scheme?.lowercased() ?? ""
        if scheme != "http" && scheme != "https" {
            decisionHandler(.cancel)
            return
        }
        if AdBlockerRules.isBlocked(url) {
            decisionHandler(.cancel)
            return
        }
        let isMain = navigationAction.targetFrame?.isMainFrame ?? true
        if !AdBlockerRules.isCineJoyHost(url.host) && isMain {
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if let url = navigationAction.request.url,
           let scheme = url.scheme?.lowercased(),
           (scheme == "https" || scheme == "http"),
           AdBlockerRules.isCineJoyHost(url.host),
           !AdBlockerRules.isBlocked(url) {
            webView.load(URLRequest(url: url))
        }
        return nil
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        hideErrorOverlay()
        failedNavigationAttempt = 0
        if let currentURL = webView.url?.absoluteString {
            CrashGuard.saveLastURL(currentURL)
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        hideErrorOverlay()
        failedNavigationAttempt = 0
        if let currentURL = webView.url?.absoluteString {
            CrashGuard.saveLastURL(currentURL)
        }
    }

    func webView(
        _ webView: WKWebView,
        didFail navigation: WKNavigation!,
        withError error: Error
    ) {
        handleLoadFailure(error)
    }

    func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation!,
        withError error: Error
    ) {
        handleLoadFailure(error)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        let recoveryURL = CrashGuard.consumeRecoveryURL()
        webView.load(URLRequest(url: recoveryURL))
    }

    private func handleLoadFailure(_ error: Error) {
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
            return
        }
        let target = nsError.userInfo[NSURLErrorFailingURLErrorKey] as? URL ?? webView.url ?? CrashGuard.homeURL
        lastFailedURL = target
        showErrorOverlay(message: error.localizedDescription, retryURL: target)
    }

    private func showErrorOverlay(message: String, retryURL: URL) {
        if errorOverlayView == nil {
            let container = UIView()
            container.translatesAutoresizingMaskIntoConstraints = false
            container.backgroundColor = backgroundDark

            let stack = UIStackView()
            stack.translatesAutoresizingMaskIntoConstraints = false
            stack.axis = .vertical
            stack.alignment = .center
            stack.spacing = 12

            let titleLbl = UILabel()
            titleLbl.text = "CineJoy Connection Issue"
            titleLbl.textColor = .systemYellow
            titleLbl.font = .systemFont(ofSize: 22, weight: .bold)

            let msgLbl = UILabel()
            msgLbl.text = message
            msgLbl.textColor = UIColor(white: 0.78, alpha: 1.0)
            msgLbl.font = .systemFont(ofSize: 15)
            msgLbl.numberOfLines = 3
            msgLbl.textAlignment = .center

            let statusLbl = UILabel()
            statusLbl.textColor = UIColor(white: 0.58, alpha: 1.0)
            statusLbl.font = .systemFont(ofSize: 13)
            errorStatusLabel = statusLbl

            let retryBtn = UIButton(type: .system)
            retryBtn.setTitle("Retry Now", for: .normal)
            retryBtn.setTitleColor(backgroundDark, for: .normal)
            retryBtn.backgroundColor = .systemYellow
            retryBtn.titleLabel?.font = .systemFont(ofSize: 16, weight: .bold)
            retryBtn.layer.cornerRadius = 8
            retryBtn.contentEdgeInsets = UIEdgeInsets(top: 12, left: 28, bottom: 12, right: 28)
            retryBtn.addTarget(self, action: #selector(handleManualRetry), for: .touchUpInside)

            stack.addArrangedSubview(titleLbl)
            stack.addArrangedSubview(msgLbl)
            stack.addArrangedSubview(statusLbl)
            stack.addArrangedSubview(retryBtn)
            container.addSubview(stack)
            view.addSubview(container)

            NSLayoutConstraint.activate([
                container.topAnchor.constraint(equalTo: view.topAnchor),
                container.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                container.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                container.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                stack.centerXAnchor.constraint(equalTo: container.centerXAnchor),
                stack.centerYAnchor.constraint(equalTo: container.centerYAnchor),
                stack.leadingAnchor.constraint(greaterThanOrEqualTo: container.leadingAnchor, constant: 28),
                stack.trailingAnchor.constraint(lessThanOrEqualTo: container.trailingAnchor, constant: -28)
            ])
            errorOverlayView = container
        }

        let delay = min(3.0 * pow(2.0, Double(min(failedNavigationAttempt, 2))), 12.0)
        failedNavigationAttempt += 1
        errorStatusLabel?.text = "Retrying in \(Int(delay))s..."
        errorRetryTimer?.invalidate()
        errorRetryTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            self?.webView.load(URLRequest(url: retryURL))
        }
    }

    private func hideErrorOverlay() {
        errorRetryTimer?.invalidate()
        errorRetryTimer = nil
        errorOverlayView?.removeFromSuperview()
        errorOverlayView = nil
        errorStatusLabel = nil
    }

    @objc private func handleManualRetry() {
        let target = lastFailedURL ?? CrashGuard.homeURL
        errorRetryTimer?.invalidate()
        errorRetryTimer = nil
        webView.load(URLRequest(url: target))
    }
}

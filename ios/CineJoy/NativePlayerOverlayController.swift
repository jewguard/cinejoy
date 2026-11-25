import AVFoundation
import AVKit
import UIKit

final class NativePlayerOverlayController: NSObject, AVPlayerViewControllerDelegate, UIGestureRecognizerDelegate {

    var isPresented: Bool {
        return playerViewController != nil
    }

    var isPictureInPictureActive: Bool = false

    private weak var hostViewController: UIViewController?
    private var player: AVPlayer?
    private var playerViewController: AVPlayerViewController?
    private var overlayContainerView: UIView?
    private var topChromeBar: UIView?
    private var mediaLoader: ResilientMediaLoader?
    private var statusObserver: NSKeyValueObservation?
    private var failObserver: NSObjectProtocol?
    private var controlsHideTimer: Timer?
    private var retryWorkItem: DispatchWorkItem?
    private var retryAttempt = 0
    private let maxRetries = 4
    private var activeURLString: String?
    private var activeHeaders: [String: String] = [:]
    private var savedPositionSeconds: Double = 0
    private var shouldRestoreOnForeground = false
    private let onVisibilityChanged: (Bool) -> Void

    init(hostViewController: UIViewController, onVisibilityChanged: @escaping (Bool) -> Void) {
        self.hostViewController = hostViewController
        self.onVisibilityChanged = onVisibilityChanged
        super.init()
    }

    deinit {
        close()
    }

    func open(urlString: String, headers: [String: String] = [:], startPositionSeconds: Double = 0) {
        guard let originalURL = URL(string: urlString) else {
            return
        }
        close()
        activeURLString = urlString
        activeHeaders = headers
        savedPositionSeconds = max(0, startPositionSeconds)
        retryAttempt = 0
        shouldRestoreOnForeground = true
        buildAndPresentPlayer(originalURL: originalURL, headers: headers, seekSeconds: savedPositionSeconds)
        onVisibilityChanged(true)
    }

    func close() {
        retryWorkItem?.cancel()
        retryWorkItem = nil
        cancelControlsHideTimer()
        activeURLString = nil
        activeHeaders = [:]
        savedPositionSeconds = 0
        retryAttempt = 0
        shouldRestoreOnForeground = false
        isPictureInPictureActive = false
        tearDownPlayerResources()
        if let container = overlayContainerView {
            container.removeFromSuperview()
            overlayContainerView = nil
        }
        topChromeBar = nil
        onVisibilityChanged(false)
    }

    func releaseForBackground() {
        if isPictureInPictureActive {
            return
        }
        guard activeURLString != nil else {
            return
        }
        retryWorkItem?.cancel()
        retryWorkItem = nil
        cancelControlsHideTimer()
        if let player = player {
            let secs = CMTimeGetSeconds(player.currentTime())
            if secs.isFinite && secs > 0 {
                savedPositionSeconds = secs
            }
        }
        shouldRestoreOnForeground = true
        tearDownPlayerResources()
    }

    func restoreFromBackground() {
        guard shouldRestoreOnForeground,
              player == nil,
              let urlString = activeURLString,
              let url = URL(string: urlString) else {
            return
        }
        retryAttempt = 0
        buildAndPresentPlayer(originalURL: url, headers: activeHeaders, seekSeconds: savedPositionSeconds)
    }

    private func buildAndPresentPlayer(originalURL: URL, headers: [String: String], seekSeconds: Double) {
        guard let hostVC = hostViewController else {
            return
        }
        tearDownPlayerResources()

        let loader = ResilientMediaLoader(customHeaders: headers)
        mediaLoader = loader

        let resilientAssetURL = ResilientMediaLoader.resilientURL(from: originalURL)
        let asset = AVURLAsset(url: resilientAssetURL)
        asset.resourceLoader.setDelegate(loader, queue: DispatchQueue(label: "pk.cinejoy.ios.avloader"))

        let playerItem = AVPlayerItem(asset: asset)
        playerItem.preferredForwardBufferDuration = 20.0
        playerItem.canUseNetworkResourcesForLiveStreamingWhilePaused = true

        let avPlayer = AVPlayer(playerItem: playerItem)
        avPlayer.automaticallyWaitsToMinimizeStalling = true
        avPlayer.allowsExternalPlayback = true
        avPlayer.usesExternalPlaybackWhileExternalScreenIsActive = true
        player = avPlayer

        let container: UIView
        if let existing = overlayContainerView {
            container = existing
        } else {
            let root = UIView()
            root.translatesAutoresizingMaskIntoConstraints = false
            root.backgroundColor = UIColor(red: 5.0 / 255.0, green: 5.0 / 255.0, blue: 5.0 / 255.0, alpha: 1.0)
            hostVC.view.addSubview(root)
            NSLayoutConstraint.activate([
                root.topAnchor.constraint(equalTo: hostVC.view.topAnchor),
                root.bottomAnchor.constraint(equalTo: hostVC.view.bottomAnchor),
                root.leadingAnchor.constraint(equalTo: hostVC.view.leadingAnchor),
                root.trailingAnchor.constraint(equalTo: hostVC.view.trailingAnchor)
            ])
            overlayContainerView = root
            container = root
        }

        let pvc = AVPlayerViewController()
        pvc.player = avPlayer
        pvc.delegate = self
        pvc.allowsPictureInPicturePlayback = true
        pvc.canStartPictureInPictureAutomaticallyFromInline = true
        pvc.entersFullScreenWhenPlaybackBegins = false
        pvc.exitsFullScreenWhenPlaybackEnds = false
        pvc.view.backgroundColor = .clear
        pvc.view.translatesAutoresizingMaskIntoConstraints = false

        hostVC.addChild(pvc)
        container.addSubview(pvc.view)
        NSLayoutConstraint.activate([
            pvc.view.topAnchor.constraint(equalTo: container.topAnchor),
            pvc.view.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            pvc.view.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            pvc.view.trailingAnchor.constraint(equalTo: container.trailingAnchor)
        ])
        pvc.didMove(toParent: hostVC)
        playerViewController = pvc

        installTopChromeOverlay(in: container)
        let tapRecognizer = UITapGestureRecognizer(target: self, action: #selector(handleOverlayTap))
        tapRecognizer.cancelsTouchesInView = false
        tapRecognizer.delegate = self
        container.addGestureRecognizer(tapRecognizer)
        scheduleControlsAutoHide()

        statusObserver = playerItem.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard let self = self else { return }
            DispatchQueue.main.async {
                switch item.status {
                case .readyToPlay:
                    self.retryAttempt = 0
                    if seekSeconds > 1.0 {
                        let targetTime = CMTime(seconds: seekSeconds, preferredTimescale: 600)
                        avPlayer.seek(to: targetTime, toleranceBefore: .zero, toleranceAfter: .zero)
                    }
                    avPlayer.play()
                case .failed:
                    self.schedulePlaybackRetry()
                default:
                    break
                }
            }
        }

        failObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: playerItem,
            queue: .main
        ) { [weak self] _ in
            self?.schedulePlaybackRetry()
        }
    }

    private func installTopChromeOverlay(in container: UIView) {
        topChromeBar?.removeFromSuperview()

        let bar = UIView()
        bar.translatesAutoresizingMaskIntoConstraints = false

        let closeBtn = UIButton(type: .system)
        closeBtn.translatesAutoresizingMaskIntoConstraints = false
        closeBtn.setImage(UIImage(systemName: "xmark.circle.fill"), for: .normal)
        closeBtn.tintColor = .white
        closeBtn.backgroundColor = UIColor(white: 0.08, alpha: 0.72)
        closeBtn.layer.cornerRadius = 18
        closeBtn.addTarget(self, action: #selector(handleCloseTapped), for: .touchUpInside)

        let routePicker = AVRoutePickerView()
        routePicker.translatesAutoresizingMaskIntoConstraints = false
        routePicker.tintColor = .white
        routePicker.activeTintColor = .systemYellow
        routePicker.backgroundColor = UIColor(white: 0.08, alpha: 0.72)
        routePicker.layer.cornerRadius = 18
        routePicker.prioritizesVideoDevices = true

        bar.addSubview(closeBtn)
        bar.addSubview(routePicker)
        container.addSubview(bar)

        NSLayoutConstraint.activate([
            bar.topAnchor.constraint(equalTo: container.safeAreaLayoutGuide.topAnchor, constant: 10),
            bar.leadingAnchor.constraint(equalTo: container.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            bar.trailingAnchor.constraint(equalTo: container.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            bar.heightAnchor.constraint(equalToConstant: 40),

            closeBtn.leadingAnchor.constraint(equalTo: bar.leadingAnchor),
            closeBtn.centerYAnchor.constraint(equalTo: bar.centerYAnchor),
            closeBtn.widthAnchor.constraint(equalToConstant: 36),
            closeBtn.heightAnchor.constraint(equalToConstant: 36),

            routePicker.trailingAnchor.constraint(equalTo: bar.trailingAnchor),
            routePicker.centerYAnchor.constraint(equalTo: bar.centerYAnchor),
            routePicker.widthAnchor.constraint(equalToConstant: 36),
            routePicker.heightAnchor.constraint(equalToConstant: 36)
        ])

        topChromeBar = bar
    }

    @objc private func handleCloseTapped() {
        close()
    }

    @objc private func handleOverlayTap() {
        wakeTopChromeBar()
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        return true
    }

    private func wakeTopChromeBar() {
        if let bar = topChromeBar {
            bar.isHidden = false
            UIView.animate(withDuration: 0.18) {
                bar.alpha = 1.0
            }
        }
        scheduleControlsAutoHide()
    }

    private func scheduleControlsAutoHide() {
        cancelControlsHideTimer()
        controlsHideTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: false) { [weak self] _ in
            guard let self = self, let bar = self.topChromeBar else { return }
            UIView.animate(withDuration: 0.18, animations: {
                bar.alpha = 0.0
            }, completion: { _ in
                if bar.alpha == 0.0 {
                    bar.isHidden = true
                }
            })
        }
    }

    private func cancelControlsHideTimer() {
        controlsHideTimer?.invalidate()
        controlsHideTimer = nil
    }

    private func schedulePlaybackRetry() {
        guard retryAttempt < maxRetries,
              let urlString = activeURLString,
              let originalURL = URL(string: urlString) else {
            close()
            return
        }
        if let player = player {
            let secs = CMTimeGetSeconds(player.currentTime())
            if secs.isFinite && secs > 0 {
                savedPositionSeconds = secs
            }
        }
        let delay = 0.5 * pow(2.0, Double(min(retryAttempt, 3)))
        retryAttempt += 1
        retryWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self = self, self.activeURLString == urlString else { return }
            self.buildAndPresentPlayer(
                originalURL: originalURL,
                headers: self.activeHeaders,
                seekSeconds: self.savedPositionSeconds
            )
        }
        retryWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func tearDownPlayerResources() {
        statusObserver?.invalidate()
        statusObserver = nil
        if let obs = failObserver {
            NotificationCenter.default.removeObserver(obs)
            failObserver = nil
        }
        if let avPlayer = player {
            avPlayer.pause()
            avPlayer.replaceCurrentItem(with: nil)
            player = nil
        }
        if let pvc = playerViewController {
            pvc.player = nil
            pvc.willMove(toParent: nil)
            pvc.view.removeFromSuperview()
            pvc.removeFromParent()
            playerViewController = nil
        }
        mediaLoader?.invalidate()
        mediaLoader = nil
    }

    func playerViewControllerWillStartPictureInPicture(_ playerViewController: AVPlayerViewController) {
        isPictureInPictureActive = true
    }

    func playerViewControllerDidStopPictureInPicture(_ playerViewController: AVPlayerViewController) {
        isPictureInPictureActive = false
        if UIApplication.shared.applicationState == .background {
            releaseForBackground()
        }
    }

    func playerViewController(
        _ playerViewController: AVPlayerViewController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void
    ) {
        isPictureInPictureActive = false
        completionHandler(true)
    }
}

import UIKit
import WebKit
import Capacitor

/// The app's one host and the path the store app keeps (quizbuks-mobile/ios/capacitor.config.json `server.url`;
/// docs/quizbuks/plans/Q49c-ios.md). Everything else on the host (the sponsor console and its top-ups, the business
/// site, the administration) and every other site opens in Safari: the store app is the takers' app only.
enum QuizBuksScope {
    static let host = "quizbuks.com"
    static let path = "/taker/"
    static let shellParameter = "shell"
    static let shell = "ios"

    static func contains(_ url: URL) -> Bool {
        url.scheme == "https" && url.host == host && (url.path + "/").hasPrefix(path)
    }

    /// The address with `shell=ios`, which tells the page it runs in the store app (quizbuks-web/app/src/shell.ts).
    static func marked(_ url: URL) -> URL {
        guard var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        var query = parts.queryItems ?? []
        if !query.contains(where: { $0.name == shellParameter }) {
            query.append(URLQueryItem(name: shellParameter, value: shell))
        }
        parts.queryItems = query
        return parts.url ?? url
    }
}

/// Keeps the web view on the takers' app: a page of the main frame outside the scope opens in Safari instead.
/// Frames (embedded videos) and the app's own requests are not navigations of the main frame and load as usual.
public class ScopePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ScopePlugin"
    public let jsName = "QuizBuksScope"
    public let pluginMethods: [CAPPluginMethod] = []

    override public func shouldOverrideLoad(_ navigationAction: WKNavigationAction) -> NSNumber? {
        guard let url = navigationAction.request.url, navigationAction.targetFrame?.isMainFrame ?? true else {
            return nil
        }
        if url.scheme == "about" || QuizBuksScope.contains(url) { return NSNumber(value: false) }
        DispatchQueue.main.async { UIApplication.shared.open(url) }
        return NSNumber(value: true)
    }
}

/// Capacitor's view controller with the scope above, and the addresses of Universal Links opened in the web view.
class QuizBuksViewController: CAPBridgeViewController {
    private var pending: URL?
    private var appeared = false

    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(ScopePlugin())
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        appeared = true
        if let url = pending {
            pending = nil
            open(url)
        }
    }

    /// Opens an address of the app's (a mail's link) in the web view; before the view is up, once it is.
    func open(_ url: URL) {
        guard QuizBuksScope.contains(url) else { return }
        guard appeared, let webView = bridge?.webView else {
            pending = url
            return
        }
        webView.load(URLRequest(url: QuizBuksScope.marked(url)))
    }
}

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private let controller = QuizBuksViewController()

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        window = UIWindow(windowScene: windowScene)
        window?.rootViewController = controller
        window?.makeKeyAndVisible()

        SceneDelegateProxy.shared.scene(scene, willConnectTo: session, options: connectionOptions)
        // Started by a Universal Link (a mail's link to the app).
        for activity in connectionOptions.userActivities {
            if let url = Self.link(of: activity) { controller.open(url) }
        }
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        SceneDelegateProxy.shared.scene(scene, openURLContexts: URLContexts)
    }

    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        SceneDelegateProxy.shared.scene(scene, continue: userActivity)
        if let url = Self.link(of: userActivity) { controller.open(url) }
    }

    private static func link(of activity: NSUserActivity) -> URL? {
        activity.activityType == NSUserActivityTypeBrowsingWeb ? activity.webpageURL : nil
    }
}

package com.sushil.codemap;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.ui.UIUtil;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;

/**
 * Content of the "Code Map" editor tab: embedded JCEF browser (or a fallback panel) showing
 * {@code http://127.0.0.1:<built-in server port>/codemap/<project id>/[#focus=…]}. No server to start: the page is
 * served by the IDE itself and shows analysis progress on its own. On a load error: a page with Retry.
 */
public final class CodeMapPanel implements Disposable {
    private static final Logger LOG = Logger.getInstance(CodeMapPanel.class);
    public static final Key<CodeMapPanel> KEY = Key.create("com.sushil.codemap.panel");

    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JTextField urlField = new JTextField();
    private final JLabel fallbackStatus = new JLabel(" ");
    private JBCefBrowser browser;
    private JBCefJSQuery openQuery;
    private JBCefJSQuery actionQuery;

    /** URL last requested (absolute). */
    private volatile String target;
    /** URL currently shown (null on the error page). */
    private volatile String currentUrl;
    private volatile boolean disposed;

    public CodeMapPanel(Project project) {
        this(project, null);
    }

    /** @param initialUrl absolute URL; null = this project's map. */
    public CodeMapPanel(Project project, String initialUrl) {
        this.project = project;
        root.add(buildToolbar(), BorderLayout.NORTH);
        if (isJcefSupported()) {
            try {
                createBrowser();
            } catch (Throwable t) {
                LOG.warn("codemap: JCEF browser creation failed, using fallback panel", t);
                browser = null;
            }
        }
        if (browser == null) root.add(buildFallback(), BorderLayout.CENTER);
        navigate(initialUrl, false);
    }

    static boolean isJcefSupported() {
        try {
            return JBCefApp.isSupported();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean hasBrowser() { return browser != null; }

    public JComponent getComponent() { return root; }

    // ------------------------------------------------------------------ UI

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(4, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        urlField.setEditable(true);
        urlField.addActionListener(e -> {
            String t = urlField.getText().trim();
            if (!t.isEmpty()) navigate(t, true);
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        JButton home = new JButton("Home");
        home.setToolTipText("Show this project's Code Map");
        home.addActionListener(e -> navigate(null, true));
        JButton reload = new JButton("Reload");
        reload.addActionListener(e -> reload());
        JButton external = new JButton("Open in Browser");
        external.addActionListener(e -> BrowserUtil.browse(currentUrl != null ? currentUrl : targetOrHome()));
        buttons.add(home);
        buttons.add(reload);
        buttons.add(external);
        bar.add(urlField, BorderLayout.CENTER);
        bar.add(buttons, BorderLayout.EAST);
        return bar;
    }

    private JComponent buildFallback() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        p.add(new JLabel("<html><b>Code Map</b><br>The embedded browser (JCEF) is not available in this IDE session"
                + " (e.g. Remote Development backend).<br>Use the button below to open the graph in your browser."
                + "<br>Ctrl+click on a node there still opens the code here.</html>"));
        p.add(Box.createVerticalStrut(12));
        JButton open = new JButton("Open Code Map in browser");
        open.addActionListener(e -> BrowserUtil.browse(targetOrHome()));
        p.add(open);
        p.add(Box.createVerticalStrut(8));
        p.add(fallbackStatus);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(p, BorderLayout.NORTH);
        return wrap;
    }

    private void createBrowser() {
        browser = new JBCefBrowser();
        Disposer.register(this, browser);
        openQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        Disposer.register(this, openQuery);
        openQuery.addHandler(payload -> {
            // Only Code Map pages from this IDE's built-in server may open files (the URL field accepts any URL).
            JBCefBrowser b = browser;
            String pageUrl = b == null ? null : b.getCefBrowser().getURL();
            if (!CodeMapUrls.isOwnPage(pageUrl)) {
                LOG.warn("codemap: ignored open request from untrusted page " + pageUrl);
                return null;
            }
            // payload: "<file>\n<line>"
            String file = payload;
            int line = 1;
            int nl = payload == null ? -1 : payload.lastIndexOf('\n');
            if (nl >= 0) {
                file = payload.substring(0, nl);
                try { line = Integer.parseInt(payload.substring(nl + 1).trim()); } catch (NumberFormatException ignored) {}
            }
            IdeNavigator.open(file, line, project);
            return null;
        });
        actionQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        Disposer.register(this, actionQuery);
        actionQuery.addHandler(action -> {
            // retry/settings buttons of our own loadHTML error page (non-http URL) or of Code Map pages
            JBCefBrowser b = browser;
            String pageUrl = b == null ? null : b.getCefBrowser().getURL();
            if (isHttp(pageUrl) && !CodeMapUrls.isOwnPage(pageUrl)) return null;
            SwingUtilities.invokeLater(() -> {
                if (disposed) return;
                if ("retry".equals(action)) retry();
                else if ("settings".equals(action)) openSettings();
            });
            return null;
        });
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (frame != null && frame.isMain()) {
                    String url = cefBrowser.getURL();
                    if (url != null && isHttp(url)) {
                        currentUrl = url;
                        SwingUtilities.invokeLater(() -> urlField.setText(url));
                    }
                    if (CodeMapUrls.isOwnPage(url)) injectBridge(cefBrowser);  // never expose the bridge to other sites
                }
            }

            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame frame, CefLoadHandler.ErrorCode errorCode,
                                    String errorText, String failedUrl) {
                if (frame == null || !frame.isMain() || errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return;
                if (failedUrl == null || !isHttp(failedUrl)) return;
                String msg = "Could not load " + failedUrl + " (" + (errorText == null || errorText.isEmpty()
                        ? String.valueOf(errorCode) : errorText) + ").";
                if (CodeMapUrls.isOwnPage(failedUrl)) {
                    msg += "\nThe page is served by the IDE's built-in server (Settings | Build, Execution, Deployment"
                            + " | Debugger | Built-in Server); Retry reloads it.";
                }
                String m = msg;
                ApplicationManager.getApplication().invokeLater(() -> showError(m), ModalityState.any());
            }
        }, browser.getCefBrowser());
        root.add(browser.getComponent(), BorderLayout.CENTER);
    }

    /** Exposes window.codemapIde.open(file, line) to the web UI (file: absolute or relative to project root). */
    private void injectBridge(CefBrowser cefBrowser) {
        if (openQuery == null) return;
        String js = "(function(){"
                + "window.codemapIde = { embedded: true, open: function(file, line) {"
                + "  var payload = String(file) + '\\n' + String(line || 1);"
                + "  " + openQuery.inject("payload") + ";"
                + "  return true; } };"
                + "try { window.dispatchEvent(new Event('codemap-ide-ready')); } catch (e) {}"
                + "})();";
        cefBrowser.executeJavaScript(js, cefBrowser.getURL(), 0);
    }

    // ------------------------------------------------------------------ navigation

    /** Navigate to an absolute URL; null = this project's map. Call on the EDT. */
    public void open(String url) {
        navigate(url, true);
    }

    private String targetOrHome() {
        String t = target;
        return t != null ? t : CodeMapUrls.projectUrl(project, null);
    }

    /** @param browseExternally in fallback (no JCEF) mode, also open the URL in the external browser */
    private void navigate(String url, boolean browseExternally) {
        if (disposed) return;
        String u = url == null || url.isEmpty() ? CodeMapUrls.projectUrl(project, null) : url;
        if (!isHttp(u)) u = "http://" + u;
        target = u;
        currentUrl = u;
        urlField.setText(u);
        if (browser != null) {
            browser.loadURL(u);
        } else {
            fallbackStatus.setText("<html>Code Map URL: <code>" + esc(u) + "</code></html>");
            if (browseExternally) BrowserUtil.browse(u);
        }
    }

    /** Reload the last target, keeping the in-app #fragment (focus) of the page currently shown. */
    public void retry() {
        String t = targetOrHome();
        String cur = currentUrl;
        if (cur != null && sameDocument(cur, t)) {
            int h = cur.indexOf('#');
            if (h >= 0 && h < cur.length() - 1) {
                int th = t.indexOf('#');
                t = (th >= 0 ? t.substring(0, th) : t) + cur.substring(h);
            }
        }
        navigate(t, true);
    }

    private static boolean sameDocument(String a, String b) {
        int ha = a.indexOf('#'), hb = b.indexOf('#');
        return (ha >= 0 ? a.substring(0, ha) : a).equals(hb >= 0 ? b.substring(0, hb) : b);
    }

    public void reload() {
        if (currentUrl == null) {
            retry();
            return;
        }
        if (browser != null) browser.getCefBrowser().reloadIgnoreCache();
        else BrowserUtil.browse(currentUrl);
    }

    private void openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, CodeMapConfigurable.class);
    }

    // ------------------------------------------------------------------ error page

    private void showError(String reason) {
        if (disposed) return;
        currentUrl = null;
        if (browser != null) {
            String buttons = actionQuery == null ? "" :
                    "<p><button onclick=\"cm('retry')\">Retry</button> "
                    + "<button onclick=\"cm('settings')\">Code Map Settings</button></p>"
                    + "<script>function cm(a){" + actionQuery.inject("a") + ";}</script>";
            browser.loadHTML(page("<h2>Code Map could not be loaded</h2><pre>" + esc(reason) + "</pre>" + buttons
                    + "<p class='dim'>Details are in idea.log (category codemap).</p>"));
        } else {
            fallbackStatus.setText("<html><b>Error:</b><pre>" + esc(reason) + "</pre></html>");
        }
    }

    private static String page(String body) {
        Color bg = UIUtil.getPanelBackground();
        Color fg = UIUtil.getLabelForeground();
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><style>"
                + "body{background:" + hex(bg) + ";color:" + hex(fg) + ";font-family:system-ui,sans-serif;"
                + "margin:0;padding:48px;}"
                + "pre{white-space:pre-wrap;background:rgba(127,127,127,.12);padding:12px;border-radius:6px;"
                + "font-size:12px;max-width:960px;}"
                + ".dim{opacity:.7}button{font-size:13px;padding:6px 14px;margin-right:6px;cursor:pointer}"
                + "</style></head><body>" + body + "</body></html>";
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static boolean isHttp(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    @Override
    public void dispose() {
        disposed = true;
        if (project.getUserData(KEY) == this) project.putUserData(KEY, null);
        browser = null;
        openQuery = null;
        actionQuery = null;
    }
}

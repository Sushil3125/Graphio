package com.sushil.codemap;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.sushil.codemap.analysis.CodeGraphService;
import com.sushil.codemap.analysis.GraphSnapshot;
import com.sushil.codemap.analysis.GraphStatus;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.jetbrains.ide.HttpRequestHandler;
import org.jetbrains.io.Responses;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Code Map on the IDE's built-in server (no extra process or port). Loopback clients with a loopback Host only.
 * <pre>
 *   GET  /codemap/                         project list (open projects)
 *   GET  /codemap/api/projects             {"projects":[{id,project,root,url}]}
 *   GET  /codemap/&lt;id&gt;/...                  bundled web UI (jar resources /codemap-web/**)
 *   GET  /codemap/&lt;id&gt;/api/graph            graph JSON (gzip if accepted)
 *   GET  /codemap/&lt;id&gt;/api/status           {version,id,root,state,done,total,message,ready,errors}
 *   GET  /codemap/&lt;id&gt;/api/source?file&amp;start&amp;end   lines from the editor document (unsaved edits included)
 *   POST /codemap/&lt;id&gt;/api/open {file,line}  navigate in the IDE
 *   POST /codemap/&lt;id&gt;/api/rebuild          full rebuild
 *   GET  /codemap/&lt;id&gt;/api/events           SSE: event "graph" {version,changed} + 15 s heartbeat
 *   GET  /api/codemap/ping                     {"ok":true,"plugin":"codemap"} (used by the standalone Python server)
 *   GET  /api/codemap/open?file=&lt;abs&gt;&amp;line=&lt;n&gt;  opens a file at a line
 * </pre>
 * &lt;id&gt; = {@link CodeMapUrls#projectId}. Netty I/O threads never block: graph encoding/gzip, document reads and
 * navigation run on pooled threads and answer from there.
 */
public final class CodeMapHttpHandler extends HttpRequestHandler {
    private static final Logger LOG = Logger.getInstance(CodeMapHttpHandler.class);
    static final String LEGACY_PREFIX = "/api/codemap/";
    static final String VERSION = "0.5.0";
    static final int MAX_SOURCE_LINES = 2000;
    private static final String WEB_RESOURCES = "codemap-web/";
    private static final Pattern PROJECT_PATH = Pattern.compile("^/codemap/([A-Za-z0-9_.-]+)(/.*)?$");
    private static final Key<GraphCache> GRAPH_CACHE = Key.create("com.sushil.codemap.graphCache");
    private static final Map<String, StaticFile> STATIC = new ConcurrentHashMap<>();
    private static final StaticFile MISSING = new StaticFile(new byte[0], null, "");

    @Override
    public boolean isSupported(FullHttpRequest request) {
        HttpMethod m = request.method();
        if (m != HttpMethod.GET && m != HttpMethod.HEAD && m != HttpMethod.POST && m != HttpMethod.OPTIONS) return false;
        String path = new QueryStringDecoder(request.uri()).path();
        return path.equals("/codemap") || path.startsWith(CodeMapUrls.PREFIX)
                || path.equals("/api/codemap") || path.startsWith(LEGACY_PREFIX);
    }

    @Override
    public boolean process(QueryStringDecoder urlDecoder, FullHttpRequest request, ChannelHandlerContext context)
            throws IOException {
        Channel ch = context.channel();
        if (!isLocal(ch.remoteAddress())) {
            sendError(ch, request, HttpResponseStatus.FORBIDDEN, "localhost only");
            return true;
        }
        if (!isLoopbackHost(request.headers().get(HttpHeaderNames.HOST))) {
            sendError(ch, request, HttpResponseStatus.FORBIDDEN, "forbidden host");
            return true;
        }
        String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (origin != null && !isLocalOrigin(origin)) {
            sendError(ch, request, HttpResponseStatus.FORBIDDEN, "origin not allowed");
            return true;
        }
        if (request.method() == HttpMethod.OPTIONS) {
            FullHttpResponse r = response(HttpResponseStatus.NO_CONTENT, "text/plain", new byte[0], false);
            r.headers().set(HttpHeaderNames.ALLOW, "GET, HEAD, POST, OPTIONS");
            send(ch, request, r);
            return true;
        }
        String path = urlDecoder.path();
        try {
            if (path.equals("/api/codemap") || path.startsWith(LEGACY_PREFIX)) {
                legacy(path, urlDecoder, request, ch);
            } else {
                codemap(path, urlDecoder, request, ch);
            }
        } catch (RuntimeException e) {
            LOG.warn("codemap: request failed: " + request.uri(), e);
            sendError(ch, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, String.valueOf(e.getMessage()));
        }
        return true;
    }

    // ------------------------------------------------------------------ /codemap/...

    private void codemap(String path, QueryStringDecoder q, FullHttpRequest request, Channel ch) {
        boolean get = request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD;
        if (path.equals("/codemap")) {
            redirect(ch, request, "/codemap/");
            return;
        }
        if (path.equals("/codemap/") || path.equals("/codemap/index.html")) {
            sendHtml(ch, request, HttpResponseStatus.OK, projectsPage());
            return;
        }
        if (path.equals("/codemap/api/projects")) {
            sendJson(ch, request, HttpResponseStatus.OK, projectsJson());
            return;
        }
        Matcher m = PROJECT_PATH.matcher(path);
        if (!m.matches()) {
            sendError(ch, request, HttpResponseStatus.NOT_FOUND, "not found");
            return;
        }
        String id = m.group(1);
        String rest = m.group(2);
        Project project = CodeMapUrls.findProject(id);
        if (project == null) {
            sendHtml(ch, request, HttpResponseStatus.NOT_FOUND, unknownProjectPage(id));
            return;
        }
        if (rest == null) {  // relative URLs in the UI need the trailing slash
            String uri = request.uri();
            String query = uri.contains("?") ? uri.substring(uri.indexOf('?')) : "";
            redirect(ch, request, "/codemap/" + id + "/" + query);
            return;
        }
        CodeGraphService service = CodeGraphService.getInstance(project);
        rest = rest.substring(1);
        if (rest.startsWith("api/")) {
            String api = rest.substring(4);
            while (api.endsWith("/")) api = api.substring(0, api.length() - 1);
            kickStart(service);
            boolean post = request.method() == HttpMethod.POST;
            switch (api) {
                case "graph" -> { if (get) graph(project, service, request, ch); else methodNotAllowed(ch, request); }
                case "status" -> {
                    if (get) sendJson(ch, request, HttpResponseStatus.OK, statusJson(project, service));
                    else methodNotAllowed(ch, request);
                }
                case "events" -> { if (get) CodeMapEvents.open(service, ch); else methodNotAllowed(ch, request); }
                case "source" -> { if (get) source(project, q, request, ch); else methodNotAllowed(ch, request); }
                case "open" -> { if (post) openPost(project, request, ch); else methodNotAllowed(ch, request); }
                case "rebuild" -> {
                    if (!post) {
                        methodNotAllowed(ch, request);
                        return;
                    }
                    ApplicationManager.getApplication().executeOnPooledThread(service::requestRebuild);
                    sendJson(ch, request, HttpResponseStatus.OK, "{\"ok\":true}");
                }
                default -> sendError(ch, request, HttpResponseStatus.NOT_FOUND, "unknown endpoint");
            }
            return;
        }
        if (!get) {
            methodNotAllowed(ch, request);
            return;
        }
        if (rest.isEmpty() || rest.equals("index.html")) kickStart(service);  // start analysing while the UI loads
        serveStatic(rest.isEmpty() ? "index.html" : rest, request, ch);
    }

    /** A project's first request starts its engine (off the I/O thread; ensureStarted is idempotent). */
    private static void kickStart(CodeGraphService service) {
        GraphStatus st = service.status();
        if (st == null || "idle".equals(st.state())) {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    service.ensureStarted();
                } catch (Throwable t) {
                    LOG.warn("codemap: ensureStarted failed", t);
                }
            });
        }
    }

    // ------------------------------------------------------------------ api/graph, api/status

    /** UTF-8 bytes (+ gzip, lazily) and error count of one snapshot, computed once per published snapshot. */
    private record GraphCache(GraphSnapshot snapshot, byte[] raw, byte[] gz, int errors) {}

    private static GraphCache graphCache(Project project, GraphSnapshot snap, boolean needGzip) {
        GraphCache c = project.getUserData(GRAPH_CACHE);
        boolean same = c != null && c.snapshot == snap;
        if (same && (c.gz != null || !needGzip)) return c;
        byte[] raw = same ? c.raw : snap.json().getBytes(StandardCharsets.UTF_8);
        int errors = same ? c.errors : errorCount(snap);
        GraphCache n = new GraphCache(snap, raw, needGzip ? gzip(raw) : null, errors);
        project.putUserData(GRAPH_CACHE, n);
        return n;
    }

    private static int errorCount(GraphSnapshot snap) {
        return Math.max(0, Json.topLevelArrayLength(snap.json(), "errors"));
    }

    private static void graph(Project project, CodeGraphService service, FullHttpRequest request, Channel ch) {
        boolean wantGzip = acceptsGzip(request);
        GraphSnapshot snap = service.snapshot();
        GraphCache c = project.getUserData(GRAPH_CACHE);
        if (c != null && c.snapshot == snap && (!wantGzip || c.gz != null)) {
            sendGraph(ch, request, c, wantGzip);
            return;
        }
        // UTF-8 encoding + gzip of a multi-MB graph: not on the Netty event loop
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                sendGraph(ch, request, graphCache(project, snap, wantGzip), wantGzip);
            } catch (Throwable t) {
                LOG.warn("codemap: api/graph failed", t);
                sendError(ch, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
            }
        });
    }

    private static void sendGraph(Channel ch, HttpRequest request, GraphCache c, boolean gz) {
        FullHttpResponse r = response(HttpResponseStatus.OK, "application/json; charset=utf-8", gz ? c.gz : c.raw, gz);
        r.headers().set(HttpHeaderNames.VARY, "Accept-Encoding");
        send(ch, request, r);
    }

    static String statusJson(Project project, CodeGraphService service) {
        GraphSnapshot snap = service.snapshot();
        GraphStatus st = service.status();
        String state = st == null || st.state() == null ? "idle" : st.state();
        GraphCache c = project.getUserData(GRAPH_CACHE);
        int errors = c != null && c.snapshot == snap ? c.errors : errorCount(snap);
        boolean ready = "ready".equals(state) || snap.version() > 0;
        return "{\"version\":" + snap.version()
                + ",\"id\":" + Json.str(CodeMapUrls.projectId(project))
                + ",\"project\":" + Json.str(project.getName())
                + ",\"root\":" + Json.str(project.getBasePath())
                + ",\"state\":" + Json.str(state)
                + ",\"done\":" + (st == null ? 0 : st.done())
                + ",\"total\":" + (st == null ? 0 : st.total())
                + ",\"message\":" + Json.str(st == null || st.message() == null ? "" : st.message())
                + ",\"ready\":" + ready
                + ",\"errors\":" + errors
                + ",\"clients\":" + CodeMapEvents.OPEN.get()
                + "}";
    }

    // ------------------------------------------------------------------ api/source

    /** Path of {@code file} (relative to the project root, or absolute) if it stays inside the root, else null. */
    static Path confine(String root, String file) {
        if (root == null || file == null || file.isEmpty() || file.indexOf('\0') >= 0) return null;
        try {
            Path r = Path.of(root).toAbsolutePath().normalize();
            try { r = r.toRealPath(); } catch (IOException ignored) { /* keep normalized */ }
            Path p = Path.of(file);
            Path c = (p.isAbsolute() ? p : r.resolve(p)).normalize();
            if (Files.exists(c)) {
                try { c = c.toRealPath(); } catch (IOException ignored) { /* keep normalized */ }
            }
            return c.startsWith(r) ? c : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static void source(Project project, QueryStringDecoder q, FullHttpRequest request, Channel ch) {
        String file = param(q, "file");
        int start, end;
        try {
            start = Integer.parseInt(paramOr(q, "start", "1").trim());
            String e = paramOr(q, "end", "").trim();
            end = e.isEmpty() ? -1 : Integer.parseInt(e);
        } catch (NumberFormatException ex) {
            sendError(ch, request, HttpResponseStatus.BAD_REQUEST, "start/end must be integers");
            return;
        }
        String base = project.getBasePath();
        Path path = confine(base, file);
        if (path == null) {
            sendError(ch, request, HttpResponseStatus.FORBIDDEN, "path outside project root");
            return;
        }
        int s0 = start, e0 = end;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(path);
                if (vf == null) vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
                if (vf == null || vf.isDirectory()) {
                    sendError(ch, request, HttpResponseStatus.NOT_FOUND, "file not found");
                    return;
                }
                VirtualFile f = vf;
                String text = ReadAction.compute(() -> {
                    if (!f.isValid()) return null;
                    Document doc = FileDocumentManager.getInstance().getDocument(f);  // unsaved edits included
                    if (doc != null) return doc.getText();
                    try {
                        return VfsUtilCore.loadText(f);
                    } catch (IOException e) {
                        return null;
                    }
                });
                if (text == null) {
                    sendError(ch, request, HttpResponseStatus.NOT_FOUND, "file not readable");
                    return;
                }
                Path root = confine(base, ".");
                String rel = root == null ? path.toString() : root.relativize(path).toString().replace('\\', '/');
                sendJson(ch, request, HttpResponseStatus.OK, sourceJson(rel, text, s0, e0));
            } catch (Throwable t) {
                LOG.warn("codemap: api/source failed", t);
                sendError(ch, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
            }
        });
    }

    /** Same shape and line semantics as server.py (str.splitlines; end &lt; start or missing = to the end). */
    static String sourceJson(String rel, String text, int start, int end) {
        List<String> lines = splitLines(text);
        start = Math.max(1, start);
        if (end < start) end = lines.size();
        end = Math.min(Math.min(end, lines.size()), start + MAX_SOURCE_LINES - 1);
        StringBuilder sb = new StringBuilder("{\"file\":").append(Json.str(rel))
                .append(",\"start\":").append(start).append(",\"end\":").append(end)
                .append(",\"total\":").append(lines.size()).append(",\"lines\":[");
        for (int i = start; i <= end; i++) {
            if (i > start) sb.append(',');
            sb.append(Json.str(lines.get(i - 1)));
        }
        return sb.append("]}").toString();
    }

    static List<String> splitLines(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.isEmpty()) return List.of();
        if (t.endsWith("\n")) t = t.substring(0, t.length() - 1);
        return List.of(t.split("\n", -1));
    }

    // ------------------------------------------------------------------ api/open

    private static void openPost(Project project, FullHttpRequest request, Channel ch) {
        String file;
        int line;
        try {
            Object body = Json.parse(request.content().toString(StandardCharsets.UTF_8));
            if (!(body instanceof Map<?, ?> m) || !(m.get("file") instanceof String f)) throw new IllegalArgumentException();
            file = f;
            Object l = m.get("line");
            if (l instanceof Number n) line = n.intValue();
            else if (l instanceof String s && !s.isBlank()) line = Integer.parseInt(s.trim());
            else line = 1;
        } catch (RuntimeException e) {
            sendError(ch, request, HttpResponseStatus.BAD_REQUEST, "expected JSON body {\"file\": \"<rel>\", \"line\": N}");
            return;
        }
        Path path = confine(project.getBasePath(), file);
        if (path == null || !Files.isRegularFile(path)) {
            sendJson(ch, request, HttpResponseStatus.OK,
                    "{\"ok\":false,\"error\":" + Json.str("file not found: " + file) + "}");
            return;
        }
        navigate(path.toString(), Math.max(1, line), project, ch, request, "{\"ok\":true,\"via\":\"ide\"}");
    }

    /**
     * Navigates without blocking the I/O thread. EDT busy (e.g. modal dialog) for 4 s -> answer "pending"; the
     * navigation still happens when it frees up. Only the request's headers are used later.
     */
    private static void navigate(String file, int line, Project hint, Channel ch, HttpRequest request, String okJson) {
        IdeNavigator.open(file, line, hint)
                .completeOnTimeout(PENDING, 4, TimeUnit.SECONDS)
                .whenComplete((err, ex) -> {
                    String json;
                    if (ex != null) err = String.valueOf(ex.getMessage());
                    if (err == null) json = okJson;
                    else if (PENDING.equals(err)) json = "{\"ok\":true,\"pending\":true}";
                    else {
                        LOG.info("codemap: open failed: " + err);
                        json = "{\"ok\":false,\"error\":" + Json.str(err) + "}";
                    }
                    sendJson(ch, request, HttpResponseStatus.OK, json);
                });
    }

    /** Marker result for "navigation scheduled, EDT busy". */
    private static final String PENDING = "\u0000pending";

    // ------------------------------------------------------------------ /api/codemap/ (ping, open by abs path)

    private void legacy(String path, QueryStringDecoder q, FullHttpRequest request, Channel ch) {
        String action = path.length() > LEGACY_PREFIX.length() ? path.substring(LEGACY_PREFIX.length()) : "";
        while (action.endsWith("/")) action = action.substring(0, action.length() - 1);
        switch (action) {
            case "ping" -> sendJson(ch, request, HttpResponseStatus.OK,
                    "{\"ok\":true,\"plugin\":\"codemap\",\"version\":\"" + VERSION + "\",\"web\":"
                            + Json.str(CodeMapUrls.PREFIX) + "}");
            case "open" -> {
                String file = param(q, "file");
                int line = 1;
                try { line = Integer.parseInt(paramOr(q, "line", "1").trim()); } catch (NumberFormatException ignored) {}
                if (file == null || file.isEmpty()) {
                    sendError(ch, request, HttpResponseStatus.BAD_REQUEST, "missing 'file' parameter");
                    return;
                }
                File f = new File(file);
                if (f.isAbsolute() && !f.isFile()) {
                    sendJson(ch, request, HttpResponseStatus.NOT_FOUND,
                            "{\"ok\":false,\"error\":" + Json.str("file not found: " + file) + "}");
                    return;
                }
                if (!IdeNavigator.hasOpenProject()) {
                    sendError(ch, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "no open project");
                    return;
                }
                navigate(file, line, null, ch, request, "{\"ok\":true}");
            }
            default -> sendError(ch, request, HttpResponseStatus.NOT_FOUND, "unknown endpoint");
        }
    }

    // ------------------------------------------------------------------ static UI

    private record StaticFile(byte[] raw, byte[] gz, String type) {}

    private void serveStatic(String rel, FullHttpRequest request, Channel ch) {
        if (rel.contains("..") || rel.contains("\\") || rel.startsWith("/") || rel.indexOf('\0') >= 0) {
            sendError(ch, request, HttpResponseStatus.FORBIDDEN, "forbidden");
            return;
        }
        if (rel.endsWith("/")) rel = rel + "index.html";
        StaticFile f = STATIC.computeIfAbsent(rel, CodeMapHttpHandler::loadStatic);
        if (f == MISSING) {
            STATIC.remove(rel);  // don't let random URLs grow the cache
            send(ch, request, response(HttpResponseStatus.NOT_FOUND, "text/plain; charset=utf-8",
                    "not found".getBytes(StandardCharsets.UTF_8), false));
            return;
        }
        boolean gz = f.gz != null && acceptsGzip(request);
        FullHttpResponse r = response(HttpResponseStatus.OK, f.type, gz ? f.gz : f.raw, gz);
        if (f.gz != null) r.headers().set(HttpHeaderNames.VARY, "Accept-Encoding");
        send(ch, request, r);
    }

    private static StaticFile loadStatic(String rel) {
        try (InputStream in = CodeMapHttpHandler.class.getClassLoader().getResourceAsStream(WEB_RESOURCES + rel)) {
            if (in == null) return MISSING;
            byte[] raw = in.readAllBytes();
            String type = contentType(rel);
            boolean text = type.startsWith("text/") || type.contains("json") || type.contains("svg");
            return new StaticFile(raw, text && raw.length > 2048 ? gzip(raw) : null, type);
        } catch (IOException e) {
            LOG.warn("codemap: cannot read resource " + rel, e);
            return MISSING;
        }
    }

    static String contentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json") || n.endsWith(".map")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".md") || n.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ pages

    private static String projectsJson() {
        StringBuilder sb = new StringBuilder("{\"projects\":[");
        boolean first = true;
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            String id = p.isDisposed() ? null : CodeMapUrls.projectId(p);
            if (id == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(Json.str(id)).append(",\"project\":").append(Json.str(p.getName()))
              .append(",\"root\":").append(Json.str(p.getBasePath()))
              .append(",\"url\":").append(Json.str(CodeMapUrls.PREFIX + id + "/")).append('}');
        }
        return sb.append("]}").toString();
    }

    private static final String PAGE_CSS = "body{background:#1e1f22;color:#dfe1e5;font:14px system-ui,sans-serif;"
            + "margin:0;padding:40px 48px}h1{font-size:20px;font-weight:600}a{color:#548af7;text-decoration:none}"
            + "a:hover{text-decoration:underline}code{font-family:'JetBrains Mono',monospace}li{margin:8px 0}"
            + ".muted{color:#868a91}";

    private static String projectsPage() {
        StringBuilder items = new StringBuilder();
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            String id = p.isDisposed() ? null : CodeMapUrls.projectId(p);
            if (id == null) continue;
            items.append("<li><a href=\"").append(esc(id)).append("/\">").append(esc(p.getName())).append("</a> ")
                 .append("<span class=\"muted\"><code>").append(esc(p.getBasePath())).append("</code></span></li>");
        }
        if (items.isEmpty()) items.append("<li class=\"muted\">No project is open in the IDE.</li>");
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Code Map: projects</title><style>" + PAGE_CSS
                + "</style></head><body><h1>Code Map: open projects</h1><p class=\"muted\">Each project open in the IDE"
                + " has its own map. In PyCharm, <b>Open Code Map</b> (Ctrl+Alt+Shift+K) opens the current project's map"
                + " in an editor tab.</p><ul>" + items + "</ul></body></html>";
    }

    private static String unknownProjectPage(String id) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Code Map: unknown project</title><style>"
                + PAGE_CSS + "</style></head><body><h1>Project not open</h1><p>No project with id <code>" + esc(id)
                + "</code> is open in the IDE. Open it in PyCharm, or pick one on the <a href=\"/codemap/\">projects"
                + " page</a>.</p></body></html>";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // ------------------------------------------------------------------ helpers

    private static String param(QueryStringDecoder d, String name) {
        List<String> v = d.parameters().get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    private static String paramOr(QueryStringDecoder d, String name, String dflt) {
        String v = param(d, name);
        return v == null ? dflt : v;
    }

    static boolean isLocal(SocketAddress addr) {
        if (addr instanceof InetSocketAddress isa) {
            return isa.getAddress() != null && isa.getAddress().isLoopbackAddress();
        }
        return false;
    }

    /** DNS-rebinding guard: Host must name the loopback interface (missing Host is allowed, as in server.py). */
    static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) return true;
        String h = host.trim().toLowerCase(Locale.ROOT);
        String name;
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            name = close > 0 ? h.substring(0, close + 1) : h;
        } else {
            int colon = h.lastIndexOf(':');
            name = colon >= 0 ? h.substring(0, colon) : h;
        }
        return name.equals("127.0.0.1") || name.equals("localhost") || name.equals("[::1]");
    }

    static boolean isLocalOrigin(String origin) {
        if ("null".equals(origin)) return false;
        try {
            String host = new URI(origin).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean acceptsGzip(HttpRequest request) {
        String ae = request.headers().get(HttpHeaderNames.ACCEPT_ENCODING);
        return ae != null && ae.toLowerCase(Locale.ROOT).contains("gzip");
    }

    static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos, 1 << 16) {{ def.setLevel(5); }}) {
            gz.write(raw);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    private static FullHttpResponse response(HttpResponseStatus status, String type, byte[] body, boolean gz) {
        FullHttpResponse r = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        r.headers().set(HttpHeaderNames.CONTENT_TYPE, type);
        if (gz) r.headers().set(HttpHeaderNames.CONTENT_ENCODING, "gzip");
        HttpUtil.setContentLength(r, body.length);
        Responses.addNoCache(r);
        return r;
    }

    /** Safe from any thread: Netty queues writes from other threads onto the channel's event loop. */
    private static void send(Channel ch, HttpRequest request, FullHttpResponse r) {
        String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (origin != null && isLocalOrigin(origin)) {
            r.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            r.headers().add(HttpHeaderNames.VARY, "Origin");
        }
        Responses.send(r, ch, request);
    }

    static void sendJson(Channel ch, HttpRequest request, HttpResponseStatus status, String json) {
        send(ch, request, response(status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), false));
    }

    static void sendError(Channel ch, HttpRequest request, HttpResponseStatus status, String message) {
        sendJson(ch, request, status, "{\"ok\":false,\"error\":" + Json.str(message) + "}");
    }

    private static void sendHtml(Channel ch, HttpRequest request, HttpResponseStatus status, String html) {
        send(ch, request, response(status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), false));
    }

    private static void methodNotAllowed(Channel ch, HttpRequest request) {
        sendError(ch, request, HttpResponseStatus.METHOD_NOT_ALLOWED, "method not allowed");
    }

    private static void redirect(Channel ch, HttpRequest request, String location) {
        FullHttpResponse r = response(HttpResponseStatus.FOUND, "text/plain; charset=utf-8", new byte[0], false);
        r.headers().set(HttpHeaderNames.LOCATION, location);
        send(ch, request, r);
    }
}

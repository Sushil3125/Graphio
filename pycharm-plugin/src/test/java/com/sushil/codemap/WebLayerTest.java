package com.sushil.codemap;

import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.sushil.codemap.analysis.CodeGraphService;
import org.jetbrains.ide.BuiltInServerManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

/** Web layer on the real built-in server: routing, guards, static UI, status/graph, SSE lifecycle. */
public class WebLayerTest extends BasePlatformTestCase {
    private int port;
    private String id;

    @Override
    protected void setUp() throws Exception {
        System.setProperty("codemap.sse.heartbeatSec", "1");
        super.setUp();
        port = BuiltInServerManager.getInstance().waitForStart().getPort();
        id = CodeMapUrls.projectId(getProject());
        assertNotNull("light project has a base path", id);
    }

    record Resp(int status, Map<String, String> headers, String body) {}

    /** One request on a fresh connection (Connection: close), raw so the Host header can be set freely. */
    private Resp request(String method, String path, String host, String extraHeaders, String body) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(200);
            byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String req = method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n"
                    + (extraHeaders == null ? "" : extraHeaders)
                    + (body == null ? "" : "Content-Type: application/json\r\nContent-Length: " + b.length + "\r\n") + "\r\n";
            OutputStream out = s.getOutputStream();
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.write(b);
            out.flush();
            byte[] raw = readAll(s.getInputStream(), 20_000);
            return parse(raw);
        }
    }

    private Resp get(String path) throws Exception {
        return request("GET", path, "127.0.0.1:" + port, null, null);
    }

    /** Reads until EOF, pumping the EDT (handlers may need it; the test runs on the EDT). */
    private static byte[] readAll(InputStream in, long timeoutMs) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        long end = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[65536];
        while (System.currentTimeMillis() < end) {
            try {
                int n = in.read(buf);
                if (n < 0) return bos.toByteArray();
                bos.write(buf, 0, n);
            } catch (SocketTimeoutException e) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            }
        }
        throw new AssertionError("timeout reading response");
    }

    private static Resp parse(byte[] raw) throws IOException {
        int sep = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertTrue("no header end", sep > 0);
        String head = new String(raw, 0, sep, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> h = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            h.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
        }
        byte[] body = java.util.Arrays.copyOfRange(raw, sep + 4, raw.length);
        if ("gzip".equals(h.get("content-encoding"))) {
            body = new GZIPInputStream(new java.io.ByteArrayInputStream(body)).readAllBytes();
        }
        return new Resp(status, h, new String(body, StandardCharsets.UTF_8));
    }

    private static int indexOf(byte[] a, byte[] pat) {
        outer:
        for (int i = 0; i + pat.length <= a.length; i++) {
            for (int j = 0; j < pat.length; j++) if (a[i + j] != pat[j]) continue outer;
            return i;
        }
        return -1;
    }

    public void testLegacyPingAndGuards() throws Exception {
        Resp ping = get("/api/codemap/ping");
        assertEquals(200, ping.status);
        assertTrue(ping.body, ping.body.contains("\"plugin\":\"codemap\""));
        // DNS rebinding: a foreign Host never reaches the page (the platform's isAccessible answers 404 first,
        // CodeMapHttpHandler would answer 403)
        Resp evil = request("GET", "/codemap/", "evil.example:" + port, null, null);
        assertTrue(String.valueOf(evil.status), evil.status == 403 || evil.status == 404);
        assertFalse(evil.body.contains("open projects"));
        assertFalse(CodeMapHttpHandler.isLoopbackHost("evil.example:" + port));
        assertTrue(CodeMapHttpHandler.isLoopbackHost("localhost:" + port));
        Resp foreign = request("GET", "/codemap/" + id + "/api/status", "127.0.0.1:" + port,
                "Origin: http://evil.example\r\n", null);
        assertTrue(String.valueOf(foreign.status), foreign.status == 403 || foreign.status == 404);
        assertFalse(foreign.body.contains("\"state\""));
        assertFalse(CodeMapHttpHandler.isLocalOrigin("http://evil.example"));
        assertFalse(CodeMapHttpHandler.isLocalOrigin("null"));
    }

    public void testProjectListStaticAndRedirect() throws Exception {
        Resp list = get("/codemap/");
        assertEquals(200, list.status);
        assertTrue(list.body, list.body.contains("href=\"" + id + "/\""));
        Resp json = get("/codemap/api/projects");
        assertTrue(json.body, json.body.contains("\"id\":\"" + id + "\""));

        Resp redirect = get("/codemap/" + id);
        assertEquals(302, redirect.status);
        assertEquals("/codemap/" + id + "/", redirect.headers.get("location"));

        Resp index = get("/codemap/" + id + "/");
        assertEquals(200, index.status);
        assertTrue(index.headers.get("content-type").startsWith("text/html"));
        assertTrue(index.body.contains("app.js"));

        Resp js = request("GET", "/codemap/" + id + "/app.js", "127.0.0.1:" + port, "Accept-Encoding: gzip\r\n", null);
        assertEquals(200, js.status);
        assertEquals("gzip", js.headers.get("content-encoding"));
        assertTrue(js.body.contains("EventSource"));

        assertEquals(404, get("/codemap/" + id + "/nope.js").status);
        assertEquals(403, get("/codemap/" + id + "/vendor/..%2f..%2fMETA-INF/plugin.xml").status);
        assertEquals(404, get("/codemap/unknown-12345678/").status);
    }

    public void testStatusGraphSourceAndOpenGuards() throws Exception {
        myFixture.addFileToProject("pkg/mod.py", "def a():\n    b()\n\ndef b():\n    pass\n");
        CodeGraphService service = CodeGraphService.getInstance(getProject());
        await(service.rebuildAsync());

        Resp status = get("/codemap/" + id + "/api/status");
        assertEquals(200, status.status);
        assertTrue(status.body, status.body.contains("\"state\":") && status.body.contains("\"ready\":"));
        assertTrue(status.body, status.body.contains("\"id\":\"" + id + "\""));

        Resp graph = request("GET", "/codemap/" + id + "/api/graph", "127.0.0.1:" + port, "Accept-Encoding: gzip\r\n", null);
        assertEquals(200, graph.status);
        assertEquals("gzip", graph.headers.get("content-encoding"));
        assertEquals(service.snapshot().json(), graph.body);

        assertEquals(403, get("/codemap/" + id + "/api/source?file=../../../../etc/passwd&start=1&end=3").status);
        assertEquals(403, get("/codemap/" + id + "/api/source?file=/etc/passwd").status);
        assertEquals(400, request("POST", "/codemap/" + id + "/api/open", "127.0.0.1:" + port,
                "Origin: http://127.0.0.1:" + port + "\r\n", "not json").status);
        Resp open = request("POST", "/codemap/" + id + "/api/open", "127.0.0.1:" + port,
                "Origin: http://127.0.0.1:" + port + "\r\n", "{\"file\":\"../../etc/passwd\",\"line\":1}");
        assertEquals(200, open.status);
        assertTrue(open.body, open.body.contains("\"ok\":false"));
        assertEquals(405, get("/codemap/" + id + "/api/open").status);
    }

    public void testEventStreamLifecycle() throws Exception {
        CodeGraphService service = CodeGraphService.getInstance(getProject());
        int before = CodeMapEvents.OPEN.get();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(200);
            s.getOutputStream().write(("GET /codemap/" + id + "/api/events HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            StringBuilder got = new StringBuilder();
            InputStream in = s.getInputStream();
            readUntil(in, got, "event: hello", 10_000);
            String head = got.toString().toLowerCase(Locale.ROOT);
            assertTrue(head, head.contains("content-type: text/event-stream"));
            assertTrue(head, head.contains("transfer-encoding: chunked"));
            assertFalse(head, head.contains("content-length"));
            assertEquals(before + 1, CodeMapEvents.OPEN.get());

            readUntil(in, got, ": heartbeat", 10_000);  // 1 s in tests (15 s by default)

            myFixture.addFileToProject("ev/one.py", "def f():\n    pass\n");
            long v = await(service.rebuildAsync()).version();
            readUntil(in, got, "event: graph\ndata: {\"version\":" + v, 20_000);
        }
        long end = System.currentTimeMillis() + 10_000;  // channel close -> stream disposed, listener removed
        while (CodeMapEvents.OPEN.get() != before && System.currentTimeMillis() < end) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            Thread.sleep(20);
        }
        assertEquals(before, CodeMapEvents.OPEN.get());
    }

    private static void readUntil(InputStream in, StringBuilder got, String marker, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[8192];
        while (got.indexOf(marker) < 0) {
            if (System.currentTimeMillis() > end) throw new AssertionError("no '" + marker + "' in:\n" + got);
            try {
                int n = in.read(buf);
                if (n < 0) throw new AssertionError("stream closed before '" + marker + "':\n" + got);
                got.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            } catch (SocketTimeoutException e) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            }
        }
    }

    private static <T> T await(CompletableFuture<T> f) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!f.isDone()) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for the engine");
            Thread.sleep(5);
        }
        return f.get();
    }
}

package com.sushil.codemap;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.ide.BuiltInServerManager;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Project ids and URLs of the Code Map pages on the IDE's built-in server:
 * {@code http://127.0.0.1:<port>/codemap/<id>/[#focus=<node id>]}.
 */
public final class CodeMapUrls {
    /** Path prefix of all Code Map pages on the built-in server. */
    public static final String PREFIX = "/codemap/";

    private CodeMapUrls() {}

    /**
     * {@code <dirname>-<sha1(basePath)[:8]>}, the same scheme as codemap/server.py {@code project_id}
     * (os.path.normcase on Windows: lower case, backslashes). Null for a project without a base dir.
     */
    public static String projectId(Project project) {
        String base = project == null ? null : project.getBasePath();
        return base == null ? null : projectId(base, SystemInfo.isWindows);
    }

    static String projectId(String basePath, boolean windows) {
        String p = basePath;
        while (p.length() > 1 && (p.endsWith("/") || p.endsWith("\\"))) p = p.substring(0, p.length() - 1);
        int cut = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        String name = cut >= 0 ? p.substring(cut + 1) : p;
        String slug = name.replaceAll("[^A-Za-z0-9_.-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (slug.isEmpty()) slug = "project";
        String key = windows ? p.replace('/', '\\').toLowerCase(Locale.ROOT) : p;
        return slug + "-" + sha1(key).substring(0, 8);
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Open (not disposed) project with this id, or null. */
    public static Project findProject(String id) {
        if (id == null) return null;
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            if (!p.isDisposed() && id.equals(projectId(p))) return p;
        }
        return null;
    }

    public static int port() {
        return BuiltInServerManager.getInstance().getPort();
    }

    /** {@code http://127.0.0.1:<port>} (no trailing slash). */
    public static String origin() {
        return "http://127.0.0.1:" + port();
    }

    /** URL of the project list page. */
    public static String indexUrl() {
        return origin() + PREFIX;
    }

    /** URL of the project's map, optionally focused on a node id. */
    public static String projectUrl(Project project, String focusNodeId) {
        String id = projectId(project);
        if (id == null) return indexUrl();
        String url = origin() + PREFIX + id + "/";
        if (focusNodeId == null || focusNodeId.isEmpty()) return url;
        return url + "#focus=" + encodeFragment(focusNodeId);
    }

    /** True if {@code url} is a Code Map page served by this IDE's built-in server (loopback host, our port). */
    public static boolean isOwnPage(String url) {
        if (url == null) return false;
        try {
            URI u = new URI(url);
            if (!"http".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) return false;
            String host = u.getHost().toLowerCase(Locale.ROOT);
            boolean loop = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1");
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            return loop && u.getPort() == port() && path.startsWith(PREFIX);
        } catch (Exception e) {
            return false;
        }
    }

    /** Percent-encode characters that are unsafe in a URL fragment; keeps ':' '.' '_' '-' '/' '~' readable. */
    static String encodeFragment(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == ':' || c == '/' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                  .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }
}

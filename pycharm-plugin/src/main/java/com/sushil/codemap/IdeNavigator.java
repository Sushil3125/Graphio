package com.sushil.codemap;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/** Opens a file at a line in the right project window. */
public final class IdeNavigator {
    private static final Logger LOG = Logger.getInstance(IdeNavigator.class);

    private IdeNavigator() {}

    /**
     * Resolves the file off the EDT (disk/VFS refresh), then navigates on the EDT. Never blocks the caller. The
     * future completes with null on success or an error message.
     * @param path absolute path, or a path relative to an open project's base dir (if {@code hint} is null)
     * @param line 1-based line
     */
    public static CompletableFuture<String> open(String path, int line, Project hint) {
        CompletableFuture<String> result = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            VirtualFile vf;
            try {
                String abs = resolvePath(path, hint);
                if (abs == null) {
                    result.complete("file not found: " + path);
                    return;
                }
                String sys = abs.replace('\\', '/');
                vf = LocalFileSystem.getInstance().findFileByPath(sys);
                if (vf == null) vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(sys);  // OK off the EDT
                if (vf == null) {
                    result.complete("file not found in VFS: " + abs);
                    return;
                }
            } catch (Throwable t) {
                LOG.warn("codemap: could not resolve " + path, t);
                result.complete(String.valueOf(t.getMessage()));
                return;
            }
            VirtualFile file = vf;
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    result.complete(openOnEdt(file, line, hint));
                } catch (Throwable t) {
                    LOG.warn("codemap: navigation failed for " + path + ":" + line, t);
                    result.complete(String.valueOf(t.getMessage()));
                }
            });
        });
        return result;
    }

    private static String openOnEdt(VirtualFile vf, int line, Project hint) {
        if (!vf.isValid()) return "file no longer exists: " + vf.getPath();
        Project project = findProject(vf, hint);
        if (project == null) return "no open project";
        int l = Math.max(0, line - 1);
        new OpenFileDescriptor(project, vf, l, 0).navigate(true);
        try {
            ProjectUtil.focusProjectWindow(project, true);
        } catch (Throwable t) {
            LOG.debug("codemap: focusProjectWindow failed", t);
        }
        return null;
    }

    static String resolvePath(String path, Project hint) {
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (f.isAbsolute()) return f.isFile() ? f.getAbsolutePath() : null;
        if (hint != null && hint.getBasePath() != null) {
            File c = new File(hint.getBasePath(), path);
            if (c.isFile()) return c.getAbsolutePath();
        }
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            if (p.isDisposed() || p.getBasePath() == null) continue;
            File c = new File(p.getBasePath(), path);
            if (c.isFile()) return c.getAbsolutePath();
        }
        return null;
    }

    static Project findProject(VirtualFile vf, Project hint) {
        Project[] open = ProjectManager.getInstance().getOpenProjects();
        if (hint != null && !hint.isDisposed() && contains(hint, vf)) return hint;
        for (Project p : open) {
            if (!p.isDisposed() && contains(p, vf)) return p;
        }
        String fp = vf.getPath();
        for (Project p : open) {
            String base = p.getBasePath();
            if (!p.isDisposed() && base != null && (fp.equals(base) || fp.startsWith(base + "/"))) return p;
        }
        if (hint != null && !hint.isDisposed()) return hint;
        for (Project p : open) if (!p.isDisposed()) return p;
        return null;
    }

    private static boolean contains(Project p, VirtualFile vf) {
        try {
            return ProjectFileIndex.getInstance(p).isInContent(vf);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean hasOpenProject() {
        for (Project p : ProjectManager.getInstance().getOpenProjects()) if (!p.isDisposed()) return true;
        return false;
    }
}

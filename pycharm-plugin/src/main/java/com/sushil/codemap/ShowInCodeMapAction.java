package com.sushil.codemap;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;

/** "Show in Code Map": focus the graph on the Python function/class at the caret (or the file's module). */
public final class ShowInCodeMapAction extends AnAction implements DumbAware {

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(AnActionEvent e) {
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean ok = e.getProject() != null && vf != null && !vf.isDirectory() && isPython(vf);
        e.getPresentation().setEnabledAndVisible(ok);
    }

    private static boolean isPython(VirtualFile vf) {
        String n = vf.getName();
        return n.endsWith(".py") || n.endsWith(".pyi");
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || vf == null) return;
        Editor editor = e.getData(CommonDataKeys.EDITOR);

        String rel = relativePath(project, vf);
        String module = PythonLocator.moduleName(rel != null ? rel : vf.getName());
        String nodeId = null;
        if (module != null) {
            String text = null;
            int line = 0;
            if (editor != null) {
                Document doc = editor.getDocument();
                text = doc.getText();
                line = doc.getLineNumber(editor.getCaretModel().getOffset());
            }
            // No editor (project view): text == null -> focus the module node.
            nodeId = PythonLocator.nodeId(module, text, line);
        }
        CodeMapTabs.open(project, CodeMapUrls.projectUrl(project, nodeId));
    }

    /** Path of {@code vf} relative to the project dir (the root the map's module names are relative to). */
    static String relativePath(Project project, VirtualFile vf) {
        String base = project.getBasePath();
        if (base != null && vf.getPath().startsWith(base + "/")) return vf.getPath().substring(base.length() + 1);
        VirtualFile root = null;
        try {
            root = ProjectFileIndex.getInstance(project).getContentRootForFile(vf);
        } catch (Throwable ignored) {}
        if (root != null) return VfsUtilCore.getRelativePath(vf, root, '/');
        return null;
    }
}

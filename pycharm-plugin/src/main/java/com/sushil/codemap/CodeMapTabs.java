package com.sushil.codemap;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;

/** Opens (or re-uses) the "Code Map" editor tab of a project. */
public final class CodeMapTabs {
    private static final Key<CodeMapVirtualFile> FILE_KEY = Key.create("com.sushil.codemap.file");

    private CodeMapTabs() {}

    /**
     * Open the Code Map tab and navigate it to {@code url} (absolute, e.g. {@link CodeMapUrls#projectUrl}); null =
     * keep the current page (or the project map for a new tab). Without JCEF the URL opens in the external browser.
     */
    public static void open(Project project, String url) {
        if (!CodeMapPanel.isJcefSupported()) {
            BrowserUtil.browse(url != null ? url : CodeMapUrls.projectUrl(project, null));
            return;
        }
        FileEditorManager fem = FileEditorManager.getInstance(project);
        CodeMapVirtualFile file = project.getUserData(FILE_KEY);
        boolean alreadyOpen = file != null && fem.isFileOpen(file);
        if (file == null) {
            file = new CodeMapVirtualFile(url);
            project.putUserData(FILE_KEY, file);
        } else if (!alreadyOpen) {
            file.initialTarget = url;  // editor will be re-created with this URL
        }
        FileEditor[] editors = fem.openFile(file, true);
        if (alreadyOpen && url != null) {
            for (FileEditor ed : editors) {
                if (ed instanceof CodeMapFileEditor cm) {
                    cm.getPanel().open(url);
                    break;
                }
            }
        }
    }
}

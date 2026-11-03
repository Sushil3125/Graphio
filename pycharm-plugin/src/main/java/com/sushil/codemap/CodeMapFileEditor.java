package com.sushil.codemap;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.JComponent;
import java.beans.PropertyChangeListener;

/** Editor-tab wrapper around {@link CodeMapPanel}. */
public final class CodeMapFileEditor extends UserDataHolderBase implements FileEditor {
    private final CodeMapVirtualFile file;
    private final CodeMapPanel panel;

    CodeMapFileEditor(Project project, CodeMapVirtualFile file) {
        this.file = file;
        // Loads the map straight from the IDE's built-in server; the page itself shows analysis progress.
        this.panel = new CodeMapPanel(project, file.initialTarget);
        Disposer.register(this, panel);
        project.putUserData(CodeMapPanel.KEY, panel);
    }

    CodeMapPanel getPanel() { return panel; }

    @Override public JComponent getComponent() { return panel.getComponent(); }
    @Override public JComponent getPreferredFocusedComponent() { return panel.getComponent(); }
    @Override public String getName() { return "Code Map"; }
    @Override public VirtualFile getFile() { return file; }
    @Override public void setState(FileEditorState state) {}
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return true; }
    @Override public void addPropertyChangeListener(PropertyChangeListener listener) {}
    @Override public void removePropertyChangeListener(PropertyChangeListener listener) {}
    @Override public void dispose() {}
}

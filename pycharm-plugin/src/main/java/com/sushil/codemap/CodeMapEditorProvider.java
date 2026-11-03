package com.sushil.codemap;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

/** Shows {@link CodeMapVirtualFile} as an editor tab containing the Code Map web UI. */
public final class CodeMapEditorProvider implements FileEditorProvider, DumbAware {
    @Override
    public boolean accept(Project project, VirtualFile file) {
        return file instanceof CodeMapVirtualFile;
    }

    @Override
    public FileEditor createEditor(Project project, VirtualFile file) {
        return new CodeMapFileEditor(project, (CodeMapVirtualFile) file);
    }

    @Override public String getEditorTypeId() { return "codemap-editor"; }
    @Override public FileEditorPolicy getPolicy() { return FileEditorPolicy.HIDE_DEFAULT_EDITOR; }
}

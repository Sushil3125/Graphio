package com.sushil.codemap;

import com.intellij.openapi.fileTypes.FileType;

import javax.swing.Icon;

/** File type of the virtual "Code Map" file, so the editor tab gets its own icon. */
public final class CodeMapFileType implements FileType {
    public static final CodeMapFileType INSTANCE = new CodeMapFileType();

    private CodeMapFileType() {}

    @Override public String getName() { return "CodeMap"; }
    @Override public String getDescription() { return "Code Map graph"; }
    @Override public String getDefaultExtension() { return ""; }
    @Override public Icon getIcon() { return CodeMapIcons.CODE_MAP; }
    @Override public boolean isBinary() { return true; }
    @Override public boolean isReadOnly() { return true; }
}

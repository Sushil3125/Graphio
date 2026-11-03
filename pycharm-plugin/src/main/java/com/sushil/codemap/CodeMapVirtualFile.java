package com.sushil.codemap;

import com.intellij.testFramework.LightVirtualFile;

/** In-memory file shown as the "Code Map" editor tab (one per project). */
public final class CodeMapVirtualFile extends LightVirtualFile {
    /** URL to load when the editor is created (null = the project map). */
    volatile String initialTarget;

    CodeMapVirtualFile(String initialTarget) {
        super("Code Map", CodeMapFileType.INSTANCE, "");
        this.initialTarget = initialTarget;
        setWritable(false);
    }
}

package com.sushil.codemap.analysis;

import com.jetbrains.python.psi.PyFile;

/**
 * Analyses one project Python file. Called by the engine inside a non-blocking read action in smart mode;
 * must check {@code ProgressManager.checkCanceled()} regularly and must not keep PSI references in the result.
 */
public interface FileAnalyzer {
    FileResult analyze(PyFile file, String relPath);

    /** Default implementation (owned by the analyzer port). */
    static FileAnalyzer create() {
        return new PsiFileAnalyzer();
    }
}

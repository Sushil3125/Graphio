package com.sushil.codemap;

import com.intellij.ide.util.PropertiesComponent;

import java.util.ArrayList;
import java.util.List;

/** Application-level analysis settings stored via PropertiesComponent (keys under com.sushil.codemap.*). */
public final class CodeMapSettings {
    public static final String KEY_EXCLUDES = "com.sushil.codemap.excludes";
    public static final String KEY_INCLUDE_TESTS = "com.sushil.codemap.includeTests";

    private CodeMapSettings() {}

    /** Extra exclude globs (relative POSIX paths, e.g. "migrations/*") for analysis. CONTRACT used by the engine. */
    public static List<String> excludeGlobs() {
        String v = PropertiesComponent.getInstance().getValue(KEY_EXCLUDES, "");
        List<String> out = new ArrayList<>();
        for (String line : v.split("[\\n,]")) if (!line.isBlank()) out.add(line.trim());
        return out;
    }

    /** Raw text of the exclude globs (one per line), as edited in the settings page. */
    public static String excludeGlobsText() {
        return String.join("\n", excludeGlobs());
    }

    public static void setExcludeGlobsText(String text) {
        List<String> out = new ArrayList<>();
        for (String line : (text == null ? "" : text).split("[\\n,]")) if (!line.isBlank()) out.add(line.trim());
        PropertiesComponent.getInstance().setValue(KEY_EXCLUDES, String.join("\n", out), "");
    }

    /** Whether test code (tests/, test/, testing/ dirs, test_*.py, *_test.py, conftest.py) is analysed. Default: true. */
    public static boolean includeTests() {
        return PropertiesComponent.getInstance().getBoolean(KEY_INCLUDE_TESTS, true);
    }

    public static void setIncludeTests(boolean value) {
        PropertiesComponent.getInstance().setValue(KEY_INCLUDE_TESTS, value, true);
    }
}

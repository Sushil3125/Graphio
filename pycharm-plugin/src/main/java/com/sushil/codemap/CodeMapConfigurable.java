package com.sushil.codemap;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.sushil.codemap.analysis.CodeGraphService;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/** Settings | Tools | Code Map: analysis options (exclude globs, test code). Changes rebuild open maps. */
public final class CodeMapConfigurable implements Configurable {
    private JBTextArea excludesArea;
    private JBCheckBox testsBox;

    @Override
    public String getDisplayName() {
        return "Code Map";
    }

    @Override
    public JComponent createComponent() {
        excludesArea = new JBTextArea(8, 50);
        testsBox = new JBCheckBox("Include test code (tests/, test/, testing/, test_*.py, *_test.py, conftest.py)");

        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(2, 0, 4, 0);
        p.add(new JLabel("Exclude from analysis (glob relative to the project root, one per line):"), c);
        c.gridy++;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        p.add(new JBScrollPane(excludesArea), c);
        c.gridy++;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.insets = new Insets(2, 0, 2, 0);
        p.add(new JLabel("<html><small>Examples: <code>migrations/*</code>, <code>**/generated/**</code>,"
                + " <code>scripts/*.py</code>. Excluded folders, virtualenvs and libraries are always skipped.</small></html>"), c);
        c.gridy++;
        c.insets = new Insets(12, 0, 2, 0);
        p.add(testsBox, c);
        c.gridy++;
        c.insets = new Insets(12, 0, 2, 0);
        p.add(new JLabel("<html><small>The call graph is built from PyCharm's own code model; no Python interpreter"
                + " is needed. The map is served by the IDE's built-in server at <code>/codemap/</code>.</small></html>"), c);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(p, BorderLayout.NORTH);
        reset();
        return wrap;
    }

    @Override
    public boolean isModified() {
        if (excludesArea == null) return false;
        return !normalize(excludesArea.getText()).equals(CodeMapSettings.excludeGlobsText())
                || testsBox.isSelected() != CodeMapSettings.includeTests();
    }

    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("[\\n,]")) {
            if (line.isBlank()) continue;
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(line.trim());
        }
        return sb.toString();
    }

    @Override
    public void apply() {
        if (excludesArea == null) return;
        boolean changed = isModified();
        CodeMapSettings.setExcludeGlobsText(excludesArea.getText());
        CodeMapSettings.setIncludeTests(testsBox.isSelected());
        excludesArea.setText(CodeMapSettings.excludeGlobsText());
        if (changed) rebuildOpenMaps();
    }

    /** Rebuild the graphs that exist (never creates a service for a project nobody opened a map for). */
    private static void rebuildOpenMaps() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            for (Project p : ProjectManager.getInstance().getOpenProjects()) {
                if (p.isDisposed()) continue;
                CodeGraphService s = p.getServiceIfCreated(CodeGraphService.class);
                if (s != null) s.requestRebuild();
            }
        });
    }

    @Override
    public void reset() {
        if (excludesArea == null) return;
        excludesArea.setText(CodeMapSettings.excludeGlobsText());
        testsBox.setSelected(CodeMapSettings.includeTests());
    }

    @Override
    public void disposeUIResources() {
        excludesArea = null;
        testsBox = null;
    }
}

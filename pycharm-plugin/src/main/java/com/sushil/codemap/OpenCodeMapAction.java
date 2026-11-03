package com.sushil.codemap;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;

/** "Open Code Map": opens the graph in an editor tab. */
public final class OpenCodeMapAction extends AnAction implements DumbAware {
    @Override
    public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override
    public void update(AnActionEvent e) { e.getPresentation().setEnabled(e.getProject() != null); }

    @Override
    public void actionPerformed(AnActionEvent e) {
        if (e.getProject() != null) CodeMapTabs.open(e.getProject(), null);  // null: keep the page if the tab is open
    }
}

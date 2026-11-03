package com.sushil.codemap.export;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.python.sdk.PythonSdkType;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.roots.ProjectRootManager;
import com.sushil.codemap.Json;
import com.sushil.codemap.analysis.CodeGraphService;
import com.sushil.codemap.analysis.GraphSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Headless command: {@code <ide> codemap-export <project dir> <out.json> [--timeout-sec N] [--sdk <python>]} - opens the project,
 * waits for indexing, builds the graph with {@link CodeGraphService} and writes the SPEC JSON. Used for benchmark
 * comparison. Prints one stats line {@code codemap-export: files=.. nodes=.. edges=.. build_ms=.. total_ms=..}.
 * Exit code 0 on success, 1 on error, 2 on bad usage. Opening a directory writes {@code .idea/} into it, so only
 * run it on copies. {@code --sdk} sets the project/module Python SDK first (library calls then resolve to real
 * qualified names; indexing the interpreter's site-packages takes longer).
 */
public final class ExportStarter implements ApplicationStarter {
    @Override
    public boolean isHeadless() { return true; }

    @Override
    public int getRequiredModality() { return NOT_IN_EDT; }

    @Override
    public void main(List<String> args) {
        int code;
        try {
            code = run(args);
        } catch (Throwable t) {
            System.err.println("codemap-export: failed: " + t);
            t.printStackTrace(System.err);
            code = 1;
        }
        System.out.flush();
        System.err.flush();
        exit(code);
    }

    private static int run(List<String> rawArgs) throws Exception {
        // args[0] is the command name ("codemap-export")
        List<String> args = rawArgs.isEmpty() || !"codemap-export".equals(rawArgs.get(0)) ? rawArgs
                : rawArgs.subList(1, rawArgs.size());
        long timeoutSec = 1800;
        String sdkPath = null;
        java.util.ArrayList<String> pos = new java.util.ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--timeout-sec") && i + 1 < args.size()) timeoutSec = Long.parseLong(args.get(++i));
            else if (a.equals("--sdk") && i + 1 < args.size()) sdkPath = args.get(++i);
            else pos.add(a);
        }
        if (pos.size() != 2) {
            System.err.println("usage: codemap-export <project dir> <out.json> [--timeout-sec N] [--sdk <python>]");
            return 2;
        }
        Path dir = Path.of(pos.get(0)).toAbsolutePath().normalize();
        Path out = Path.of(pos.get(1)).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            System.err.println("codemap-export: not a directory: " + dir);
            return 2;
        }
        long t0 = System.nanoTime();
        TrustedProjects.setProjectTrusted(dir, true);
        Project project = ProjectUtil.openOrImport(dir);
        if (project == null) {
            System.err.println("codemap-export: could not open " + dir);
            return 1;
        }
        try {
            long tOpen = System.nanoTime();
            int roots = ReadAction.compute(() -> ProjectRootManager.getInstance(project).getContentRoots().length);
            if (roots == 0) {
                System.err.println("codemap-export: project has no content roots: " + dir);
                return 1;
            }
            if (sdkPath != null) configureSdk(project, sdkPath);
            waitForStableSmartMode(project, TimeUnit.SECONDS.toMillis(timeoutSec));
            long tIndexed = System.nanoTime();
            CodeGraphService svc = CodeGraphService.getInstance(project);
            GraphSnapshot s = svc.buildNowBlocking(TimeUnit.SECONDS.toMillis(timeoutSec));
            long tBuilt = System.nanoTime();
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, s.json(), StandardCharsets.UTF_8);
            int nodes = Json.topLevelArrayLength(s.json(), "nodes");
            int edges = Json.topLevelArrayLength(s.json(), "edges");
            System.out.println("codemap-export: files=" + svc.fileCount() + " nodes=" + nodes + " edges=" + edges
                    + " build_ms=" + svc.lastBuildMs() + " " + svc.lastBuildBreakdown()
                    + " open_ms=" + ms(t0, tOpen) + " index_wait_ms=" + ms(tOpen, tIndexed)
                    + " engine_ms=" + ms(tIndexed, tBuilt) + " total_ms=" + ms(t0, System.nanoTime())
                    + " out=" + out);
            return 0;
        } finally {
            ApplicationManager.getApplication().invokeAndWait(
                    () -> ProjectManagerEx.getInstanceEx().forceCloseProject(project));
        }
    }

    private static void configureSdk(Project project, String python) {
        Sdk sdk = null;
        for (Sdk s : ProjectJdkTable.getInstance().getAllJdks()) {
            if (python.equals(s.getHomePath())) { sdk = s; break; }
        }
        if (sdk == null) {
            VirtualFile home = LocalFileSystem.getInstance().refreshAndFindFileByPath(python);
            if (home == null) throw new IllegalStateException("no such interpreter: " + python);
            // setupSdk runs the interpreter to read sys.path (off the EDT); registering it needs a write action on the EDT
            Sdk created = SdkConfigurationUtil.setupSdk(ProjectJdkTable.getInstance().getAllJdks(), home,
                    PythonSdkType.getInstance(), null, null);
            if (created == null) throw new IllegalStateException("could not create a Python SDK for " + python);
            ApplicationManager.getApplication().invokeAndWait(() -> SdkConfigurationUtil.addSdk(created));
            sdk = created;
        }
        Sdk chosen = sdk;
        ApplicationManager.getApplication().invokeAndWait(() -> {
            WriteAction.run(() -> ProjectRootManager.getInstance(project).setProjectSdk(chosen));
            for (Module m : ModuleManager.getInstance(project).getModules()) ModuleRootModificationUtil.setModuleSdk(m, chosen);
        });
        System.out.println("codemap-export: sdk=" + chosen.getName() + " (" + chosen.getHomePath() + ")");
    }

    /** Smart mode that lasts (indexing may start a moment after opening, and comes in several phases). */
    private static void waitForStableSmartMode(Project project, long timeoutMs) throws InterruptedException {
        DumbService dumb = DumbService.getInstance(project);
        long deadline = System.currentTimeMillis() + timeoutMs;
        long smartSince = -1;
        while (System.currentTimeMillis() < deadline) {
            if (dumb.isDumb()) {
                smartSince = -1;
                dumb.waitForSmartMode(Math.max(1, Math.min(5_000, deadline - System.currentTimeMillis())));
                continue;
            }
            long now = System.currentTimeMillis();
            if (smartSince < 0) smartSince = now;
            if (now - smartSince >= 2_000) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("indexing did not finish within " + timeoutMs + " ms");
    }

    private static long ms(long from, long to) { return (to - from) / 1_000_000; }

    private static void exit(int code) {
        // Fallback in case the orderly shutdown hangs.
        Thread killer = new Thread(() -> {
            try { Thread.sleep(60_000); } catch (InterruptedException ignored) { }
            Runtime.getRuntime().halt(code);
        }, "codemap-export-exit");
        killer.setDaemon(true);
        killer.start();
        try {
            ApplicationManagerEx.getApplicationEx().exit(true, true, code);
        } catch (Throwable t) {
            System.exit(code);
        }
    }
}

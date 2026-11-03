package com.sushil.codemap.analysis;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.ControlFlowException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiTreeChangeAdapter;
import com.intellij.psi.PsiTreeChangeEvent;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.jetbrains.python.psi.PyFile;
import com.sushil.codemap.CodeMapSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.CancellablePromise;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-project graph engine: initial build, incremental updates on PSI changes, publishes snapshots.
 * CONTRACT used by the web layer (do not change signatures without updating both sides):
 *   getInstance(project), ensureStarted(), snapshot(), status(), requestRebuild(), addListener(listener, parent).
 * Listeners are called on a background thread after each new snapshot is published.
 *
 * Threading: all orchestration runs on one sequential pooled "engine" executor (full builds and incremental updates
 * never overlap; the per-file cache {@link #entries} is touched only there). PSI is read only inside
 * {@code ReadAction.nonBlocking(...).inSmartMode(project).expireWith(this)} batches of {@value #BATCH} files on a
 * bounded worker pool, so a write action (typing) cancels and restarts only the current batch, dumb mode just
 * delays it, and nothing runs on the EDT. PSI/VFS listeners only record dirty files and (re)arm a
 * {@value #DEBOUNCE_MS} ms debounce.
 */
@Service(Service.Level.PROJECT)
public final class CodeGraphService implements Disposable {
    public interface Listener {
        void graphChanged(GraphSnapshot snapshot);
    }

    private static final Logger LOG = Logger.getInstance(CodeGraphService.class);
    static final int BATCH = 20;
    static final long DEBOUNCE_MS = 500;
    private static final long POLL_MS = 100;
    static volatile boolean DEBUG = Boolean.getBoolean("codemap.debug");
    /** status tracking mode for {@link #await}: none (incremental work keeps "ready"). */
    private static final int NO_STATUS = -1;

    /** One analysed file in the cache. VirtualFile (not PSI) is safe to hold. */
    private record Entry(VirtualFile file, String absPath, FileResult result) {}

    /** Result of analysing one file inside a read action; {@code result == null}: not (or no longer) analysable. */
    private record Analyzed(VirtualFile file, @Nullable String relPath, @Nullable FileResult result) {}

    private final Project project;
    private final String root;
    private final String projectName;
    private final FileAnalyzer analyzer;
    private final GraphAssembler assembler = GraphAssembler.create();
    private final ExecutorService engine = AppExecutorUtil.createBoundedApplicationPoolExecutor("CodeMap engine", 1);
    /** Parallel read actions for analysis: half the cores, 1..8 (override: -Dcodemap.workers=N). */
    static final int WORKERS = Math.max(1, Integer.getInteger("codemap.workers",
            Math.min(8, Runtime.getRuntime().availableProcessors() / 2)));
    private final ExecutorService workers = AppExecutorUtil.createBoundedApplicationPoolExecutor("CodeMap analysis", WORKERS);
    private final AtomicLong analyzerNanos = new AtomicLong();

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean listening = new AtomicBoolean();
    private volatile boolean disposed;
    private volatile GraphSnapshot snapshot;
    private volatile GraphStatus status = new GraphStatus("idle", 0, 0, "");
    private final AtomicLong version = new AtomicLong();
    /** Bumped by every rebuild request and on dispose: running work of an older generation aborts. */
    private final AtomicInteger generation = new AtomicInteger();
    private final Set<CancellablePromise<?>> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<GraphSnapshot>> waiting = ConcurrentHashMap.newKeySet();

    // ---- engine-thread state ----
    private final TreeMap<String, Entry> entries = new TreeMap<>();          // relPath -> entry (sorted: deterministic)
    private final Map<String, String> relByPath = new HashMap<>();           // absolute path -> relPath
    private final Map<String, Set<String>> dependants = new HashMap<>();     // module -> relPaths depending on it
    private @Nullable EngineFiles files;
    private volatile boolean built;
    private volatile int fileCount;
    private volatile List<String> filesView = List.of();
    private volatile long lastBuildMs;
    private volatile String lastBuildBreakdown = "";
    private volatile long lastUpdateMs;

    // ---- pending changes (guarded by pendingLock) ----
    private final Object pendingLock = new Object();
    private final LinkedHashSet<VirtualFile> dirtyFiles = new LinkedHashSet<>();
    private final LinkedHashSet<String> removedPaths = new LinkedHashSet<>();
    private boolean rescanRequested;
    private @Nullable ScheduledFuture<?> debounce;
    private long debounceSeq;
    private final AtomicInteger engineTasks = new AtomicInteger();

    public CodeGraphService(Project project) {
        this(project, FileAnalyzer.create());
    }

    /** For tests: a service with a custom analyzer (register it with a test disposable). */
    CodeGraphService(Project project, FileAnalyzer analyzer) {
        this.project = project;
        this.analyzer = analyzer;
        this.root = project.getBasePath() == null ? "" : project.getBasePath();
        Path fn = root.isEmpty() ? null : Path.of(root).getFileName();
        this.projectName = fn == null ? project.getName() : fn.toString();
        this.snapshot = GraphSnapshot.empty(root, projectName);
    }

    public static CodeGraphService getInstance(Project project) {
        return project.getService(CodeGraphService.class);
    }

    // =============================================================================================== public API

    /** Installs change listeners and starts the initial build (once). Cheap, any thread. */
    public void ensureStarted() {
        if (started.compareAndSet(false, true)) rebuildAsync();
    }

    public GraphSnapshot snapshot() { return snapshot; }

    public GraphStatus status() { return status; }

    /** Discards the cache and rebuilds everything (e.g. after the exclude settings changed). */
    public void requestRebuild() {
        rebuildAsync();
    }

    public void addListener(Listener listener, Disposable parent) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
    }

    /** Full rebuild; completes with the published snapshot (exceptionally when superseded, disposed or failed). */
    public CompletableFuture<GraphSnapshot> rebuildAsync() {
        CompletableFuture<GraphSnapshot> f = new CompletableFuture<>();
        if (disposed) { f.cancel(false); return f; }
        installListeners();
        started.set(true);
        int gen = generation.incrementAndGet();
        for (CancellablePromise<?> p : inFlight) p.cancel();
        submitEngine(f, () -> fullBuild(gen));
        return f;
    }

    /**
     * Blocking full build for the headless exporter. Must not be called on the EDT. Also installs the change
     * listeners, so the service keeps tracking changes afterwards.
     */
    public GraphSnapshot buildNowBlocking(long timeoutMs) throws Exception {
        return rebuildAsync().get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Processes pending changes now instead of after the debounce; completes with the then-current snapshot. */
    public CompletableFuture<GraphSnapshot> flushPendingAsync() {
        synchronized (pendingLock) {
            if (debounce != null) { debounce.cancel(false); debounce = null; }
        }
        CompletableFuture<GraphSnapshot> f = new CompletableFuture<>();
        submitEngine(f, () -> { incremental(generation.get()); return snapshot; });
        return f;
    }

    /** True once a full build has been published. */
    public boolean isBuilt() { return built; }

    /** relPaths of the files in the current graph (sorted). */
    public List<String> files() { return filesView; }

    /** Number of files in the current graph. */
    public int fileCount() { return fileCount; }

    /** Wall time of the last full build (file collection + analysis + assembly), ms. */
    public long lastBuildMs() { return lastBuildMs; }

    /** Phase timings of the last full build, "key=value ..." (for the exporter / logs). */
    public String lastBuildBreakdown() { return lastBuildBreakdown; }

    /** Wall time of the last incremental update, ms. */
    public long lastUpdateMs() { return lastUpdateMs; }

    /** True while changes are waiting for the debounce or being processed. */
    public boolean hasPendingChanges() {
        synchronized (pendingLock) {
            return debounce != null || engineTasks.get() > 0 || !dirtyFiles.isEmpty() || !removedPaths.isEmpty()
                    || rescanRequested;
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        generation.incrementAndGet();
        for (CancellablePromise<?> p : inFlight) p.cancel();
        synchronized (pendingLock) {
            if (debounce != null) debounce.cancel(false);
            debounce = null;
            dirtyFiles.clear();
            removedPaths.clear();
        }
        listeners.clear();
        for (CompletableFuture<GraphSnapshot> f : waiting) f.cancel(false);   // queued tasks may never run now
        waiting.clear();
        try { engine.shutdownNow(); } catch (RuntimeException ignored) { }
        try { workers.shutdownNow(); } catch (RuntimeException ignored) { }
    }

    // =============================================================================================== engine tasks

    private interface EngineTask { GraphSnapshot run() throws Exception; }

    private void submitEngine(CompletableFuture<GraphSnapshot> f, EngineTask task) {
        engineTasks.incrementAndGet();
        waiting.add(f);
        f.whenComplete((r, t) -> waiting.remove(f));
        try {
            engine.execute(() -> {
                try {
                    if (disposed) { f.cancel(false); return; }
                    f.complete(task.run());
                } catch (CancellationException e) {
                    f.completeExceptionally(e);
                } catch (Throwable t) {
                    if (t instanceof ProcessCanceledException || disposed) {
                        f.completeExceptionally(new CancellationException("cancelled"));
                        return;
                    }
                    LOG.warn("Code Map engine task failed", t);
                    status = new GraphStatus("error", 0, 0, String.valueOf(t));
                    f.completeExceptionally(t);
                } finally {
                    engineTasks.decrementAndGet();
                }
            });
        } catch (RuntimeException rejected) {   // executor shut down (disposed)
            engineTasks.decrementAndGet();
            f.cancel(false);
        }
    }

    private GraphSnapshot fullBuild(int gen) throws Exception {
        long t0 = System.nanoTime();
        EngineFiles cfg = new EngineFiles(project, CodeMapSettings.excludeGlobs());
        synchronized (pendingLock) {   // everything pending so far is covered by this build (it reads current PSI)
            dirtyFiles.clear();
            removedPaths.clear();
            rescanRequested = false;
        }
        status = DumbService.isDumb(project)
                ? new GraphStatus("waiting_for_index", 0, 0, "Waiting for indexing…")
                : new GraphStatus("building", 0, 0, "Collecting files…");
        TreeMap<String, VirtualFile> all = await(ReadAction.nonBlocking(cfg::collect)
                .inSmartMode(project).expireWith(this).submit(workers), gen, 0);

        long tCollected = System.nanoTime();
        List<VirtualFile> vfs = new ArrayList<>(all.values());
        status = new GraphStatus("building", 0, vfs.size(), "Analysing 0 / " + vfs.size() + " files…");
        analyzerNanos.set(0);
        List<Analyzed> results = analyzeAll(cfg, vfs, gen, true);
        long tAnalysed = System.nanoTime();

        entries.clear();
        relByPath.clear();
        for (Analyzed a : results) if (a.result != null) put(a);
        files = cfg;
        reindex();
        GraphSnapshot s = publish(gen, List.of(), (tAnalysed - t0) / 1_000_000);
        long tEnd = System.nanoTime();
        lastBuildMs = (tEnd - t0) / 1_000_000;
        lastBuildBreakdown = "collect_ms=" + (tCollected - t0) / 1_000_000 + " analyse_ms=" + (tAnalysed - tCollected) / 1_000_000
                + " analyser_cpu_ms=" + analyzerNanos.get() / 1_000_000 + " assemble_ms=" + (tEnd - tAnalysed) / 1_000_000
                + " workers=" + WORKERS;
        built = true;
        LOG.info("Code Map: built " + entries.size() + " files in " + lastBuildMs + " ms (" + lastBuildBreakdown + ", "
                + project.getName() + ")");
        return s;
    }

    private void incremental(int gen) throws Exception {
        EngineFiles cfg = files;
        if (!built || cfg == null) return;   // the queued (or failed) full build covers it
        List<VirtualFile> dirty;
        List<String> removed;
        boolean rescan;
        synchronized (pendingLock) {
            dirty = new ArrayList<>(dirtyFiles);
            removed = new ArrayList<>(removedPaths);
            rescan = rescanRequested;
            dirtyFiles.clear();
            removedPaths.clear();
            rescanRequested = false;
        }
        if (DEBUG) LOG.info("codemap incremental dirty=" + dirty + " removed=" + removed + " rescan=" + rescan);
        if (dirty.isEmpty() && removed.isEmpty() && !rescan) return;
        long t0 = System.nanoTime();
        Set<String> changed = new TreeSet<>();
        Set<String> touchedModules = new HashSet<>();

        for (String p : removed) {
            String norm = p.replace('\\', '/');
            List<String> gone = new ArrayList<>();
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                String abs = e.getValue().absPath;
                if (abs.equals(norm) || abs.startsWith(norm + "/")) gone.add(e.getKey());
            }
            for (String rel : gone) remove(rel, changed, touchedModules);
        }

        LinkedHashSet<VirtualFile> toAnalyse = new LinkedHashSet<>(dirty);
        if (rescan) {
            TreeMap<String, VirtualFile> all = await(ReadAction.nonBlocking(cfg::collect)
                    .inSmartMode(project).expireWith(this).submit(workers), gen, NO_STATUS);
            for (String rel : new ArrayList<>(entries.keySet())) {
                if (!all.containsKey(rel)) remove(rel, changed, touchedModules);
            }
            for (Map.Entry<String, VirtualFile> e : all.entrySet()) {
                if (!entries.containsKey(e.getKey())) toAnalyse.add(e.getValue());
            }
        }

        Set<String> analysedRels = new HashSet<>();
        applyResults(analyzeAll(cfg, new ArrayList<>(toAnalyse), gen, false), changed, touchedModules, analysedRels);

        // Dependants: files whose results refer to a module that changed (one level).
        LinkedHashSet<VirtualFile> deps = new LinkedHashSet<>();
        for (String m : touchedModules) {
            for (String rel : dependants.getOrDefault(m, Set.of())) {
                if (analysedRels.contains(rel)) continue;
                Entry e = entries.get(rel);
                if (e != null) deps.add(e.file);
            }
        }
        if (!deps.isEmpty()) {
            applyResults(analyzeAll(cfg, new ArrayList<>(deps), gen, false), changed, new HashSet<>(), analysedRels);
        }
        if (changed.isEmpty()) return;
        reindex();
        publish(gen, List.copyOf(changed), (System.nanoTime() - t0) / 1_000_000);
        lastUpdateMs = (System.nanoTime() - t0) / 1_000_000;
    }

    private void applyResults(List<Analyzed> results, Set<String> changed, Set<String> touchedModules,
                              Set<String> analysedRels) {
        for (Analyzed a : results) {
            String oldRel = relByPath.get(a.file.getPath());
            if (oldRel != null && (a.result == null || !oldRel.equals(a.relPath))) remove(oldRel, changed, touchedModules);
            if (a.result == null) continue;
            Entry old = entries.get(a.relPath);
            if (old != null && old.result.module != null) touchedModules.add(old.result.module);
            put(a);
            changed.add(a.relPath);
            analysedRels.add(a.relPath);
            if (a.result.module != null) touchedModules.add(a.result.module);
        }
    }

    private void put(Analyzed a) {
        String path = a.file.getPath();
        Entry prev = entries.put(a.relPath, new Entry(a.file, path, a.result));
        if (prev != null && !prev.absPath.equals(path)) relByPath.remove(prev.absPath);
        relByPath.put(path, a.relPath);
    }

    private void remove(String rel, Set<String> changed, Set<String> touchedModules) {
        Entry e = entries.remove(rel);
        if (e == null) return;
        relByPath.remove(e.absPath);
        changed.add(rel);
        if (e.result.module != null) touchedModules.add(e.result.module);
    }

    private void reindex() {
        dependants.clear();
        for (Map.Entry<String, Entry> me : entries.entrySet()) {
            FileResult r = me.getValue().result;
            for (String m : r.dependsOnModules) {
                if (!m.equals(r.module)) dependants.computeIfAbsent(m, k -> new HashSet<>()).add(me.getKey());
            }
        }
        fileCount = entries.size();
        filesView = List.copyOf(entries.keySet());
    }

    private GraphSnapshot publish(int gen, List<String> changed, long buildMs) {
        checkGeneration(gen);
        List<FileResult> rs = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) rs.add(e.result);
        long v = version.incrementAndGet();
        String json = assembler.assembleJson(rs, root, projectName, v, buildMs);
        GraphSnapshot s = new GraphSnapshot(v, json, changed);
        snapshot = s;
        status = new GraphStatus("ready", 0, 0, rs.size() + " files");
        for (Listener l : listeners) {
            try {
                l.graphChanged(s);
            } catch (Throwable t) {
                if (!(t instanceof ControlFlowException)) LOG.warn("Code Map listener failed", t);
            }
        }
        return s;
    }

    // =============================================================================================== analysis

    /** Analyses files in read-action batches on the worker pool and waits for all (honouring the generation). */
    private List<Analyzed> analyzeAll(EngineFiles cfg, List<VirtualFile> vfs, int gen, boolean progress)
            throws Exception {
        if (vfs.isEmpty()) return List.of();
        int total = vfs.size();
        AtomicInteger done = new AtomicInteger();
        List<CancellablePromise<List<Analyzed>>> promises = new ArrayList<>();
        try {
            for (int i = 0; i < total; i += BATCH) {
                List<VirtualFile> batch = List.copyOf(vfs.subList(i, Math.min(total, i + BATCH)));
                CancellablePromise<List<Analyzed>> p = ReadAction.nonBlocking(() -> analyzeBatch(cfg, batch))
                        .inSmartMode(project).expireWith(this).submit(workers);
                inFlight.add(p);
                if (progress) {
                    p.onSuccess(r -> {
                        int d = done.addAndGet(r.size());
                        if (generation.get() == gen) {
                            status = new GraphStatus("building", d, total, "Analysing " + d + " / " + total + " files…");
                        }
                    });
                }
                promises.add(p);
            }
            List<Analyzed> out = new ArrayList<>(total);
            for (CancellablePromise<List<Analyzed>> p : promises) out.addAll(await(p, gen, progress ? total : NO_STATUS));
            return out;
        } finally {
            for (CancellablePromise<List<Analyzed>> p : promises) {
                inFlight.remove(p);
                if (!p.isDone()) p.cancel();
            }
        }
    }

    private List<Analyzed> analyzeBatch(EngineFiles cfg, List<VirtualFile> batch) {
        List<Analyzed> out = new ArrayList<>(batch.size());
        PsiManager psi = PsiManager.getInstance(project);
        for (VirtualFile vf : batch) {
            ProgressManager.checkCanceled();
            String rel = vf.isValid() ? cfg.relPathIfIncluded(vf) : null;
            PsiFile pf = rel == null ? null : psi.findFile(vf);
            if (!(pf instanceof PyFile py)) { out.add(new Analyzed(vf, null, null)); continue; }
            FileResult r;
            long ta = System.nanoTime();
            try {
                r = analyzer.analyze(py, rel);
                analyzerNanos.addAndGet(System.nanoTime() - ta);
            } catch (Throwable t) {
                if (t instanceof ControlFlowException) throw t;   // ProcessCanceledException & co: action restarts
                LOG.warn("Code Map: analysis failed for " + rel, t);
                r = errorResult(rel, t);
            }
            if (r == null) r = errorResult(rel, new IllegalStateException("analyzer returned null"));
            if (r.relPath == null) r.relPath = rel;
            out.add(new Analyzed(vf, rel, r));
        }
        return out;
    }

    private static FileResult errorResult(String rel, Throwable t) {
        FileResult r = new FileResult();
        r.relPath = rel;
        r.module = rel.replaceAll("\\.pyi?$", "").replace('/', '.').replaceAll("\\.__init__$", "");
        r.errors.add(rel + ": analysis failed: " + t);
        return r;
    }

    /**
     * Waits for a promise; aborts (cancelling it) when superseded or disposed. Unless {@code total == NO_STATUS},
     * keeps the status in sync with dumb mode (waiting_for_index while the IDE indexes).
     */
    private <T> T await(CancellablePromise<T> p, int gen, int total) throws Exception {
        inFlight.add(p);
        try {
            while (true) {
                try {
                    T r = p.get(POLL_MS, TimeUnit.MILLISECONDS);
                    if (r == null && p.isCancelled()) throw new CancellationException("cancelled");
                    return r;
                } catch (TimeoutException e) {
                    if (disposed || generation.get() != gen) {
                        p.cancel();
                        throw new CancellationException("superseded");
                    }
                    if (total == NO_STATUS) continue;
                    boolean dumb = DumbService.isDumb(project);
                    GraphStatus st = status;
                    if (dumb && !"waiting_for_index".equals(st.state())) {
                        status = new GraphStatus("waiting_for_index", st.done(), st.total(), "Waiting for indexing…");
                    } else if (!dumb && "waiting_for_index".equals(st.state())) {
                        status = new GraphStatus("building", st.done(), st.total(), st.total() == 0
                                ? "Collecting files…" : "Analysing " + st.done() + " / " + st.total() + " files…");
                    }
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof Exception ex) throw ex;
                    throw e;
                }
            }
        } finally {
            inFlight.remove(p);
        }
    }

    private void checkGeneration(int gen) {
        if (disposed || generation.get() != gen) throw new CancellationException("superseded");
    }

    // =============================================================================================== listeners

    private void installListeners() {
        if (disposed || !listening.compareAndSet(false, true)) return;
        PsiManager.getInstance(project).addPsiTreeChangeListener(new PsiTreeChangeAdapter() {
            @Override public void childAdded(@NotNull PsiTreeChangeEvent event) { onPsi(event); }
            @Override public void childRemoved(@NotNull PsiTreeChangeEvent event) { onPsi(event); }
            @Override public void childReplaced(@NotNull PsiTreeChangeEvent event) { onPsi(event); }
            @Override public void childMoved(@NotNull PsiTreeChangeEvent event) { onPsi(event); }
            @Override public void childrenChanged(@NotNull PsiTreeChangeEvent event) { onPsi(event); }
            @Override public void propertyChanged(@NotNull PsiTreeChangeEvent event) { onPsi(event); }
        }, this);
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends @NotNull VFileEvent> events) { onVfs(events); }
        });
    }

    /** Runs inside write actions on the EDT: only record and debounce. */
    private void onPsi(PsiTreeChangeEvent event) {
        PsiFile f = event.getFile();
        if (f == null || !f.isPhysical()) return;   // file-level add/delete/move arrive as VFS events
        VirtualFile vf = f.getViewProvider().getVirtualFile();
        if (!"py".equals(vf.getExtension())) return;
        if (disposed) return;
        synchronized (pendingLock) { dirtyFiles.add(vf); }
        schedule();
    }

    private void onVfs(List<? extends VFileEvent> events) {
        if (disposed || events.isEmpty()) return;
        List<String> roots = projectRoots();
        if (DEBUG) LOG.info("codemap vfs roots=" + roots + " events=" + events);
        boolean any = false;
        synchronized (pendingLock) {
            for (VFileEvent ev : events) {
                if (ev instanceof VFileDeleteEvent) {
                    any |= removedPath(ev.getPath(), roots);
                } else if (ev instanceof VFileMoveEvent mv) {
                    any |= removedPath(mv.getOldPath(), roots);
                    any |= added(mv.getFile(), roots);
                } else if (ev instanceof VFilePropertyChangeEvent pc) {
                    if (VirtualFile.PROP_NAME.equals(pc.getPropertyName())) {
                        any |= removedPath(pc.getOldPath(), roots);
                        any |= added(pc.getFile(), roots);
                    }
                } else if (ev instanceof VFileCreateEvent || ev instanceof VFileCopyEvent) {
                    any |= added(ev.getFile(), roots);
                } else if (ev instanceof VFileContentChangeEvent cc) {
                    if (!cc.isFromSave()) any |= added(cc.getFile(), roots);   // external change (git, other tools)
                }
            }
        }
        if (any) schedule();
    }

    /** Caller holds pendingLock. */
    private boolean removedPath(@Nullable String path, List<String> roots) {
        if (!under(path, roots)) return false;
        removedPaths.add(path);
        return true;
    }

    /** Caller holds pendingLock. */
    private boolean added(@Nullable VirtualFile f, List<String> roots) {
        if (f == null || !f.isValid() || !under(f.getPath(), roots)) return false;
        if (f.isDirectory()) { rescanRequested = true; return true; }
        if (!"py".equals(f.getExtension())) return false;
        dirtyFiles.add(f);
        return true;
    }

    private List<String> projectRoots() {
        List<String> out = new ArrayList<>();
        if (!root.isEmpty()) out.add(root);
        try {
            for (VirtualFile r : ProjectRootManager.getInstance(project).getContentRoots()) out.add(r.getPath());
        } catch (RuntimeException ignored) { }
        return out;
    }

    private static boolean under(@Nullable String path, List<String> roots) {
        if (path == null) return false;
        for (String r : roots) if (path.equals(r) || path.startsWith(r + "/")) return true;
        return false;
    }

    private void schedule() {
        if (disposed) return;
        synchronized (pendingLock) {
            if (debounce != null) debounce.cancel(false);
            long seq = ++debounceSeq;
            debounce = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                synchronized (pendingLock) {
                    if (debounceSeq == seq) debounce = null;
                }
                submitEngine(new CompletableFuture<>(), () -> { incremental(generation.get()); return snapshot; });
            }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }
}

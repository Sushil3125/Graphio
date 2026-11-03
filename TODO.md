# Remaining work

State: v0.5 (Java analysis on PyCharm's Python model) is built and compiles; the benchmark gates pass on all
four corpora (gis_api, FastAPI, Celery, SQLAlchemy). The installed plugin in PyCharm is still 0.4.0
(bundled Python server). See PLAN-psi.md and the v0.5 section of SPEC.md.

## 1. Finish the v0.5 review/fix cycle
- [ ] **Performance/threading review** (not finished): check for UI-thread blocking, long read actions,
      missing cancellation checks, memory growth across incremental updates, SSE channel leaks,
      dispose order, races between incremental update and full rebuild, behaviour while indexing,
      and big-repo timings (SQLAlchemy copy plus a synthetic ~3,000-file repo).
- [ ] Fix the findings of the differences review (done; 7 findings):
  - [ ] **high** `PyResolver`: `super().m()` can resolve to the wrong method (PyCharm's result can
        skip the next class in the MRO). Resolve bare `super()` with the project's own class model
        first; use a real C3 linearization.
  - [ ] **medium** `PyResolver`: one target per call site; Union / isinstance tuple / multiple local
        bindings keep only the first. Emit edges to every project-class member (reuse `callDef`).
  - [ ] **medium** Celery `Signature.clone`: `@ABC.register` / `ABC.register(Cls)` virtual subclassing
        not modelled, so calls dead-end at the abstract stub. Add implements edges for registered classes.
  - [ ] **medium** `PsiFileAnalyzer`: 91 extra edges to `typing.Protocol` stub methods are wrong.
        Treat calls to stub methods of Protocol classes as external.
  - [ ] **medium** `DIFFERENCES.md` entry `Mapper._configure_property -> ColumnCollection.add` hides a
        wrong edge: prefer the `self.attr = <expr>` value over a bare class annotation.
  - [ ] **low** `Type[Base]` / `_TypeEngineArgument` calls draw wrong `instantiates TypeEngine` edges;
        record them as external or unknown.
  - [ ] **low** About 13 `DIFFERENCES.md` reasons don't match the source; rewrite them with the real causes.
- [ ] Final verification: `./gradlew clean buildPlugin test verifyPlugin`, Python tests,
      export + `tools/graphdiff.py` on all four corpora.
- [ ] Install 0.5.0 (`pycharm-plugin/install.sh`, exactly one copy), restart PyCharm, try it on
      gis_api and one other project (tab, Ctrl+click, live update, progress while indexing).

## 2. Rename the project to Graphio
- [ ] Plugin name "Graphio", id `com.sushil.graphio`, Java package `com.sushil.graphio`, class names
      `CodeMap*` -> `Graphio*`, actions "Open Graphio" / "Show in Graphio", tab title, settings keys,
      icons, resources (`codemap-web` -> `graphio-web`), URL prefixes (`/codemap/` and `/api/codemap/*`
      -> `/graphio/...`, in both Java and Python), export command `graphio-export`, Gradle properties.
- [ ] Python package `codemap` -> `graphio` (`python -m graphio`), env vars `CODEMAP_*` -> `GRAPHIO_*`,
      scripts and the systemd unit.
- [ ] `install.sh` must still remove the old ids `com.sushil.codemap` and `dev.codemap.pycharm`.
- [ ] Docs (README, SPEC, PLAN, PUBLISHING, bench), web UI title and header.
- [ ] Rename the folder `~/PycharmProjects/codemap` -> `~/PycharmProjects/graphio` (close it in
      PyCharm first; it has a `.idea`), then update or remove the old systemd service
      `codemap.service` (port 8765), which points at the old path. The plugin no longer needs it.
- [ ] Rebuild and re-run all checks after the rename.

## 3. Publish to the JetBrains Marketplace
- [ ] Create a signing key and certificate (stored outside the repo, e.g. `~/.config/graphio/`, mode 600);
      reuse the same key for every future release. `./gradlew signPlugin`.
- [ ] **First upload must be manual** through the Marketplace website
      (https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html: "The first plugin publication
      must always be uploaded manually."). Fill in name, category, license, and links to the GitHub repo.
- [ ] Wait for JetBrains' manual review (usually a couple of business days).
- [ ] Later updates: `PUBLISH_TOKEN=... ./gradlew publishPlugin` (token stored outside the repo).
      Rotate the current Marketplace token, since it was shared in a chat.

## 4. Before a public release
- [ ] Test on Windows and macOS (only Linux/WSL has been tested).
- [ ] Django URL routing (`urls.py`) as entrypoints.
- [ ] Choose and add a LICENSE file (the vendored elkjs is EPL-2.0; its license ships in `codemap/web/vendor/`).

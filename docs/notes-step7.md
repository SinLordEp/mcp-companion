# Step 7: `mc_diagnostics`, `mc_build` and Mermaid for `mc_modules graph`, platform notes

Date: 2026-11-17. IntelliJ IDEA 2026.3 (IU-263.7307.200). Goal: replace the two tools of idea-mcp-ext that had no
counterpart (`build_module_with_dependencies`, `get_aggregated_diagnostics`): see the problems of one module, a
directory or some files without building, and build one module with what it needs instead of the whole project.

## Step 0: what the platform and the bundled server do

All findings from the 263 jars (Fernflower of the IDE's own `java-decompiler`, `javap` for the call sites), pinned
where possible by `BuildToolsContractTest`.

### Official `build_project` (`AnalysisToolset`, `intellij.mcpserver.toolsets.jar`)

- Parameters `rebuild`, `filesToRebuild`, `timeout` (ms, default 60,000). **No module parameter.** Without files it
  builds `ProjectTaskManager.createAllModulesBuildTask(!rebuild, project)`; with files it calls
  `ProjectTaskManagerImpl.createModulesFilesTask(...)` (class of the `impl` package), i.e. compiles those files.
- Problems: `BuildViewManager.addListener(listener, disposable)` for the duration of the call; it keeps
  `FileMessageEvent`s of kind ERROR/WARNING with `FilePosition` (file, start line, start column), `BuildIssueEvent`s
  and `MessageEvent`s without position, and the failures of the `FinishBuildEvent`. Because the Build tool window
  receives the messages of JPS, Gradle and Maven builds alike, this gives positions for every build system. When no
  `StartBuildEvent` came it adds "The project has limited build diagnostics functionality"; when the task result has
  errors but no ERROR message was seen it adds "Build reported errors, but detailed error messages were not captured".
- **Status of that listener API (re-checked):** `AbstractViewManager.addListener(BuildProgressListener, Disposable)`
  is `@ApiStatus.Experimental`; the interface it implements, `BuildProgressObservable`, is `@ApiStatus.Experimental`
  as a whole; `BuildProgressListenerRegistrar` (the extension point that hands out the observable) is
  `@ApiStatus.Internal`. No other public way reaches the Build tool window's events. The step-3 finding stands:
  positions of delegated builds are not reachable through the Build view with allowed API. The event classes
  themselves (`FileMessageEvent`, `FilePosition`, `MessageEvent`) are not annotated, but nothing allowed delivers them.
- Public and allowed: `ProjectTaskManager` (`@NonExtendable` only) with `createModulesBuildTask(Module[], incremental,
  includeDependentModules, includeRuntimeDependencies, includeTests)` and `run(ProjectTaskContext, task)`;
  `ProjectTaskManager.Result.isAborted/hasErrors` (`anyTaskMatches` is experimental, not used);
  `CompilerTopics.COMPILATION_STATUS` with `CompileContext.getMessages(category)` (JPS messages with
  `OpenFileDescriptor` positions, Java plugin); `ExecutionManager.EXECUTION_TOPIC` and `ProcessListener` (platform).
- Consent: `build_project` asks nothing (`docs/notes-consent.md`); `mc_build` asks nothing either.

How `mc_build` gets positions without the Build view (decided):

| Build system | Messages | Positions |
|---|---|---|
| JPS (the IDE builds; also Maven projects unless "Delegate IDE build/run actions to Maven" is on) | `CompilerTopics.COMPILATION_STATUS` | file, line, column of every error and warning |
| Gradle (delegated) | the build runs as an execution; its console output is read through `ExecutionManager.EXECUTION_TOPIC` and a `ProcessListener`, and javac/kotlinc lines are parsed (`CompilerOutputParser`) | javac: file and line, the column from javac's caret line; kotlinc: file, line, column |
| Maven (delegated) | the same console route | the Maven compiler plugin's `[ERROR] /path:[line,column]` lines |
| anything else (a build script error, a missing dependency) | no compiler line | none: `messages=build_output` (or `none`) with an explicit note that points to `build_project` |

The console route reads every execution that starts while the build runs (there is no public link from a
`ProjectTaskContext` to the execution a delegated runner starts); only lines in a compiler's format count. A process the
user starts during the build and that prints such lines would add them. The same shared gateway now serves
`mc_run hotswap`, so hot swap in delegated builds reports compile errors with positions too.

### Official per-file problem tools

| Tool | What it runs | Limits |
|---|---|---|
| `get_file_problems(filePath, errorsOnly, timeout)` | the main highlighting passes on one file | one file |
| `lint_files(files, min_severity, timeout)` | `DaemonCodeAnalyzerImpl.runMainPasses` per file under `HighlightingSessionImpl`, with `InspectionProfileWrapper` (project profile), mutex, yields to write actions; severities warning (incl. weak warnings), strong_warning, error | explicit files only, no module or directory scope, no totals per module |
| `get_ide_diagnostics` | the IDE process (threads, memory); unrelated to code | disabled unless `-Didea.diagnostics.mcp.enabled=true` |
| `*_inspection_kts*` (Qodana) | inspection scripts the agent writes | not the editor's analysis |

`lint_files` reaches into `com.intellij.codeInsight.daemon.impl` (`DaemonCodeAnalyzerImpl`, `HighlightingSessionImpl`,
`HighlightInfo`), `codeInspection.ex` and `ApplicationEx`; none of that is allowed here.

Public API for many files:

- `MainPassesRunner` (`com.intellij.codeInsight.daemon.impl`, not annotated, but an `impl` package): not used.
- `InspectionEngine.inspectEx/runInspectionOnFile`: inspections only, without the language's error highlighting
  (unresolved symbols are not an inspection), partly `@ApiStatus.Internal`.
- `DaemonCodeAnalyzerEx.processHighlights`: reads highlights of files that were highlighted in an editor (impl package);
  a file nobody opened has none. This is why idea-mcp-ext's diagnostics report 0 errors in a headless run.
- **`CodeSmellDetector.findCodeSmells(List<VirtualFile>)`** (`com.intellij.openapi.vcs`, abstract public class, no
  `@ApiStatus` annotation; results `com.intellij.codeInsight.CodeSmellInfo`: document, description, text range,
  severity, start line and column). The platform's "Analyze code before commit". Its implementation runs the same
  `MainPassesRunner` (all highlighting passes: the language's error checks, annotators, the enabled inspections of the
  project profile, or of the profile chosen for commit checks), at WARNING and above. **Chosen.**

Facts about `CodeSmellDetector` that the tool relies on (from `CodeSmellDetectorImpl` and `MainPassesRunner`):

- On the EDT it opens a modal progress (`Task.Modal`). Off the EDT it needs a `ProgressIndicatorEx` as the current
  indicator and asserts a background thread without a read lock. We call it on `Dispatchers.IO` under
  `ProgressManager.runProcess(..., ProgressIndicatorBase())`; the coroutine's cancellation cancels the indicator, which
  cancels the per-file daemon indicators.
- It restarts the whole batch when a write action begins (`beforeWriteActionStart` cancels), and returns only at the
  end: no partial results. So the use case analyses batches of 8 files, keeps finished batches when the time bound
  hits, and reports the rest as not analysed.
- Minimum severity is fixed at WARNING: weak warnings and information never come; we report errors (severity ≥ ERROR)
  and warnings (everything between).
- Dumb mode: passes that are not dumb-aware are skipped silently. Before reading the module model and the files we
  wait, as `mc_sync waitForIndexing` does, for `Observation.awaitConfiguration` (a Gradle or Maven import registers
  its activity) and a smart read action; again before each batch, and a batch is analysed again when indexing began
  during it (`DumbService.modificationTracker` changed or still dumb).
- Inspection ids: the detector writes `[id] description` only for findings of type
  `HighlightInfoTypeSeverityByKey`; in the platform test the deprecation and pointless-boolean inspections came without
  an id. The output field `inspection` is filled only when the id is there.
- `ProblemHighlightFilter.shouldProcessFileInBatch` skips files the IDE does not analyse in batch; we check that
  first and report those files as `notAnalysed`.
- It lives in the content module `intellij.platform.vcs.impl` of the bundled plugin `com.intellij.platform.vcs`, which
  declares the alias `com.intellij.modules.vcs`. Our descriptor `mcp-companion-vcs.xml` depends on that alias
  optionally; without it `mc_diagnostics` answers `UNSUPPORTED`.

### idea-mcp-ext (public README and docs/USAGE.md, MIT; scope only, no code read or copied)

- `build_module_with_dependencies`: builds a module with its upstream and downstream dependencies in topological
  order; returns `build_order`, `errors`, `warnings`, `errors_count` (-1 on a real Gradle/Maven failure).
- `get_aggregated_diagnostics`: inspection and compilation errors per module (`summary`, `by_module`,
  `total_errors`), project, module or file scope. Its documentation says that in a headless run the daemon has no
  markup, so `total_errors` can be 0 while the code is broken, and recommends the build tool for hard errors.

Our scope: module (optionally with dependencies or dependents), directory or files; errors or warnings; grouped by
file with positions; totals per severity and per module; paging and bounds. Files need not be open.

## Tool surface (decided)

| Tool | Parameters | Why |
|---|---|---|
| `mc_diagnostics` (toolset `diagnostics`, read-only, idempotent) | exactly one of `module` (+ `include=dependencies|dependents`), `directory`, `files`; `minSeverity` error (default) or warning; `limit` (files per call, default 100, hard bound 500), `offset`; `timeoutSeconds` (default 60, max 600) | One tool, no `action`: it does one thing. Paging is over the scope's files (sorted by path), because the expensive part is the analysis: page *n* analyses only its own files. The page lists the analysed files that have problems at or above the floor; `nextOffset` is the first file not analysed (also after a timeout), so `partial` results continue exactly. Totals per severity and per module always count errors and warnings of all analysed files. At most 50 problems per file (`omittedProblems`), at most 50 skipped files listed. |
| `mc_build` (toolset `build`, not read-only, not idempotent, open world) | `action` `build` or `status`; `module` (exact name), `includeDependents`, `includeWarnings`, `timeoutSeconds` (default 300, max 1800) | Its own toolset, not an action of `mc_modules`: building writes output and runs build tools, `mc_modules` is annotated read-only and MCP hints are per tool. A second build while one runs is **refused** (`CONFLICT`, names the running build), unlike `mc_sync`'s wait-and-share: builds of different modules cannot share a result, a queued call could spend its timeout waiting, and an incremental build right after is cheap. `status` gives the running build and the latest result (also of a call that timed out). Problems: at most 200 per build, errors first. |
| `mc_modules graph format=mermaid` | `format` json (default) or mermaid; `limit`/`offset` are refused with mermaid | One flowchart of all edges of the selection (whole graph or walk), not paged; at most 500 edges and 1,000 nodes (`omittedEdges`, `omittedNodes`). Node ids are generated (`m0`, `l0`), names are quoted labels with `#quot;`, `#lt;`, `#gt;`, `#35;` escapes; test edges dotted; the walk's root drawn thicker. |

No step-aside catalog entries: `build_project` has no module scope, `get_file_problems`/`lint_files` take explicit
files only (pinned by `BuildToolsContractTest`).

## Design

- `common/modulegraph` (moved from `modules`): `Dependency`, `DependencyTarget`, `DependencyScope`, `ProjectGraph`,
  `GraphDirection`, `GraphEdge`, `DependencyGraph`; `common/modulegraph/ide/IdeModuleGraph` reads the order entries
  (formerly in `IdeProjectModelGateway`, which now delegates). Used by `mc_modules`, `mc_build` (dependents and the
  module list of a build) and `mc_diagnostics` (`include`).
- `common/build` (pure): `BuildScope`, `BuildProblem`, `BuildRun`, `MessageSource`, `CompilerOutputParser`.
  `common/build/java`: `ProjectTaskBuild` (replaces `run/java/ProjectBuild`), `BuildToolConsole`, `IdeReloadVeto`
  (moved from `run/java`). `mc_run hotswap` and `mc_build` both use it.
- `build/`: `BuildControl` (`ActionRegistry`, `ParameterGuard`), handlers `BuildModule` and `ReportBuildStatus`,
  `BuildCoordinator` (one build per project, runs in the project service's scope), gateway interfaces
  `BuildEnvironment`, `ModuleBuilder`; `build/ide`: `BuildState` service, `IdeBuildEnvironment`, extension point
  `moduleBuilder`; `build/java`: `JvmModuleBuilderProvider` (in `mcp-companion-java.xml`).
- `diagnostics/`: `CollectDiagnostics` (scope, window, batches, deadline, totals), gateway interfaces
  `DiagnosticsEnvironment`, `FileAnalyzer`; `diagnostics/ide`: `IdeDiagnosticsEnvironment`, extension point
  `fileAnalyzer`; `diagnostics/vcs`: `CodeSmellFileAnalyzer` (in the new optional `mcp-companion-vcs.xml`).
- Konsist: `build` and `diagnostics` are layered features; new gateway segment `vcs`; new `SharedGatewaysTest`: VCS
  APIs only in `vcs` packages, `com.intellij.task` only in `common.build.java`, module order entries only in
  `common.modulegraph.ide`.
- Build without the Java plugin: `UNSUPPORTED`. Diagnostics without the VCS module: `UNSUPPORTED`.
- Delegated build outcome: a failing delegated Maven build ends as *aborted* in `ProjectTaskManager.Result` (seen with a
  compile error and with an unresolvable dependency), so an aborted build with errors is reported as `failed`; aborted
  stays for a build without any error.

## End to end

Sandbox `runIdeForMcpE2e -PmcpE2eAwtToolkit=XToolkit` on a private Xvfb `:97` (1440x900), a Python Streamable-HTTP
client (127.0.0.1:64399). Requests and responses, screenshots and thread dumps:
`/tmp/claude-1000/-home-sin-GIT-mcp-companion/be06ade8-e979-4e39-adb9-08921da9ad9d/scratchpad/step7/e2e/` (the report
names the files). Rounds: JPS; Gradle twice; Maven with the IDE building; Maven delegated three times; JPS in Chinese.

### Diagnostics

- JPS `module=buildbad`: 1 error, `Broken.java` 11:25 "Cannot resolve method 'missing' in 'Greeting'", 1.6 s (first
  call), 0.3 s later. `module=buildok minSeverity=warning`: 6 warnings (unused, deprecated call, redundant
  `strictfp`), 0 errors. `include=dependents` on `buildok`: `buildok` and `buildbad`, per-module totals.
  `directory`, `files` with `limit=1`: `nextOffset=1`, `hasMore`.
- Boundary, JPS `javaconly`: diagnostics 0 errors (7 warnings); `mc_build javaconly`: failed, `TooLarge.java` 8:24
  "java: code too large" and the javac warning "Integer(int) ... deprecated and marked for removal" for
  `Removal.java`, which the IDE reports only as "'Integer(int)' is deprecated since version 9" (its MarkedForRemoval
  inspection, level ERROR, did not fire). So: javac-only errors are invisible to diagnostics, and severities can differ.
- Gradle right after the IDE opened the fixture, before the import (first version, which only waited for smart
  mode): `directory=buildbad` returned **0 errors** in 1.2 s, the module did not exist yet. Since then the tool waits
  for the configuration activity: the same call (Gradle and Maven) waited 14.3 s / 14.8 s and found the error, with the
  right module once the files are listed after the wait.
- `timeoutSeconds=1` on the JPS project directory (47 files): `partial=timeout`, `analysedFiles=20`, `nextOffset=20`
  (batches of 20 then); `limit=500, offset=500, timeoutSeconds=5` on the Gradle project with 2,133 files:
  `analysedFiles=128`, `partial=timeout`, `nextOffset=628`.
- **A platform stall**: with 20 files per `CodeSmellDetector` call the JPS directory analysis hung reproducibly for
  60 s (19 files took 0.6 s; any 18-file window that contained `buildok/.../Greeting.java` hung; every file alone was
  fast). Thread dump (`jstack-diag.txt`, `jstack-hang2.txt`): pool threads in `ExternalToolPass.getInfos` sleeping in
  its wait loop for the `ExternalAnnotatorManager` queue, which gives up after 60 s; then the call completes
  (`j45-long`: 60.1 s, all 20 analysed). Batches are now 8 files; the JPS directory takes 1.9-3.6 s. On the Gradle
  project the first analysis of `gen00` (100 files, 13 batches) after a sync still took 64 s once (4 s on every later
  call): the stall is not fully explained and can recur; the time bound keeps calls bounded.
- Mermaid output checked with the IDE's own Mermaid plugin (bundled, enabled in the sandbox): `graph.mmd` with the
  81-node Gradle diagram had no problem and rendered in the preview (`gradle-mermaid-editor.png`); a deliberately
  broken copy got "Flowchart.LINK_TEXT or Flowchart.SEP expected" at 83:21 (through `mc_diagnostics`). The `.mmd`
  files were written outside the IDE; the first `files=` call answered `NOT_FOUND` until the editor opened them, so
  named files are now refreshed first (not re-run end to end afterwards).

### Build, per build system

| Case | Result |
|---|---|
| JPS `buildok` (+ warnings) | succeeded 1.5 s, `messages=compiler`, 2 javac warnings with positions (deprecation 9:22, strictfp 12:28) |
| JPS `buildbad` | failed, `modules=[buildbad, buildok]`, "java: cannot find symbol ..." `Broken.java` 11:24 |
| JPS `buildok includeDependents` | failed (`dependents=[buildbad]`), same error |
| Gradle `gradlefix.buildok.main` | succeeded, `messages=build_output`, javac warning `[strictfp]` `Legacy.java` 12:28 (column from the caret line) |
| Gradle `gradlefix.buildbad.main` | failed, "cannot find symbol" `Broken.java` 11:24 |
| Maven, IDE builds | like JPS (`messages=compiler`), `Broken.java` 11:24 |
| Maven delegated, `buildok` with an error added | failed, `messages=build_output`, "cannot find symbol" `Greeting.java` 6:28 (Maven `[ERROR] ...:[6,28]`), plus the `strictfp` warning |
| Maven delegated, `buildbad` (or `buildok includeDependents`) | failed: "Failed to execute goal on project buildbad: Could not resolve dependencies for project com.example:buildbad:jar:1.0". The IDE runs Maven per module (`-pl`), `buildok` is not installed, so a Maven-delegated module build cannot use a sibling module that is not in the local repository. |

Official `build_project` for comparison: JPS and IDE-built Maven give the same messages with **0-based** line and
column (Legacy 8:21 for line 9 column 22); Gradle gives them via the Build view (Broken 10:23); **Maven delegated: `isSuccess: true` with no
problems while `Greeting.java` did not compile** (`p06-official`), and also after the dependency failure. Our results
are 1-based, as the files show them.

Timeout and concurrency (Gradle, `compileJava.doLast { sleep 15 s }` added to the throwaway copy): `timeoutSeconds=3`
→ `state: running`, `waitedMs: 3000`; a second `build` → `CONFLICT` naming the running build; `status` → running and
the previous result; after the end `status` → `idle` with the build: succeeded, `durationMs: 15447`; a build right
after → succeeded.

Debug session (JPS): `mc_run start Beacon executor=debug`, edit `Beacon.java`, `mc_build module=fixture` → succeeded,
the list of visible windows was the same before and after (no "reload changed classes?" dialog; the IDE's default is
Ask). A later `mc_run hotswap` failed on the fixture's broken modules, as expected: hot swap builds all modules.

A failed build opens the file of the first error in an editor tab (IDE behaviour, seen: `TooLarge.java`,
`Broken.java`); no dialog in any round.

### Timing (Gradle, warm daemon)

Fixture as it is: `mc_build buildok.main` after an edit 0.21-0.27 s; `build_project` 0.26-0.49 s (fails on
`buildbad`); `mc_diagnostics module=buildbad.main` 0.31-0.70 s; `mc_diagnostics directory=.` (53 files) 2.2 s.

With 20 generated subprojects of 100 classes each (only in the throwaway copy; `buildbad` fixed):

| What | Time |
|---|---|
| `mc_build gen00.main`, first build of that module | 0.71 s |
| `build_project`, first build of the other 19 modules | 3.42 s |
| `build_project`, nothing changed | 0.39 s |
| `mc_build gen00.main`, nothing changed | 0.20 s |
| `mc_build gen00.main` after an edit in gen00 (3 rounds) | 0.20 / 0.23 / 0.19 s (class file rewritten, checked) |
| `build_project` after an edit in gen00 (3 rounds) | 0.43 / 0.44 / 0.36 s |
| `mc_diagnostics module=gen00.main` (100 files) | 64.4 s first time (stall, see above), then 4.0 s; `gen01.main` 3.9 s |
| `mc_diagnostics directory=.`, first 500 of 2,133 files | 19.4 s |

On this small project an incremental Gradle build is already fast; the gain of a module build is the configuration
and up-to-date checking of the other modules (about half the time here) and a cold build of only what is needed (0.7
vs 3.4 s). Diagnostics are slower than an incremental build for the same module here, but need no compile and see the
dependents' errors without building them. Not measured: a large real project.

### Screenshots

Settings page at 1440x900, English (`settings-en-1440x900.png`, Maven sandbox) and Chinese
(`settings-zh-1440x900.png`, JPS sandbox with `LocalizationStateService` zh-CN): ten rows, all visible without
scrolling (`McpCompanionConfigurableTest` also asserts `rowCount == visibleRowCount`).

## Limits and what is not proven

- Diagnostics see what the editor sees, not what only a compile finds (proven: "code too large"; severities can differ
  from javac). Annotation processors and generated code were not run end to end (no fixture with a processor).
- Inspection ids: the public result carries them only for one kind of finding; none came in the tests and fixtures.
- Weak warnings and information are never reported (`CodeSmellDetector` minimum is WARNING).
- The 60-second external-annotator stall can recur; its cause in the platform is not found.
- Builds started by the user in the IDE are not coordinated with `mc_build`; `status` knows only `mc_build`'s builds.
  The console of every execution that starts during a build is read; a process started meanwhile that prints
  compiler-format lines would add them.
- Positions of delegated builds depend on the console formats: javac (Gradle prints them), kotlinc 2.x, Maven
  compiler plugin. Kotlin was not run end to end (no Kotlin module in the fixtures); Groovy and Scala compilers,
  Gradle's Problems-API-only reports and other build tools give no positions.
- Untrusted project: refused before anything starts (`BuildControlTest`); not run end to end, because the sandbox
  trusts every project (`-Didea.trust.all.projects=true`) and an untrusted start would open the trust dialog.
- Maven-delegated module builds need the sibling modules in the local repository (see the table).
- Not run: a debug session on the Gradle fixture (the veto is the same code as on JPS), Windows and macOS.

## Surprising

- The Gradle sandbox downloaded `gradle-9.8.0-bin.zip` from services.gradle.org into the private Gradle user home
  (`build/mcp-e2e-gradle-user-home/wrapper/dists`) on its first import, although the harness writes a LOCAL Gradle home
  into `.idea/gradle.xml`; the IDE rewrote the file without `gradleHome` (it adds `GradleMigrationSettings`). Step 4
  noted "no network is used"; that no longer holds for a fresh sandbox. Not changed here.

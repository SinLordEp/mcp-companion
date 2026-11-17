# Step 4: `mc_modules` and `mc_sync`, platform notes

Date: 2026-11-16. IntelliJ IDEA 2026.3 (IU-263.7307.200). Findings made while building `mc_modules` and `mc_sync`.

## What the bundled server already offers (263)

Every `mcpToolset` registered in the 263 distribution was read from the jars (descriptors and bytecode; tool names,
parameters and result classes). Related to modules, dependencies, building or project sync there are exactly three
official tools, all in `AnalysisToolset` (`plugins/mcpserver-plugin/lib/modules/intellij.mcpserver.toolsets.jar`).
No Gradle, Maven or external-system plugin registers an MCP toolset, and no official tool syncs, reloads or
re-imports a project. `BuildToolsContractTest` pins the set (it also lists Qodana's unrelated
`compile_inspection_kts`), the parameters and the output schemas.

| Official tool | Parameters | Returns | Consequence |
|---|---|---|---|
| `get_project_modules` | none | `modules: [{name, type}]` (`Module.getName`, `getModuleTypeName`) | No content roots, folders, build system, SDK or language level. `mc_modules list` kept: it answers the PLAN section 3 decision (which directories next to the project directory belong to it). |
| `get_project_dependencies` | none | `dependencies: [{name, version?, dependencyType?, source?}]`; for JPS the `projectDependenciesProvider` `JpsDependenciesProvider` lists the **file names of library class roots** of all modules, de-duplicated, `source=jps-library` | No owning module, no module-to-module edges, no scope, no exported flag, no coordinates. `mc_modules graph` and `cycles` kept. |
| `build_project` | `rebuild`, `filesToRebuild`, `timeout` | `isSuccess`, `problems: [{message, kind, group, description, file, line, column}]`, `timedOut`. Builds through `ProjectTaskManager` (`createAllModulesBuildTask` or files) and collects problems from `BuildViewManager` events, so delegated Gradle/Maven builds report positions too. | Covers building. The only gap is a module scope. **The planned `build` action was dropped**: a module build of ours would have to read delegated-build errors through the build view listener API, which the step-3 notes found `@ApiStatus.Experimental`, so it would report less than the official tool. Agents use `build_project`; `mc_modules` names it in its description. |

Because `build` is gone, `mc_modules` is purely read-only (`readOnlyHint=true`), and the question whether a mutating
`build` belongs in a read-only tool does not arise. The equivalence catalog stays empty.

## Package layout

- `modules/`: use cases and domain, platform-free: `ModulesControl` (`ActionRegistry` + the shared `ParameterGuard`),
  one handler per action (`ListModules`, `GraphModules`, `FindCycles`), `DependencyGraph` (indexes both ways, iterative
  breadth-first walk), `StronglyConnectedComponents` (iterative Tarjan), the gateway interface `ProjectModelGateway`.
- `modules/ide/`: `IdeProjectModelGateway` (`ModuleManager`, `ModuleRootManager`, one cancellable `readAction` per
  call), `ContentFolders`, the extension point `moduleFacts` (`ModuleFactsProvider`).
- `modules/java/`: `JvmModuleFacts` (language level, Maven coordinates), in `mcp-companion-java.xml`.
- `modules/externalsystem/`: `ExternalSystemModuleFacts` (owning build system), in `mcp-companion-external-system.xml`.
- `sync/`: use cases and domain: `SyncControl`, `ReportStatus`, `RunSync`, `UnitSelection`, `SyncCoordinator` (the
  concurrency rule), gateway interfaces `BuildSystemSync` (strategy per build system) and `SyncEnvironment`.
- `sync/ide/`: the extension point `buildSystemSync` (`BuildSystemSyncProvider`), the project service `SyncState`
  (holds the coordinator, scoped to the project), `IdeSyncEnvironment`, `syncControlFor`.
- `sync/externalsystem/`: `ExternalSystemSync` (Gradle and any other `ExternalSystemManager`), `SyncEnd`,
  `FailureText`, `ExternalSystemSyncActivity` (project service: the IDE's syncs per linked project); in
  `mcp-companion-external-system.xml`.
- `sync/maven/`: `MavenSync`, `MavenSyncProgress`, `MavenSyncEvents`; in `mcp-companion-maven.xml`. They also use
  the external-system API (`ExternalSystemProjectNotificationAware`). That is safe without a dependency of ours on
  `com.intellij.modules.externalSystem`: in the 263 descriptor of the Maven plugin the required content module
  `intellij.maven` (`loading="embedded"`) depends on `intellij.platform.externalSystem` and
  `intellij.platform.externalSystem.impl`, so wherever our Maven descriptor loads these classes load too. Pinned by
  `BuildToolsContractTest.testTheMavenPluginRequiresTheExternalSystemModules`.
- `toolsets/modules`, `toolsets/sync`: the adapters.
- Shared pieces moved to `common`: `common/time` (`Clock`, `SystemClock`, `withTimeout`, `timeoutOf`, formerly in
  `run`) and `common/actions` (`ToolArguments`, `ParameterizedAction`, `ParameterGuard`, formerly private to `run`).

Konsist (`LayeringTest`, 18 rules): `modules` and `sync` are layered features; `externalsystem` and `maven` are new
gateway segments; use cases of `run`, `modules` and `sync` import no gateway of their feature; the neutral `ide`
gateways import none of the optional-plugin segments; `com.intellij.openapi.externalSystem` only in `externalsystem`
and `maven` packages; `org.jetbrains.idea.maven` only in `maven` packages; `com.intellij.java.library`,
`LanguageLevelUtil` and `com.intellij.pom.java` added to the Java-plugin APIs.

## Where the APIs live (263)

| API | Location | How we depend on it |
|---|---|---|
| `ModuleManager`, `ModuleRootManager`, order entries, `DependencyScope` | `lib/intellij.platform.projectModel.jar` | core |
| `JavaSourceRootType`, `JavaResourceRootType`, `JavaSourceRootProperties` | `lib/jps-model.jar` (platform) | core (`ContentFolders`) |
| External-system API (`ExternalSystemManager`, `ExternalSystemApiUtil`, `ImportSpecBuilder`, `ExternalSystemProjectNotificationAware`, `ProjectDataImportListener`, `ExternalSystemModulePropertyManager`) | product module `intellij.platform.externalSystem` (`lib/`) | — |
| `ExternalSystemUtil`, `ExternalSystemProcessingManager`, `ExternalSystemProgressNotificationManager` | product module `intellij.platform.externalSystem.impl` (`lib/`), which declares the alias `com.intellij.modules.externalSystem` | optional `<depends>` on `com.intellij.modules.externalSystem` with `mcp-companion-external-system.xml`. It is a product module, not part of every IDE, so it is optional. |
| `Observation.awaitConfiguration` | `lib/intellij.platform.backend.observation.jar`, public, no `@ApiStatus` | core (`IdeSyncEnvironment`) |
| `LanguageLevelUtil`, `JavaLibraryUtil.getMavenCoordinates` | Java plugin | `mcp-companion-java.xml` |
| `MavenProjectsManager`, `MavenSyncListener`, `MavenProjectProblem`, `MavenUtil.SYSTEM_ID` | Maven plugin (`org.jetbrains.idea.maven`) | optional `<depends>` with `mcp-companion-maven.xml` |
| Gradle | the Gradle plugin registers `GradleManager` as an `ExternalSystemManager` | **no Gradle class is used**: Gradle is driven through the neutral external-system API, so no Gradle descriptor exists |

Maven is not an external system in this sense: it registers no `ExternalSystemManager`, only an
`ExternalSystemProjectAware` (`MavenProjectAware`, `@ApiStatus.Internal`) with the auto-reload tracker. Hence its own
gateway.

Without the external-system module and the Maven plugin both extension points are empty: `status` lists no build
system and `sync` answers `UNSUPPORTED`; nothing fails at load time. Without the Java plugin `languageLevel` and
`coordinates` are absent.

## `mc_modules`

- `list`: modules sorted by name, paged (`limit` default 100, max 1000, `offset`), optional exact `module`. Per module:
  `type` (`ModuleType.id`), `buildSystem` (`gradle`, `maven`, other external-system ids lower case; from
  `ExternalSystemModulePropertyManager`: `isMavenized()` or `getExternalSystemId()`), `sdk`, `languageLevel` (Java
  modules: feature number, `-preview` suffix), content roots with `path` in the project's location model
  (`ProjectPathRenderer`, the same form as the official tools: checked against `relativizeIfPossible` in
  `ModulesToolsetTest`) and `outsideProjectDir=true` for roots that are not under the project directory. Folders:
  `kind` source, test, resource, test_resource, excluded; `generated=true` for generated source and resource roots;
  at most 100 per root (`omittedFolders`). Root types of other languages (Kotlin, Python ...) count as source or test
  by their `isForTests` flag; a non-Java resource root type is therefore shown as source.
- `graph`: declared order entries. Whole project, or a breadth-first walk from `module` in `direction` dependencies
  (default) or dependents, at most `depth` edges (default unlimited). Every order entry is reported once, also one
  leading back into the walk, so cycles stay visible; each walk edge has its `depth`. Order: depth, from, modules
  before libraries, target, scope; pages are stable. `includeLibraries` adds library entries (name, and
  `group:artifact:version` from `JavaLibraryUtil.getMavenCoordinates` when the library's properties carry them, never
  parsed from the name); refused with `direction=dependents`. A dependency on a module that does not exist is an edge
  with `missing=true`. `depth`/`direction` without `module` are refused.
- `cycles`: strongly connected components with more than one module plus self loops (iterative Tarjan: no recursion,
  linear; a closed 100,000-module chain is a unit test). Per group: the modules (sorted), one shortest cycle through
  the first module (breadth first), and all edges among the group's modules (at most 500, `omittedEdges`).
  `ignoreTestScope` drops test-scope edges. Edges to missing modules are ignored.
- The exported flag is reported, not interpreted: the walk follows every edge, not only what ends up on a classpath.
- Threading: one `readAction` per call (the module model needs no indexes), `ProgressManager.checkCanceled()` per
  module; never on the EDT.

## `mc_sync`

### API choice

| Need | Gradle (and other external systems) | Maven |
|---|---|---|
| Linked projects | `ExternalSystemApiUtil.getSettings(project, id).linkedProjectsSettings` | `MavenProjectsManager.isMavenizedProject`, `rootProjects` |
| Needs reload | `ExternalSystemProjectNotificationAware.isNotificationVisible(systemId)` (the "Load changes" notice) | same, with `MavenUtil.SYSTEM_ID` |
| Running now (`activity`) | `ExternalSystemSyncActivity`: busy from a resolve task's `onStart` to its `onEnd` and from `ProjectDataImportListener.onImportStarted` to `onImportFinished`/`onImportFailed`; resolves begun are counted; a resolve already running at the first call is taken from `ExternalSystemProcessingManager.findTask` | `MavenSyncListener` (application topic) start/finish counts since the plugin loaded |
| Start | `ExternalSystemUtil.refreshProject(path, ImportSpec)` with `ProgressExecutionMode.IN_BACKGROUND_ASYNC`, on the EDT (it asserts a write-safe context and saves all documents, as the IDE's own Reload) | `MavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()` ("Reload All Maven Projects") |
| End and result | `ExternalProjectRefreshCallback` (`withCallback`); plus the task's `onCancel` and `ProjectDataImportListener.onImportFailed` for the ends that bypass the callback | the next `syncFinished` after our call; result from `MavenProject.getProblems()` (SYNTAX/STRUCTURE problems fail the sync, the others are reported as `messages`) |

Rejected: `ExternalSystemProjectTracker.markDirty` + `scheduleProjectRefresh` (would cover both build systems, but
gives no public way to learn the end or the result of someone else's project aware); `ImportSpecBuilder.callback`
(deprecated; `withCallback` used); the deprecated `ExternalSystemUtil.refreshProject(project, id, path, ...)`
overloads; Maven's `MavenAsyncProjectsManager.updateAllMavenProjects`, `scheduleUpdateMavenProjects` and
`MavenSyncSpec` (`@ApiStatus.Experimental`, which is why Maven syncs all its projects together); Maven's
`scheduleImportAndResolve`/`updateAllMavenProjectsSync` (deprecated, modal progress); `ProjectResolverPolicy`
(experimental).

### Waiting: event-driven

- The use case waits on the gateway's suspending `sync`, under a deadline on the injected `Clock` (no sleeping).
- External systems: a `CompletableDeferred` completed by the callback, the resolve task's cancel event, the data
  import's failure event, or the failure/cancel event of a before-sync task (an `EXECUTE_TASK` of the same linked
  project before our resolve began: `ExternalSystemTaskActivator.runTasks` then returns false and `executeSync`
  returns without resolving and without calling back).
- Maven: `MavenProjectsManager`'s next `syncFinished` (counts in `MavenSyncProgress`, fed by `MavenSyncEvents`,
  `<applicationListeners>` on `MavenSyncListener`, pinned by `BuildToolsContractTest`). End to end every Maven sync,
  also one with nothing changed and one that failed on a non-parseable POM, published exactly one
  `syncStarted`/`syncFinished` pair (logged by `MavenSyncEvents`).
- `waitForIndexing=true`: after successful syncs, within the same timeout, `Observation.awaitConfiguration(project)`
  (all registered configuration activity trackers, Gradle's and Maven's included, report nothing in progress) and then
  a `smartReadAction` (the indexes are ready). That is what `indexing: "done"` means. `timed_out` and `skipped` (a
  sync did not succeed) are reported as such.

### A sync whose end the IDE never reports (the watch)

Our own record of a run is not trusted on its own: a run ends when its gateway returns, or when the coordinator's
watch decides that the IDE no longer works on it. The watch only reads the IDE's activity for the unit
(`BuildSystemSync.activity`: busy flag and number of syncs begun) and the injected clock:

1. Wait until the IDE begins a sync of the unit (a begun count above the one read before the request was made, so a
   sync that begins and ends before the watch looks still counts). Bound: `Limits.start` = 2 minutes. There is no
   public event for "the IDE dropped the request": the background task is queued at once and begins its resolve
   within milliseconds (Gradle before-sync tasks run first; their failure is caught by the event above). Silence for
   2 minutes ends the run as failed: "The IDE never began this sync".
2. Then, each time the IDE reports idle, wait `Limits.settle` = 10 s for either the result or the IDE being busy
   again. The result is delivered on the IDE's sync thread right after its last reported phase (Gradle: the callback
   follows the data import; Maven: `syncFinished` precedes our read of the problems), and the only idle gap between
   reported phases is the resolve's end before the data import begins. A result missing 10 s after the IDE went idle
   will not come: the run ends as failed, "The IDE finished working on the sync but reported no result", and the
   gateway's wait is cancelled (the IDE's own sync is never touched).

The race "the IDE has not registered the task yet when we look" is closed by step 1: the watch never concludes
"idle" before the IDE has shown a sync begun after our request. Consequences: a dead run never blocks its unit (a
waiting request gets its own sync once the watch ended the dead one); `status` shows `running` from the IDE's
activity and from runs not yet ended, so a dead run stops showing as running when the watch ends it. A Gradle
before-sync task that runs longer than 2 minutes ends our run as "never began" (the IDE's sync goes on; `status` shows
it running from its resolve on); before-sync tasks are not counted as activity because user-run Gradle tasks of the
same project (e.g. a Gradle-delegated run configuration) are the same kind of task and would make the unit look busy
for as long as they run. Unit tests (`SyncControlTest`, no sleeping): a sync the IDE finished without a result fails
after the settle bound and the next request starts a new one; a short idle gap between phases does not end it; a sync
the IDE never begins fails after the start bound; a request waiting behind a dead sync gets its own.

### Failure messages

The callback's failure text for Gradle is the stack trace of `ExternalSystemPartialResolutionException`
(`@ApiStatus.Internal`, not referenced by us), seen end to end; the actual reason is only in its chain and in Gradle's
error output. `FailureText` therefore listens to the resolve task's own `onFailure` (messages of the exception chain:
causes and suppressed, without class-name-only wrappers) and to its error output (`onTaskOutput`, stderr, last 40
lines), and uses the line after Gradle's `* What went wrong:` as the message. Stack frames are removed; message and
details are at most 4,000 characters each. Tested in `FailureTextTest` with the end-to-end sample.

### Concurrency rule (`SyncCoordinator`, `SyncControlTest`)

Per sync unit (one linked external-system project, or all Maven projects):

- never two syncs at a time: a request waits until the running sync ends, whether `mc_sync` or the IDE started it,
  and only then starts its own;
- a request never reuses a sync that started before it arrived (that sync may have read the build files before the
  agent's edit); requests that wait at the same time share the one sync started after all of them arrived
  (`joined: true`).

Syncs run in the project service's coroutine scope, not the caller's: when `timeoutSeconds` (default 60, max 600)
passes, the call returns `state: running` and the sync goes on; `status` shows `running` and later the result
(`lastSync`). A timeout while waiting for another sync returns `not_started`; nothing was started. End to end, three
concurrent calls produced two syncs (`par1` own, `par2` joined, `par3` own; the IDE log shows two resolve runs).

### UI paths and rules

From the 263 bytecode (scan for `Messages`, `DialogWrapper`, modal progress, trust dialogs in the external-system,
Gradle and Maven jars, then reading the code reached from our calls):

| Where | UI | When | Rule |
|---|---|---|---|
| `ExternalSystemUtil.refreshProjectImpl` | none; an untrusted project is synced in preview mode (no build script runs) | always | we refuse untrusted projects (`CONFLICT`) before calling it, like `mc_run` |
| `ExternalSystemUtil.confirmLoadingUntrustedProject` / `ExternalSystemTrustedProjectDialog` | modal trust dialog | only callers of that deprecated method and `ExternalSystemRunnableState` (task runs), never on our path | not reached |
| `ExternalSystemTaskUnderProgress` with `MODAL_SYNC` | modal progress | progress mode `MODAL_SYNC` | we always pass `IN_BACKGROUND_ASYNC` |
| `ExternalSystemUtil.selectProjectDataToImport` | `ExternalProjectDataSelectorDialog` | `withSelectProjectDataToImport(true)` | we pass `false` explicitly |
| Sync view (`BuildContentDescriptor`) | Build tool window activation, focus, navigation to the error in an editor | the spec's activate/navigate flags | `withActivateToolWindowOnStart(false)`, `withActivateToolWindowOnFailure(false)`, `dontNavigateToError()`; the sync tab is still filled (no window, no question) |
| `GradleInstallationManager.invokeSdkLookup` | `runWithModalProgressBlocking` (Gradle JVM lookup) | only when called on the EDT | the resolve runs in the background task; not on the EDT |
| Gradle JDK or Gradle home problems | build issues with quick fixes in the sync view, notifications | invalid Gradle JVM/home | non-modal; they become the failure message |
| `GradleOpenProjectProvider` trust dialog | modal | opening/linking a project | not used (we never link projects) |
| Maven `updateAllMavenProjectsSync` | modal progress | deprecated `scheduleImportAndResolve` | not called |
| Maven untrusted project | `showUntrustedProjectNotification` (balloon) | untrusted | refused before |
| Maven wrapper (`MavenWrapperDownloader.checkOrInstallForSync`) | background progress, downloads the wrapper's Maven | project uses the Maven wrapper | accepted: non-modal; the tool is marked `openWorldHint=true` and its description says the build tool may download |
| Orphan modules after a sync | balloon "The IDE modules below were removed by the Gradle project sync … open a dialog to restore" | a failed Gradle sync imported partial data (seen end to end, screenshot `build/step4-e2e/e2e-gradle-after.png`) | non-modal; the dialog opens only if the user clicks the link. Measured end to end: after the failed sync the model had 3 of 12 modules (only the included build), after the fixed sync 12 again. A failed result therefore carries `nextStep`: fix the build file and sync again; missing modules are not gone. A failed Maven sync (non-parseable POM) kept all 4 modules. |
| Maven sync console | the Build tool window's "Sync" tab | every Maven sync | a tool window tab, no new window, no question (window lists unchanged) |

End to end (non-headless sandbox, XToolkit, private Xvfb `:97`): the list of visible top-level windows was identical
before and after every call (Gradle and JPS fixtures, 40 recorded calls including apply_patch, failing syncs, a
timed-out sync and three concurrent syncs); the screenshots show no dialog.

### MCP hints

`mc_modules`: `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`, `openWorldHint=false`.
`mc_sync`: `readOnlyHint=false` (`sync` changes the IDE's project model; `status` is read-only, but hints are per
tool), `destructiveHint=false` (no user data is deleted; the model follows the build files), `idempotentHint=false`
(the MCP spec means "calling again with the same arguments has no additional effect"; a second sync runs the build
scripts again, which may download or have other effects of their own, so the hint is not given), `openWorldHint=true`
(the build tool may download dependencies, wrappers, plugins). Pinned in
`BuildToolsContractTest` and seen in the sandbox's `tools/list`.

## End to end

`runIdeForMcpE2e` changes: the Gradle fixture is a multi-project build (`app` depends on `lib` and on
`com.example:shared:1.0`, which comes from the included build `src/mcpE2eFixtureGradleSibling`, copied to
`build/mcp-e2e-gradle-sibling`, i.e. next to the project directory, like a composite of sibling repositories). Gradle
runs by the sandbox use a private Gradle user home `build/mcp-e2e-gradle-user-home` (`-Dgradle.user.home` and
`GRADLE_USER_HOME` for the sandbox IDE; its sync daemon registered there, not in `~/.gradle`). The JPS fixture got the
modules `cyclea` (source, test, resource and excluded folders; depends on `cycleb` exported and on `fixture` in test
scope) and `cycleb` (depends on `cyclea` at run time). `mc_run` start/wait_ready/stop on `Beacon` still works with the
new modules.

Recorded requests and responses: `build/step4-e2e/e2e-*.txt` (g*: first Gradle run, h*/i*/j*: Gradle after the
failure-text change, k*: JPS). Highlights:

- Gradle `list`: 12 modules (per-source-set), `buildSystem: gradle`, `languageLevel: 25`, the included build's roots
  `../mcp-e2e-gradle-sibling...` with `outsideProjectDir: true`.
- `apply_patch` adds `include 'tools'` and `tools/build.gradle` → `status` shows `needsReload: true` after a few
  seconds (the tracker is asynchronous; right after the patch it was still `false`) → `sync` with
  `waitForIndexing: true`: `succeeded` in 569 ms, `indexing: done` → `graph module=gradlefix.tools.main` shows the new
  edge to `gradlefix.lib.main`, and `status` shows `needsReload: false` and the last sync.
- Broken `lib/build.gradle` → `sync`: `failed`, message `Groovy DSL script compilation problem`, details with Gradle's
  "Where" and "Unexpected input: '{' @ line 5, column 14"; after the fix `succeeded`.
- `Thread.sleep(8000)` in `settings.gradle`, `timeoutSeconds: 2` → `state: running`; `status` → `running: true`;
  9 s later `status` → `succeeded`, `durationMs: 8323`.
- JPS: `cycles` → `[cyclea, cycleb]`, cycle `cyclea → cycleb → cyclea`, edges compile/exported and runtime;
  `sync` → `NOT_FOUND` (no linked project); `status` lists `gradle` and `maven` without projects.

## Maven end to end

`runIdeForMcpE2e -PmcpE2eFixture=maven` opens `src/mcpE2eFixtureMaven` (parent `mavenfix` with modules `core` and
`app`, `app` depends on `core`, no third-party dependency) copied to `build/mcp-e2e-maven`. The task writes the
fixture's `.idea/workspace.xml` with `MavenImportPreferences` (the persisted `MavenGeneralSettings`, read from the
263 bytecode: `localRepository` = `build/mcp-e2e-m2`, `userSettingsFile` = a fixture-local settings file whose
`<localRepository>` is the same directory). The bundled Maven downloaded the build plugins it needed into that
repository; `~/.m2/repository` was empty before and after (directory unchanged since Nov 11). Requests and responses:
`build/step4-e2e/round2/e2e-m*.txt`, `e2e-n*.txt`, `e2e-mpar*.txt`; the window list was unchanged by every call.

- `status`: `maven` with project `.`; `list`: `app`, `core`, `mavenfix` with `buildSystem: maven`, source, test,
  resource, test_resource and excluded folders.
- `sync` with nothing changed: `succeeded` in 173 ms, one `syncStarted`/`syncFinished` pair.
- `apply_patch` adds `<module>tools</module>` and `tools/pom.xml` (depends on `core` and `org.slf4j:slf4j-api:2.0.16`)
  → `status` `needsReload: true` → `sync` with `waitForIndexing`: `succeeded` in 912 ms, `indexing: done`, one pair →
  `graph module=tools includeLibraries=true`: `tools → core` and `tools → Maven: org.slf4j:slf4j-api:2.0.16` with
  `coordinates: org.slf4j:slf4j-api:2.0.16` (library coordinates proven).
- Non-parseable `app/pom.xml` → `failed`, message `…/app/pom.xml:14:19: Non-parseable POM … TEXT must be immediately
  followed by END_TAG …`, with `nextStep`; still one pair (Maven reports the end of a sync that fails early).
- Three concurrent calls after the fix: two syncs (two pairs), the second call `joined: true`, the third shares it.

## Limits and what is unproven

- Gradle before-sync tasks: the failure signal (an `EXECUTE_TASK` of the project failing before our resolve) comes
  from reading `ExternalSystemUtil.executeSync` and `ExternalSystemTaskActivator.runTasks`; it was not run end to end
  (the fixture has no before-sync task). If it does not fire, the watch ends the run after the 2-minute start bound.
- Maven syncs started before the plugin was loaded are not seen as running.
- `needsReload` is per build system (the IDE's notice), not per linked project, and lags edits by a few seconds; the
  tool description says to call `sync` after an edit without waiting for it.
- `lastSync` covers only syncs `mc_sync` started; results of syncs the user started are not known.
- A failed Gradle sync leaves only the part of the project it could import until the next successful sync (measured:
  3 of 12 modules).
- No Mermaid output for `graph` (PLAN section 4 mentions it); JSON only. Added in step 7 (`format=mermaid`, `docs/notes-step7.md`).

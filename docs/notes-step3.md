# Step 3: run control (`mc_run`), platform notes

Date: 2026-11-16. IntelliJ IDEA 2026.3 (IU-263.7307.200). Findings made while building `mc_run`.

## What the bundled server already offers (263)

Read from the bytecode of every `mcpToolset` of the IDE (and pinned by `ExecutionContractTest`):

| Tool (toolset) | What it does | Consequence for `mc_run` |
|---|---|---|
| `get_run_configurations` (ExecutionToolset) | Lists run configurations (name, type, arguments, env), or the run points of a file. | Not duplicated: `mc_run` has no list of configurations, its descriptions point to this tool. |
| `execute_run_configuration` (ExecutionToolset) | Runs a configuration by name or a temporary one from `filePath`+`line`, Run executor only. `waitForExit=true` (default, `timeout` 10 s) waits for the end and returns output snapshot, `exitCode` (absent on timeout, the process keeps running) and `fullOutputPath` (a temp file). `waitForExit=false` returns once the process started. It returns **no handle**: nothing can address that process later. No stop, restart, status or readiness. Starts through `ProgramRunnerUtil.executeConfigurationAsync`, so an invalid configuration opens the configuration editor (`RunDialog.editConfiguration`), and a second instance is started even when the configuration does not allow parallel runs. **Asks the user first** unless brave mode is on (see below). | Overlaps only with `start`; kept, because a run without a handle cannot be stopped, read or hot-swapped, and the debug executor is missing. |
| `xdebug_start_debugger_session` (debuggerMcp) | Starts a configuration with the debugger, waits for the session. | Overlaps with `start executor=debug`; kept for the same reason (handle, readiness, stop, hot swap). |
| `xdebug_control_session` | Step, resume, pause, **stop** a debug session. | Stops debug sessions only; `mc_run stop` covers run and debug. |
| `xdebug_get_process_output` | Output of **debug** sessions (also ones not started by MCP), line ranges, up to 1,000,000 characters per session, five stopped sessions retained. | Debug only; `mc_run output` covers run and debug, with regex filter and tail. |
| `xdebug_get_debugger_status`, `xdebug_*`, `jvm_debug_*` | Breakpoints, stepping, evaluation, thread dumps. | Not duplicated; the description of `mc_run` refers to them. |
| `execute_terminal_command` (TerminalToolset) | Runs a shell command. | Out of scope (`mc_run` runs only existing configurations). |
| `coverage_run_configuration` (java-coverageMcp) | Runs a configuration with the coverage executor. | Out of scope. |
| `monitor_start/wait/stop` (AI Assistant "Codex monitor", air plugin) | Starts a shell command and polls its output with a cursor. Not served to ordinary MCP clients in the sandbox (absent from `tools/list`). | Similar output model, different target (shell commands, not run configurations). |

No official tool stops or restarts a Run process, reads the console of a Run process, waits for readiness, or hot-swaps.
So no action was dropped and the equivalence catalog stays empty. Nothing in 263 hot-swaps at all.

### Consent

**Corrected 2026-11-17** (details in `docs/notes-consent.md`): `execute_run_configuration` **does** ask.
`Execution_utilKt.prepareAndExecuteRunConfiguration`, the helper `ExecutionToolset` calls, resolves the configuration
and then calls `checkUserConfirmationIfNeeded` ("MCP client wants to execute run configuration '…'", Allow/Deny, a
"brave mode" checkbox) before it executes anything. The gate asks when the session's `AskCommandExecutionMode` is `ASK`,
or `RESPECT_GLOBAL_SETTINGS` (every external client) while brave mode (`McpServerSettings.enableBraveMode`) is off; it
is also used by `execute_terminal_command` and the IDE script tool. The first reading was wrong because the search for
callers covered `ExecutionToolset` and the gate's own coroutine class, not the helper in the same file as the gate; the
step-3 rounds never called the official tool, step 3b did and saw the dialog.

The session mode is in package `impl`, but brave mode is public API (`McpServerSettings`, module `intellij.mcpserver`,
not internal). `mc_run` therefore asks too, governed by the same brave mode: `start`, `restart` and a `hotswap` that
restarts show a confirmation of their own after the checks that need no user (see `docs/notes-consent.md`). What also
keeps starts deliberate:

- the tool carries MCP annotations `destructiveHint=true`, `readOnlyHint=false` (`@McpToolHints`, public), so clients
  that ask before destructive tools ask before every `mc_run` call;
- `action` has no default; a start needs an explicit `action=start` and an exact configuration name (and `type` when
  ambiguous); unknown or inapplicable parameters are rejected;
- only existing run configurations, in a trusted project (`TrustedProjects.isProjectTrusted`); never commands,
  never new or changed configurations.

Residual risk: with brave mode on, any MCP client allowed to call `mc_run` can run the project's run configurations,
exactly as it can with `execute_run_configuration`; the user switches the tool off on the MCP Server's tool page if
that is not wanted.

## Design

Packages (Konsist: `run` is a layered feature; `ide` and `java` are gateway packages; use cases import neither):

- `run/` use cases and domain: `RunControl` (dispatch through `ActionRegistry`, rejects parameters an action does not
  take), one `RunActionHandler` per action (`StartProcess`, `StopProcess`, `RestartProcess`, `ProcessStatus`,
  `ListProcesses`, `ReadOutput`, `WaitReady`, `HotSwapProcess`), `ProcessLifecycle` (start/stop/restart rules shared by
  three actions), `ProcessResolver`/`ConfigurationResolver`, gateway interfaces (`ProcessCatalog`, `ExecutionGateway`,
  `HotSwapGateway`, `PortProbe`, `Clock` with `SystemClock`). The parallel-run check and the scheduling of a start run
  under one mutex per project (held by `ProcessTracker`), so two concurrent starts of a configuration without parallel
  runs start it once.
- `run/output/`: `ConsoleBuffer` (bounded ring buffer, line assembly, ANSI removal, cursors), pure.
- `run/readiness/`: readiness strategies (`OutputCondition`, `TerminationCondition`, `PortCondition`), pure.
- `run/ide/`: platform gateways: `ProcessTracker` (project service, the catalog), `IdeTrackedProcess` (one observed
  process), `ExecutionEventsListener` (message bus), `IdeExecutionGateway` + `LaunchChecks` + `LaunchQuestions`,
  `RunningProcessAdoption`,
  the `HotSwapSupport` extension point and its gateway, the loopback port probe, `runControlFor(project)`.
- `run/java/`: `JvmHotSwapSupport` (registered in `mcp-companion-java.xml`), `JvmHotSwapSession`, `ProjectBuild`,
  `IdeReloadVeto`.
  Without the Java plugin the EP is empty and `hotswap` answers `UNSUPPORTED`; everything else works.
- `toolsets/run/`: the adapter (`RunToolset`), input enums and output models.

### Handles

`p` plus a per-project sequence number (`p1`, `p2`, ...), assigned when the tracker first hears of a process (our
`start`, or the IDE's schedule event for any other start). Opaque, never reused, unambiguous when a configuration runs
twice, and stable across calls while the process is retained. Not persisted: processes do not survive an IDE restart
either. `ExecutionEnvironment.executionId` was not used: a rerun copies it.

A process is released (with its output) when its run tab is disposed after it ended (closed, or reused by the next run
of the same configuration, which is what `restart` does), when it is among more than 20 ended processes, or when the
project closes. A running process whose tab is closed is released when it ends.

### Output capture (observer)

`ExecutionEventsListener` is a `<projectListeners>` entry on `ExecutionListener`; it is created by the platform on the
first event, also for starts made in the IDE, from the moment the plugin is loaded. In `processStarting(executor, env,
handler)` the tracker attaches a `ProcessListener` with the process's `Disposable`; `ExecutionManagerImpl` publishes
this event **before** `startNotify()`, so no output is lost (pinned by `ExecutionContractTest`, which also pins that
`env.contentToReuse` already is the run tab of the handler). Our own starts are registered by environment before the
runner sees it, so the first event finds the handle `start` returned.

Per process: at most 10,000 lines, 2,000,000 characters (about 4 MB), lines split at 8,000 characters; the oldest lines
go first and `output` reports `missed` lines to a client polling with an old cursor. `\r\n` and `\n` end lines, a lone
`\r` overwrites the line (progress bars), ANSI CSI/OSC sequences are removed when a line completes (so sequences split
across chunks are caught). stdout, stderr and IDE lines (command line, "Process finished") are tagged. Readers
(`output`, `wait_ready`) never touch the console UI; `wait_ready` watches the buffer's version `StateFlow` and re-reads
from its cursor, so no unbounded queue exists.

Processes that were running before the plugin was loaded (dynamic install or update) are adopted from
`RunContentManager.allDescriptors` on the first call: state, exit code, stop, restart (when exactly one configuration
has the tab's name) and output **from adoption on** work; earlier output, the start time and (for other executors) the
executor are not known, and `outputComplete=false` says so. Adoption runs once and only counts as done when it
finished: a call cancelled half-way leaves it to the next call (adopting a handler twice is a no-op).

### Readiness (strategy)

`wait_ready` merges strategy flows and takes the first signal, with the deadline on an injected `Clock` (unit tests
use a manual clock; nothing sleeps): output patterns (ready and failure, failure wins on the same line, earlier lines
win, unfinished lines count), the end of the process (always; output written before the end is checked once more, so a
ready line printed just before exiting still counts), and optionally a TCP port on the loopback address. The port has
no event, so that strategy probes every 250 ms on the clock (suspending, cancellable); it is used only when asked for.
Without `pattern` and `port`, common server start messages are the ready patterns (Spring Boot, Quarkus, Micronaut,
"listening on", "server started"); without `failPattern`, Spring Boot's "APPLICATION FAILED TO START". Cancelling the
MCP call cancels the wait.

## Starting without questions: the UI paths

The IDE's start entry points and what `mc_run` does instead (all from 263 bytecode):

| Where | UI | When | `mc_run` |
|---|---|---|---|
| `ExecutionManagerImpl.restartRunProfile` (Run button, `ExecutionUtil.restart`) | "stop and rerun?" (`userApprovesStopForSameTypeConfigurations`), incompatible configurations dialog | a non-parallel configuration (or an incompatible one) runs | never called. Rule: a configuration that does not allow parallel runs is not started while it runs (`CONFLICT`, names the handle; use `restart` or `stop`); with "Allow multiple instances" a second instance starts. |
| `ExecutionManagerImpl.executeConfiguration` (used by `ProgramRunnerUtil.executeConfigurationAsync`, also by the official tool) | `RunDialog.editConfiguration`; "configuration still incorrect, edit again?" (`Messages.showYesNoDialog`) | `canRunConfiguration` is false | never called. `LaunchChecks` runs `RunnerAndConfigurationSettings.checkSettings(executor)` in a (smart) read action: an error refuses the start with its message (`CONFLICT`), a warning does not stop it (as in the IDE). Untrusted project and a target that cannot run are refused too. |
| same, "Show this page" (`isEditBeforeRun`) | the configuration editor before every run | the user ticked it | refused (`CONFLICT`, "asks to show its settings before each run"). The user set the flag to look at the settings before each run, so a start that skips the editor would go against that choice. |
| Prompting macros while the command line is built (`PromptingMacro.promptUser`: `$Prompt$`, `$FilePrompt$`, password prompts, any macro of that type) | an input dialog per macro; the start waits for it (seen end to end with the IDE's own Run) | arguments, VM options, working directory, environment or any other stored setting contains one | refused (`UNSUPPORTED`, names the macros). Detection (`LaunchQuestions.promptingMacrosIn`): the names of all `PromptingMacro`s from `MacroManager.getMacros()`, searched as `$Name$` or `$Name:…$` in every attribute and text of the configuration's stored state (`RunConfiguration.writeExternal`). A configuration whose state cannot be written is not checked. |
| `ExecutionManagerImpl.compileAndRun` before-launch tasks | whatever the task provider shows (e.g. "Run another configuration" goes through `restartRunProfile`) | the configuration has before-launch tasks | only the Java plugin's build tasks `Make`, `MakeNoErrorCheck`, `MakeProject` (no UI in their bytecode) are allowed; a configuration with any other enabled task is refused (`UNSUPPORTED`, names the task). A failed build ends the start as `not_started`. |
| `HotSwapUIImpl.MyCompilationStatusListener` (any `ProjectTaskManager` build, e.g. the before-launch Build) | `RunHotswapDialog` "reload changed classes?", or the possible-hang `Messages` dialog | debug sessions run, classes changed, `RUN_HOTSWAP_AFTER_COMPILE` is Ask (default) | during an `mc_run` start a `HotSwapVetoableListener` (public `HotSwapUI.addListener`) declines the IDE's reload for builds of that configuration, so the IDE never asks; other debug sessions are reloaded only by `hotswap`. Settings are read, never changed. |
| `ExecutionUtil.handleExecutionError` | an error balloon (notification), not modal | launching failed (no JDK, process not created) | accepted: non-blocking. The cause becomes `not_started` with `reason`. |
| `RunContentManager.showRunContent` | the Run/Debug tool window shows a tab | every launch | accepted: a tool window tab, no new window, no question. |

The launch itself: `ExecutionEnvironmentBuilder.create(executor, settings).activeTarget().build(callback)`, then
`environment.assignNewExecutionId()` and `environment.runner.execute(environment)` on the EDT, which is what the IDE's
entry points do after their questions. The builder chooses the runner for the profile it builds: in a project that
delegates running to Gradle, the project's `ExecutionEnvironmentProvider` turns an Application configuration into a
generated Gradle run configuration with the Gradle runner (seen end to end; forcing the configuration's own runner made
the start fail). The process keeps the name and configuration the client asked for, so `configuration=Beacon` and
`restart` keep working.

Steps of `executeConfiguration`/`restartRunProfile` that `mc_run` skips, and why that is safe:

| Step | Why skipped |
|---|---|
| Stop running instances of the same configuration, stop incompatible ones (`restartRunProfile`) | replaced by the parallel-run rule (refuse) and by `restart`, which stops exactly the given process. Incompatible configurations (`CompatibilityAwareRunProfile`, in `execution.impl`) are not checked: such a start runs, as with the official tool. |
| `RunConfiguration.restartSingleton` | only used when stopping a running instance, which we refuse. |
| Edit-before-run and "still incorrect, edit again?" dialogs | replaced by the refusals above. |
| `ExecutionManager.isStarting` check (`ProgramRunnerUtil.executeConfigurationAsync`) | `@ApiStatus.Internal`; our starts are serialised per project by a mutex around the parallel-run check and the scheduling, and starts made in the IDE are seen from their schedule event as `starting`. |
| `ExecutionSafeMode.findUntrustedTargetFile` | `@ApiStatus.Internal`; replaced by refusing untrusted projects (`TrustedProjects.isProjectTrusted`). |
| `ExecutionEnvironmentListener.executionRequested` (`ExecutionManagerImpl` topic) | class of the `impl` package; it only notifies listeners that a run was requested. |
| `ExecutionTargetManager.doCanRun` with an error balloon | replaced by `ExecutionTargetManager.canRun` and an `UNSUPPORTED` answer. |
| Waiting for smart mode for configuration types that are not dumb-aware | done: the checks run in a smart read action for those types. |
| `assignNewExecutionId` | done (it was missing in the first version: every start had execution id 0). |

### A start that never reports back

`ExecutionManagerImpl` reports every start it hears of: `processStartScheduled` comes synchronously inside
`runner.execute`, and then always either `processStarting`/`processStarted` or `processNotStarted` (exceptions, a null
descriptor, failed or cancelled before-launch tasks, `EXECUTION_SKIP_RUN`). Two cases in 263 end without any of them:

- the runner never hands the start to the execution manager, e.g. `GenericProgramRunner.execute` returns silently when
  `environment.state` is null. Because the schedule event is synchronous, the gateway checks right after
  `runner.execute` returns: a start the execution manager never announced ends as `not_started` ("The runner did not
  start anything"). No timeout is needed.
- the runner returns a run content descriptor without a process handler: the execution manager then publishes nothing
  after `processStartScheduled`. The environment's `ProgramRunner.Callback.processStarted(descriptor)` sees it and ends
  the start as `not_started` ("The runner finished the start without a process").

Both are tested (`RunToolsetTest.testAStartTheRunnerNeverHandsOverEndsAsNotStarted` covers the first; the second has no
runner in the test IDE that produces it and is covered by reading). A later start of the configuration is not blocked.

### Stop

What the IDE's Stop does: `ProcessHandler.destroyProcess()` (for a Java process, SIGINT to the process tree first), or
`detachProcess()` when the handler detaches by default (attached processes). Then the wait for the end on the clock;
`force=true` kills afterwards (`KillableProcess.killProcess`). A process still in its before-launch phase cannot be
stopped yet (`CONFLICT`); there is no public way to cancel a scheduled start.

### Hot swap

`HotSwapUI.reloadChangedClasses` can ask (`RunHotswapDialog` when the setting is not "Always", the possible-hang
checkbox dialog `confirmPossibleHang`) and reports only success/failure, no reasons. `JvmHotSwapSession` runs the same
steps through public API instead:

1. Refuse when the session is paused and `DebuggerSettings.HOTSWAP_HANG_WARNING_ENABLED` is on (the IDE would ask);
   resume first. The setting is read, never changed.
2. Build like the IDE's "Reload Changed Classes" does (`HotSwapUIImpl.reloadChangedClasses` with compile:
   `ProjectTaskManager.run(context, createAllModulesBuildTask(true, project))`). `ProjectTaskManager` honours build
   delegation: a Gradle- or Maven-delegated project is built by that tool into the directories its JVM loads classes
   from (end to end: the Gradle fixture's `build/classes/java/main/runfix/Beacon.class` was rewritten, no `out/`
   directory exists). This build reaches `HotSwapUIImpl`'s own listener, which would reload (by default after asking);
   a `HotSwapVetoableListener` declines it for exactly our build's `ProjectTaskContext`, for the duration of the build.
   Errors: a JPS build publishes `CompilerTopics.COMPILATION_STATUS` with its `CompileContext`, so errors come with
   path, line and column (at most 50). A delegated build reports its errors only to the Build tool window, whose listener
   API (`AbstractViewManager.addListener`) is `@ApiStatus.Experimental`; then `ProjectTaskManager.Result` only says
   `hasErrors`, and the report has one error without location that points to the official `build_project` tool. As with
   any build in the IDE, a failed build may open the file of the first error in the editor (an editor tab, no window,
   no question).
3. `HotSwapManager.scanForModifiedClasses` and `reloadModifiedClasses` (public static) with a `HotSwapProgress` of our
   own that collects messages by `MessageCategory` instead of showing notifications. `ReloadClassesWorker` has no UI
   (only an `invokeLater` to refresh debugger views). Error messages are the JVM's reasons, e.g. "Operation not
   supported by VM: add method not implemented".
4. The hot-swap manager advances its "last reloaded" time even when the JVM refused the classes, so a second call would
   say "nothing to reload" while the old code still runs (seen end to end). Refused classes are therefore remembered on
   the debug process (`UserDataHolder`) and reported as `rejected` until a reload of them succeeds.
5. `restartOnFailure=true`: on `rejected` or `not_possible`, restart with the same executor. Never on compile errors.

The `RUN_HOTSWAP_AFTER_COMPILE` setting (ask/always/never) governs the IDE's automatic reload after a build; an
explicit reload ignores it, in the IDE ("Reload Changed Classes") and here.

## Limits and what is not supported

- Test results (counts, failed tests) are not in this step. Findings for that step: the public topic
  `SMTRunnerEventsListener.TEST_STATUS` (interface `@ApiStatus.OverrideOnly`, implementing it is allowed) and
  `SMTestProxy.SMRootTestProxy.getHandler()` (public) give the results per process handler; `SMTestProxy` has public
  `isPassed`, `isDefect`, `isIgnored`, `getErrorMessage`, `getStacktrace`, `getLocationUrl`. In 263 they live in the
  content module `intellij.platform.smRunner` (`plugins/platform-testRunner-plugin/lib/modules/intellij.platform.smRunner.jar`,
  next to `intellij.platform.testRunner`) of the bundled plugin `com.intellij.platform.testRunner`, which is marked
  `implementation-detail="true"`; the Java plugin's descriptors depend on it as `<module name="intellij.platform.testRunner"/>`.
  What I believe is needed: a v2 module dependency on `intellij.platform.smRunner` (and `intellij.platform.testRunner`)
  from a content module of ours, or an optional `<depends>` on `com.intellij.platform.testRunner` with its own config
  file, then a project listener on `TEST_STATUS` in that descriptor; which of the two the platform's class loading
  accepts for a v1-style descriptor must be tried in the sandbox (the Plugin Verifier checks class resolution).
- Only the Run and Debug executors start; processes of other executors (coverage, profiler) are listed, read and
  stopped.
- Delegated builds (Gradle, Maven): `hotswap` builds and reloads, but compile errors carry no file positions (see Hot
  swap). Processes run through Gradle have no `pid` (the handler is not an OS process handler), and `stop` ends them with
  the Gradle task's exit code (1), not the JVM's. The IDE's own Gradle build tasks (e.g. "Build mcp-e2e-gradle") are
  listed as processes too, because they run through the execution manager.
- Client regular expressions (`pattern`, `failPattern`): at most 500 characters, Java syntax, matched with `find`
  against each line, which is at most 8,000 characters (longer output is split into lines of that length). A match runs
  to completion: it is not cancellable in the middle, so a pathological expression can hold one call for as long as it
  needs on one line. Accepted, because lines are bounded and the caller controls the expression.
- A stop of a process that is still in its before-launch phase is refused.
- Configurations whose before-launch tasks are not build tasks are refused.
- Remote JVM debug configurations detach on `stop` (as the IDE does) and report `detaching`; not verified end to end.

## Test runtime

- Platform tests run a single-file Java program through a test-only configuration type and the platform's default Run
  runner (`ScriptConfigurations`). The light project outlives a test, so each test gets a fresh `ProcessTracker`
  (`replaceService`).
- Run tabs are not created in headless tests; the release on tab disposal is tested by disposing the descriptor taken
  from the execution event.
- Headless tests answer dialogs themselves and have no debugger runner for the test configuration type, so "no dialog"
  and hot swap are verified end to end only.

## End to end

`runIdeForMcpE2e` now gives the fixture a JDK: the fixture's `.idea/misc.xml` names `mcp-e2e-jdk`, and the task writes
that JDK into the **sandbox's** `options/jdk.table.xml` (the sandbox IDE's own runtime, which has `javac` and JDWP; one
`jrt://` root per module, as the IDE writes it). The owner's IDE settings are never touched. The fixture's
`.idea/compiler.xml` excludes the step-2 Kotlin fixture file from compilation (no Kotlin standard library in the
fixture). Fixture programs: `src/runfix/Beacon.java` (tick per second, `READY` after two seconds, answers on
127.0.0.1:64411, exits when stdin closes), `src/runfix/Crash.java` (exit code 3); shared configurations in `.run/`.

A second fixture, `src/mcpE2eFixtureGradle` (`-PmcpE2eFixture=gradle`), is a Gradle project with build and run delegated
to Gradle. The task writes its `.idea/gradle.xml` (an already unpacked local Gradle distribution, found under
`~/.gradle/wrapper/dists` or given as `-PmcpE2eGradleHome`; Gradle runs on the caller's `JAVA_HOME`, because Gradle sync
refused the sandbox JDK entry) and deletes the sandbox's cached model of the project, so that the IDE imports it afresh
on every start. No network is used. The JPS fixture has a third configuration, `Prompting`, whose program arguments are
`$Prompt$`.

Requests and responses are in the step-3 report. Every call returned without interaction, and the list of viewable
top-level windows of the private display was the same before and after each call.

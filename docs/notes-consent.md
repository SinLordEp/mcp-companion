# Consent: the user confirms launches by MCP clients (`mc_run`)

Date: 2026-11-17. IntelliJ IDEA 2026.3 (IU-263.7307.200). Why `mc_run start`, `restart` and a `hotswap` that restarts
ask the user first, how the official server does it, and what this plugin does.

## Step 0: how the official gate works (263)

Read from the bytecode of the bundled MCP Server plugin (`plugins/mcpserver-plugin/lib/modules/intellij.mcpserver.toolsets.jar`,
`intellij.mcpserver.jar`) and seen end to end in the sandbox.

- `execute_run_configuration` (`ExecutionToolset`) runs `Execution_utilKt.prepareAndExecuteRunConfiguration`. It first
  resolves the configuration (unknown name or code location: the tool fails without asking), clones it with the
  overrides, then calls `checkUserConfirmationIfNeeded(title, text, project)` with "MCP client wants to execute run
  configuration '{0}'" (plus the program arguments when there are any), and only after an Allow executes it through
  `ProgramRunnerUtil` (where an invalid configuration still opens the configuration editor).
- `checkUserConfirmationIfNeeded` reads the session's `AskCommandExecutionMode` from `McpCallInfo.mcpSessionOptions`
  (`com.intellij.mcpserver.impl.McpServerService.McpSessionOptions`): `ASK` always asks; `RESPECT_GLOBAL_SETTINGS`
  asks unless `McpServerSettings.enableBraveMode` is on; `DONT_ASK` never asks. A session without a token (every
  external client: SSE, Streamable HTTP, stdio proxy) gets `RESPECT_GLOBAL_SETTINGS` (`McpServerService.getSessionOptions`);
  `ASK`/`DONT_ASK` exist only for sessions the IDE's own agents open with `authorizedSession`.
- `askConfirmation` shows a `DialogWrapper(project, canBeParent = true)` (default modality: the whole IDE) with
  `withContext(Dispatchers.EDT) { show() }`: title "Confirm Command Execution", the question, a read-only text area with
  the command when there is one, a checkbox "Run shell commands or run configurations without confirmation (brave mode)"
  bound to `McpServerSettings::enableBraveMode` (applied with the dialog's OK only) and the comment "Note: You can
  configure 'Brave Mode' in settings."; OK is renamed Allow (default button), Cancel Deny. Deny throws
  `CommandExecutionRejectedException` ("User rejected command execution", an `McpExpectedError`).
- No time bound, and the modal `show()` does not react to the cancellation of the call: an unanswered dialog keeps the
  call open for as long as it stays open.
- The same gate is used by `execute_terminal_command` (`TerminalToolset`) and the IDE script tool (which is only offered
  in brave mode). `xdebug_start_debugger_session` (debugger plugin) does **not** ask: no other bundled plugin references
  the gate or brave mode.
- Brave mode lives in `McpServerSettings` (`@State(name = "McpServerSettings", storages = mcpServer.xml)`, application
  level, default off); the official settings page (Settings | AI | MCP Server, group "Commands execution") and the
  status bar popup ("Brave mode") edit it.

### Which parts are reachable by a third-party plugin

| What | Where | Usable? |
|---|---|---|
| Trigger the official dialog (`checkUserConfirmationIfNeeded`, `askConfirmation`) | `com.intellij.mcpserver.toolsets.util`, content module `intellij.mcpserver.toolsets` declared `visibility="internal"` | no |
| The session's mode (`McpCallInfo.mcpSessionOptions.commandExecutionMode`) | type `McpServerService.McpSessionOptions` / `AskCommandExecutionMode` in package `impl` | no (PLAN §3: never `impl`) |
| Brave mode (`McpServerSettings.getInstance().enableBraveMode`, read and write) | `com.intellij.mcpserver.settings.McpServerSettings`, a public interface in the module `intellij.mcpserver` (`visibility="public"`), not annotated `@ApiStatus.Internal`/`@Experimental` | **yes** |

Proof for the last row: a spike class that reads and writes `enableBraveMode` (and calls `AppIcon.requestAttention`)
was verified with `./gradlew verifyPlugin`: "Compatible", no internal or experimental API usage. The final build is
verified again (see the report of this change).

So (a) the official confirmation cannot be triggered, but (b) its setting can be read and written. This change
therefore asks with a dialog of its own, governed by the **same** setting: one switch decides for the official run and
terminal tools and for `mc_run`. The narrower, plugin-owned setting first planned was dropped: a user who turned brave
mode on expects run configurations to start without questions ("run shell commands or run configurations without
confirmation"), and one who left it off expects to be asked, whichever tool starts them.

### Why `docs/notes-step3.md` said "no consent gate"

It recorded that "the only caller of `checkUserConfirmationIfNeeded` is its own continuation". The search looked at the
classes of `ExecutionToolset` and of the gate's own coroutine; the call sits in
`Execution_utilKt.prepareAndExecuteRunConfiguration` (bytecode offset 728), the helper that `ExecutionToolset` calls, in
the same file as the gate. The step-3 end-to-end rounds did not see the dialog because none of them called the official
tool; step 3b did and saw it.

## Behaviour

- Asking actions: `start`; `restart`; `hotswap` with `restartOnFailure=true` when it restarts. `processes`, `status`,
  `output`, `wait_ready`, `tests`, `stop` and a `hotswap` without restart never ask.
- One question per call. A restart (also the hot-swap one) asks before anything is stopped; after Allow it stops the
  process and starts again without a second question.
- Order: the refusals that need no user come first, so nobody is asked about a launch that would be refused anyway:
  unknown or ambiguous configuration, wrong arguments, already running (`CONFLICT`), process still starting, then
  `ExecutionGateway.checkLaunch` (untrusted project, no runner for the executor, invalid configuration, run target,
  "Show this page", prompting macros, foreign before-launch tasks). `start` checks again after the answer (the
  configuration may have changed meanwhile).
- The question names the configuration, its type, the executor (Run/Debug, the IDE's own words), what is requested
  (start, restart, restart because the code could not be reloaded), and for a restart that the running process stops
  first. Buttons Allow (default, focused) and Deny; Escape and closing the window are Deny. The checkbox is the
  official one (same text): it turns brave mode on, and only together with Allow (`LaunchApproval` calls
  `LaunchConsent.stopAsking()` only for `Allowed(stopAsking = true)`).
- Deny: `DENIED`, "The user denied the request to start 'X' when the IDE asked. Nothing was started or stopped." Next
  step: do not retry on your own, ask the user. Nothing is scheduled, so `processes` shows no record. A denied hot-swap
  restart keeps the reload result in the message ("Nothing was reloaded: …").
- No answer within **120 s**: the dialog closes, `DENIED` "did not answer … within 120 s; the question was withdrawn",
  next step: tell the user the IDE is asking, retry when they are there.
- Cancelled call (MCP `notifications/cancelled`, or anything that cancels the tool's coroutine): the dialog closes,
  nothing starts, the start lock is released.
- Headless IDE: nobody can be asked: `UNSUPPORTED` with the reason, nothing starts (unless brave mode is on).

### Decisions

- **`DENIED` is a new `ErrorCode`.** The existing codes say what is wrong with the request or the code
  (`INVALID_ARGUMENT`, `NOT_FOUND`, `AMBIGUOUS`, `UNSUPPORTED`, `CONFLICT`); an agent reacts to them by changing the
  request or waiting. A refusal by the user needs the opposite reaction (stop and ask the user), so it must be
  distinguishable by code, not only by text. The unanswered question uses `DENIED` too: the user did not allow it, and
  the reaction is the same (talk to the user, no blind retry). "Nobody can be asked" (headless) is `UNSUPPORTED`: no
  user decided anything; the IDE cannot do it.
- **A time bound of 120 s, unlike the official dialog.** The official call hangs until the dialog is answered (and
  closing the client's connection does not end it). Many clients do not cancel tool calls, so an unbounded question can
  hold the agent and keep the start lock (and every later start of the project) blocked indefinitely. 120 s leaves the
  user time to read and decide; a user who comes back later gets the next question.
- **The question is asked under the per-project start lock.** Concurrent launches are asked one after the other (never
  two dialogs at once), and a later start sees the process of an earlier one: two starts of a configuration without
  parallel runs ask once and start once (the second gets `CONFLICT` without a question). Read-only actions and `stop`
  never take the lock, so they answer while a question is open. A pending question delays only other launches of the
  same project (at most 120 s each).
- **Dialog ownership and visibility.** `DialogWrapper(project, parent = the project's frame, canBeParent, IDE modality)`:
  the dialog is a transient of the project's frame (also when another application or another project has the focus).
  A minimised frame is restored first (otherwise the modal dialog would block the IDE invisibly), and
  `AppIcon.requestAttention(project, critical)` is called when the IDE is not the active application. IDE-wide
  modality as in the official dialog.
- **Cancellation.** The dialog is created and shown on the EDT; a sibling coroutine closes it (`close(CANCEL)`, on the
  EDT with `ModalityState.any()`, which the modal loop runs) when the question is withdrawn. `showAndGet()` then
  returns and the cancellation propagates.

## Design (PLAN §5.1)

- `run/LaunchConsent.kt` (pure): gateway `LaunchConsent` (`isRequired`, `ask`, `stopAsking`), `LaunchRequest`
  (configuration, executor, `LaunchPurpose`, `stopsRunning`), sealed `ConsentAnswer` (`Allowed(stopAsking)`, `Denied`,
  `Unavailable(reason)`).
- `run/LaunchApproval.kt` (pure use-case part): the policy (skip when not required, time bound on the injected `Clock`,
  turn confirmation off only after Allow, map answers to `ToolResult`).
- `run/ProcessLifecycle`: `start` = lock { not running → `checkLaunch` → approve → `execution.start` }; `restart` asks
  under the lock, then stops and starts with only the parallel-run rule checked again. `HotSwapProcess` passes
  `HOTSWAP_RESTART`.
- `run/ide/IdeLaunchConsent` (dialog lifecycle, headless, frame, attention) and `run/ide/LaunchConsentDialog`
  (`DialogWrapper` + `LaunchConsentPanel`, Kotlin UI DSL).
- The setting: `common/consent/ConfirmationSetting` (pure interface) and `common/consent/ide/BraveModeSetting` (the
  only class that touches `McpServerSettings`; a Konsist rule pins that). It is in `common` because both the `run`
  gateway and the settings page use it, and `settings` must stay a leaf ("nothing depends on the settings page"); no
  layering rule had to be relaxed. A second Konsist rule keeps the `run` use cases off `common.consent` (they only know
  `LaunchConsent`).
- Settings | Tools | MCP Companion: a group "Run configurations started by MCP clients" with the official checkbox text,
  bound (`bindSelected`) to `ConfirmationSetting`, so apply, reset and `isModified` come from the panel; a comment says
  that it is the MCP Server's brave mode and that it also covers shell commands. English and Chinese bundles.
- `runIdeForMcpE2e -PmcpE2eLaunchConfirmation=ask|skip` (default `skip`) writes `enableBraveMode` into the sandbox's
  `options/mcpServer.xml` (only that option; the file is merged, not replaced).

## End to end

Private Xvfb `:61` at 1440x900, sandbox `runIdeForMcpE2e -PmcpE2eAwtToolkit=XToolkit -PmcpE2ePort=64477
-PmcpE2eLaunchConfirmation=ask`, JPS fixture, a Python Streamable HTTP client; answers given with xdotool (keys and
clicks) on the private display. Rounds in English and in Chinese (`LocalizationStateService` zh-CN in the sandbox's
`ide.general.xml`), part of them under openbox (window manager: title bars, minimise, focus). Raw exchanges and
screenshots: see the report of this change.

| Case | Result |
|---|---|
| start Beacon, Allow (Enter) | dialog "MCP client wants to start run configuration 'Beacon'", Type Application, Executor Run; process scheduled right after the answer |
| `processes` while the dialog is open | answered at once |
| start of a running configuration, a prompting one, an unknown name | `CONFLICT`, `UNSUPPORTED`, `NOT_FOUND`, no dialog (window list unchanged) |
| start Crash with debug, Escape | `DENIED`, nothing started |
| restart, checkbox ticked, Allow (mouse) | one question ("…restart…", "Its running process is stopped first."); the old process ended only after Allow; the next start ran without a question; the settings page showed the box ticked |
| untick on our settings page, Apply/OK | the next start asked again; the sandbox's `mcpServer.xml` went back to the default |
| official `execute_run_configuration` with brave mode off | its own "Confirm Command Execution" dialog (Deny: "User rejected command execution"); with brave mode turned on on **our** page neither tool asked |
| `notifications/cancelled` while the dialog is open | dialog closed, no response (as the server does for cancelled requests), nothing started |
| client closes the connection while the dialog is open | the server keeps the call: dialog stays until the 120 s bound, then closes; nothing started |
| no answer, client waiting | `DENIED` after 120.02 s, "did not answer" |
| two concurrent starts of Crash | one question; after Allow #1 started, #2 `CONFLICT` without a question |
| concurrent starts of Crash and SlowTest | two questions one after the other (Allow, Deny) |
| `hotswap` of a run (not debug) process without restart | no dialog, `not_possible` |
| `hotswap restartOnFailure` | "…restart … because the changed code cannot be reloaded"; Allow restarted it; Deny (JVM refused an added method, debug session) kept the process |
| minimised frame (openbox) | frame restored from Iconic to Normal, dialog on top of it with its title bar |
| another application focused (openbox) | dialog is a transient of the project frame, not raised over the focused window, `_NET_WM_STATE_DEMANDS_ATTENTION` set |
| closing the dialog with its title-bar button | `DENIED` |
| Chinese | dialog and settings texts in Chinese; executor and type names from the IDE (调试, 应用程序) |

## Limits and what is not proven

- JetBrains' own agents can open sessions with `ASK` or `DONT_ASK`; the mode is in `impl` and cannot be read, so `mc_run`
  follows brave mode in those sessions too (an `ASK` session with brave mode on is asked by the official tool, not by
  `mc_run`). External clients always have the mode that follows brave mode.
- Turning brave mode on (in our dialog or page) also lets the bundled terminal and IDE script tools run without
  confirmation; both texts say so. That is the official coupling.
- `xdebug_start_debugger_session` starts configurations with the debugger without asking; `mc_run start
  executor=debug` asks. The official server is inconsistent there; we ask for both.
- `AppIcon.requestAttention` was called, but its effect is not separable from the window manager's own reaction: under
  openbox the dialog got `_NET_WM_STATE_DEMANDS_ATTENTION`, which openbox also sets when it refuses focus. Not tested on
  Windows, macOS or a GNOME/KDE session; restoring a minimised frame was tested with openbox only.
- Two open projects (a question for a project whose frame is behind another project's frame) were not tested; the
  parent is the project's frame by construction.
- While the modal dialog is open, other EDT work that waits for a non-modal state (e.g. a hot swap build of another
  call, the first-call adoption of running processes) waits too; the same holds for the official dialog.
- A restart releases the start lock between the question and the stop. If another call starts the same configuration
  in that window, the restart stops its process and then gets `CONFLICT` for the new start (as before this change).
  Not reproduced; holding the lock across the stop would block every other launch for up to the stop timeout.
- The dialog itself is not shown in the headless platform tests; its texts are tested on its panel, the rest end to
  end.
- Whether a client's own request timeout sends `notifications/cancelled` depends on the client; a client that just
  drops the connection leaves the question open until the 120 s bound (seen end to end).

## `mc_sync` and `mc_safe_delete`: official counterparts (not changed here)

- No official tool syncs or re-imports Gradle/Maven projects (`docs/notes-step4.md`). The closest, `build_project`
  (`AnalysisToolset`), runs Gradle or Maven builds in delegated projects, i.e. executes build scripts, and does **not**
  ask: only `execute_run_configuration`, `execute_terminal_command` and the IDE script tool reach the gate. A sync run
  through `execute_terminal_command` (`./gradlew …`) would ask.
- No official safe delete exists. Code changes and deletions by official tools (`apply_patch` with Delete/Update,
  `rename_refactoring`, `create_new_file`, `reformat_file`, `optimize_imports`) do not ask.
- So neither has an official counterpart that asks; if a gate is wanted there it would be a policy of our own.

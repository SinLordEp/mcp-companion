# Step 9: `mc_run start_at` and `mc_quick_fix`, platform notes

Date: 2026-11-19. IntelliJ IDEA 2026.3 (IU-263.7307.200). Goals: run or debug code without a run configuration made
beforehand (a test method, test class, test directory or main function, like the Run gutter icon), with everything
`mc_run` already offers for the process; and list and apply the IDE's quick fixes for the problems of a file. Both are
part of 0.3.0.

## Step 0: what the bundled server offers

Every `@McpTool` of the IDE's `mcpToolset` extensions was listed with its description and parameters (a throwaway
platform test that reflected over the extension point), and the relevant classes were read with `javap` and the IDE's
Fernflower. Pinned by `RunAtCodeAndQuickFixContractTest`.

### Running code from a location

| Tool | What it does | Consequence |
|---|---|---|
| `execute_run_configuration` | A saved configuration by name, or a temporary one from `filePath` + `line` (`Execution_utilKt.resolveRunConfigurationFromCodeLocation`: `new ConfigurationContext(psiElement).getConfigurationsFromContext()`, the first one, `RunManager.setTemporaryConfiguration`), Run executor only, through `ProgramRunnerUtil.executeConfigurationAsync`. Waits for the exit or returns once started; output snapshot, exit code, a temp file with the output. | No handle: nothing can later stop the process, wait for readiness, read its output by cursor or read test results. No directory, no qualified name, no debug, no list of candidates (the first one wins silently). |
| `xdebug_start_debugger_session` | The same location resolution with the debugger; a debugger session id for the `xdebug_*` tools. | Debug only, no test results, no stop of run processes. |
| `coverage_run_configuration` | The same with the coverage executor. | Out of scope. |
| `get_run_configurations(filePath)` | Lists a file's run points (lines with a Run gutter icon). | Complementary; `start_at`'s NOT_FOUND points to it. |
| `get_symbol_info`, `xdebug_run_to_line` | Also take `filePath` + `line`, but run nothing new. | - |

So the official tools overlap with `start_at` only in "start a temporary configuration from a line". What makes
`start_at` worth having is that its process is an ordinary `mc_run` process: `tests`, `wait_ready`, `output`, `stop`,
`restart`, debug. Not a duplicate: no catalog entry, no step aside.

### Quick fixes

| Tool | What it does |
|---|---|
| `get_file_problems`, `lint_files` | Problems of files (severity, description, line, column, inspection id); `lint_files` runs `DaemonCodeAnalyzerImpl.runMainPasses`. No fixes in the output. |
| `optimize_imports` | Removes unused imports and orders them; "never adds missing imports". |
| `*_inspection_kts*` (Qodana) | Inspection scripts written by the agent. |

Nothing lists or applies the IDE's quick fixes. `mc_quick_fix` is new.

## Running code: public API and what is skipped

What the IDE's gutter action (`RunContextAction`/`ExecutorAction`) does, and what `start_at` does:

| IDE step | API | `start_at` |
|---|---|---|
| Find the configurations for the element at the caret | `new ConfigurationContext(PsiElement)` and `getConfigurationsFromContext()` (public, not annotated; the constructor is the one the official tool uses). It asks every `RunConfigurationProducer`, keeps the preferred ones (`ConfigurationFromContext.COMPARATOR`, `@Internal`, applied inside), and returns an existing matching configuration instead of a new one (`findOrCreateConfigurationFromContext`). | Used as is, in a smart read action off the EDT. The element is the first non-blank character of `line` (or `column`), the file without `line`, the `PsiDirectory` for a directory, or the declaration of a `qualifiedName` (the existing qualified-name locators). Library, SDK and non-project files are refused. |
| Several configurations | a popup to choose | `AMBIGUOUS` with every candidate (name, type id, type name, existing); `type` (and `configuration`, the name) picks one. |
| `RunManager.setTemporaryConfiguration` | adds a temporary configuration and selects it in the toolbar | `settings.isTemporary = true; RunManager.addConfiguration(settings)` for a new one: the IDE's rules for temporary configurations apply (kept in the workspace, not shared, the oldest dropped beyond the limit, "Save configuration" to keep it), but the user's toolbar selection is not changed. An existing configuration (saved or temporary) is run as it is. |
| `ConfigurationFromContext.onFirstRun` | Producers may ask here: `AbstractInClassConfigurationProducer` (JUnit, TestNG) and the Gradle test producers show `InheritorChooser` (which subclass of an abstract test class), the Gradle producers also `TestTasksChooser` (which of several test tasks of one source set); Kotlin's JUnit producer runs a modal progress to detect the framework. | Skipped. The case where that changes what runs is refused before anything happens: a `CodeRunGuard` (extension point; Java's `JvmAbstractTestGuard` in `mcp-companion-java.xml`) refuses tests declared in an abstract class (`UNSUPPORTED`, "pass a concrete subclass"). Several Gradle test tasks: the first one, which the producer already wrote into the configuration. |
| Launch | `ProgramRunnerUtil.executeConfigurationAsync` | `mc_run start`'s own launch: the checks without user, the user's confirmation, `ExecutionEnvironmentBuilder`, the process tracker. |

The consent rules of `start` apply unchanged: brave mode decides, one question per call after the checks that need
no user. The question names the code (`Code: testfix.CalculatorTest`) and says when a temporary configuration is made
for it ("A temporary run configuration is created for it."). When the launch does not happen (denied, unanswered,
refused by a check, headless), the temporary configuration made for it is removed again; an existing one never is.
`processes` and `status` report `temporary=true` for processes of temporary configurations.

Producers seen end to end: JUnit (method, class, a directory matched the saved `AllTests`), Application (Java `main`),
Kotlin (`JetRunConfigurationType`, top-level `main`), Gradle (`GradleRunConfiguration` for method and class with
"Run tests using: Gradle", a directory matched the saved `LibTests`), JUnit in a Maven project, with the IDE's runner
and with "Delegate IDE build/run actions to Maven" (the latter runs the tests through Maven and still gives a test
tree: `tests` worked).

## Quick fixes: public API and what is refused

1. **The problems and their fixes.** `MainPassesRunner(project, title, null).runMainPasses(files, WEAK_WARNING)`, the
   runner behind `CodeSmellDetector` and the official `lint_files`, on a background thread without a read lock under a
   progress indicator of ours (as `mc_diagnostics` runs the detector), with external annotators paused for the file
   (`ExternalAnnotatorPause`, now in `common/analysis/ide`). It returns `HighlightInfo`s with the fixes the passes
   registered. `MainPassesRunner` and `HighlightInfo` sit in `com.intellij.codeInsight.daemon.impl` but neither class
   is annotated; the members used (`runMainPasses`, `getSeverity`, `getDescription`, `getStartOffset`,
   `getEndOffset`, `findRegisteredQuickFix`, `IntentionActionDescriptor.getAction`) are not annotated either (checked
   with `javap -v`; `getFixRange`, `hasLazyQuickFixes`, `isFromInspection` and others are `@Internal` and not used).
   The Plugin Verifier agrees (see the end). The step-7 decision against `impl` classes was about the MCP server's
   `impl` package and the official tool's internals; here there is no public alternative (`CodeSmellDetector` drops
   the fixes, `InspectionEngine` misses the language's own errors such as unresolved symbols).
2. **Fixes a language registers lazily.** Some fixes are computed only when Alt+Enter is pressed
   (`HighlightInfo.Builder.registerLazyFixes`, computed by `LazyQuickFixUpdater`, `@Internal @Experimental`), so a
   highlight made without an editor lacks them. Two public substitutes:
   - the platform's `unresolvedReferenceQuickFixProvider` extensions: each provider's public `registerFixes` for an
     unresolved reference at the problem (the loop the `@Internal` `registerReferenceFixes` runs), e.g. Maven's "Add
     Maven dependency";
   - `DeferredFixProvider` (our extension point); Java's `JavaDeferredFixes` creates "Import class" like the Java
     plugin's `AdditionalJavaErrorFixProvider.registerLazyFixes` (its interface `JavaErrorFixProvider` is
     `@Experimental`): `new ImportClassFix(reference).getFallbackModCommandAction()`. `ImportClassFix` and the method
     (declared in `ImportClassFixBase`) are public and not annotated; the method implements one of the
     `@Experimental` interface `IntentionActionWithModCommandFallback`, which we do not reference. The Verifier
     reports no experimental usage for it.
3. **Which fixes can run without the user.** Only fixes in the `ModCommand` form (`IntentionAction.asModCommandAction()`
   not null). Such a fix is offered when `getPresentation(ActionContext)` is not null at the problem's offset (caret
   there, no selection, as Alt+Enter would see it), and `perform(context)` computes its change as data, on a copy,
   changing nothing. `ModCommandChecks` walks the steps (`ModCommand.unpack()`): text changes and deletions of
   writable project files pass, as do steps an editor would only show afterwards (navigate, highlight, in-place rename,
   Tab targets, optional templates), which the IDE's batch execution skips. Refused with the reason: a choice inside a
   choice, a non-optional live template, conflicts, an options dialog, an error message, clipboard, browser, new or
   moved files (their target type `FutureVirtualFile` is `@Experimental`), anything unknown (the `@Experimental` step
   kinds `ModUpdateReferences`, `ModLaunchEditorAction`, `ModUpdateSystemOptions` are not referenced and fall here).
   A fix whose change is a choice (`ModChooseAction`, e.g. "Import class" with several classes, "Create type
   parameter") is listed once per option (`3.1`, `3.2`, ...), so the agent makes the choice the user would.
4. **Older fixes** (`IntentionAction.invoke(project, editor, file)`) are listed when `isAvailable(project, null,
   file)` is true or fails without an editor, with `applicable=false` and "works only in an open editor": they may
   open dialogs, popups or templates, which cannot be known beforehand (`ImplementMethodsFix` does nothing without an
   editor and opens the member chooser with one; `CreateClassFromUsageFix` needs the editor even for `isAvailable`).
   Applying one returns `UNSUPPORTED`. No editor is opened for them.
5. **Applying.** The ModCommand is computed again for the current content in a smart read action, checked again,
   then executed on the EDT with `ModCommandExecutor.getInstance().executeInBatch(context, command)` (the path of Code
   Cleanup; it compares each file's text with the one the change was computed for and aborts otherwise) inside
   `UndoableCommand` (`common/edits/psi`, extracted from step 8's refactoring runner and used by both): one command
   named "Quick Fix: <name>" in the UI language, marked global, so one Edit | Undo reverts it from anywhere.
   `changedFiles` come from the PSI change events of the command and the text steps.

### Ids and staleness

`<line>:<column>/<position>-<check>` (e.g. `12:9/2-3fa1b2c4`, `15:19/3.2-6b2ae428`): the problem's start, the fix's
place in the problem's list (1-based; `n.m` for an option), and a CRC-32 over the file's content stamp (length and
CRC-32 of the text), the problem's range and message, the position and the name. The same content gives the same ids
(tested), any edit of the file gives other ones. `apply` analyses the file again and looks the id up; no match:
`CONFLICT` "the file changed since action=list, or the problem is gone", nothing changed. The document's modification
stamp is checked again right before the execution.

## Tool surface (decided)

`mc_run action=start_at`: `path` (file or directory) with optional `line` and `column`, or `qualifiedName` (class or
`Class#member`); `executor` run|debug; `type` and `configuration` pick among several candidates. Refusals:
`INVALID_ARGUMENT` (both or none of path/qualifiedName, `column` without `line`, numbers below 1, a line outside the
file, `line` for a directory, library or non-project code), `NOT_FOUND` (no file, no class, nothing to run there),
`AMBIGUOUS` (several candidates), `UNSUPPORTED` (the IDE would ask on the first run; nobody can confirm in a headless
IDE), then everything `start` refuses (`CONFLICT` while a non-parallel configuration runs, invalid configuration,
untrusted project, `DENIED`).

`mc_quick_fix` (own toolset `quick_fix`, settings row "Quick Fix"/"快速修复", `mc_status`, step-aside catalog
like every toolset): `action` list (default) or apply; `path`; list: `line`, `endLine` (problems touching the
range), `minSeverity` warning (default: weak warnings, warnings, errors) or error, `limit` (1-100, default 20; fixes are
computed for the page only), `offset`; apply: `fix`. Output: `path`, `problemCount`, `problems` (line, column, endLine,
endColumn, severity, message, lineText, fixes: id, name, applicable, reason), `hasMore`, `nextOffset`; after apply
`applied`, the fixed problem and `changedFiles`. Hints: not read-only, not destructive, closed world. No consent
question (like the refactoring tools: project files only, one undoable command).

## Design

- `run/`: `CodeLocation`, `CodeConfiguration`, gateway `CodeRunGateway` (`CodeRuns.kt`), use case `StartAtCode`;
  `LaunchRequest.origin` (`LaunchOrigin`: location text, temporary); `ConfigurationRef.temporary`.
  `run/ide/IdeCodeRunGateway` (the only user of `ConfigurationContext`; Konsist rule), extension point
  `CodeRunGuard`; `run/java/JvmAbstractTestGuard`.
- `quickfix/` (pure): arguments, `FixId`, `QuickFixGateway` and its models, `ListQuickFixes`, `ApplyQuickFix`,
  `QuickFixControl`. `quickfix/psi`: `FileHighlighting` (the only user of `MainPassesRunner`/`HighlightInfo`, with the
  rest of `quickfix.psi`; Konsist rule), `FixCandidates`, `FixDescriptions`, `ModCommandChecks`,
  `PsiQuickFixGateway`, extension point `DeferredFixProvider`, `quickFixControlFor`. `quickfix/java/JavaDeferredFixes`.
- `common/edits/psi`: `UndoableCommand` and `ChangedFilesRecorder` (moved from `common/refactoring/psi`).
  `common/analysis/ide`: `ExternalAnnotatorPause` (moved from `diagnostics/ide`).
- `toolsets/quickfix`: `QuickFixToolset` and its input/output models.
- Konsist: `quickfix` is a layered feature; `CodeActionLayersTest` pins the rules above and the Java APIs of the Java
  supports.

## End to end

Sandbox `runIdeForMcpE2e -PmcpE2eAwtToolkit=XToolkit` on a private Xvfb `:89` (1440x900, openbox), a Python
Streamable-HTTP client on 127.0.0.1:64399. New fixture parts: module `quickfix` (`Broken.java`, `Circle.java`),
`testfix/test/abstractfix` (an abstract test class and two subclasses), `testfix/test/multifix/RunnableTest` (tests and
a `main`), module `kotlinfix` (a Kotlin `main`; the sandbox task copies the Kotlin plugin's `kotlin-stdlib.jar`). The
step-8 file `src/refac/OrderReport.kt` is now excluded from compilation like `src/rules/Settings.kt`: it made every
build of the `fixture` module fail ("Cannot access built-in declaration 'kotlin.String'"), so `Crash` and `Beacon` did
not start since step 8. Requests and responses: `build/e2e-step9/*.json`; screenshots: `build/e2e-step9/screenshots/`
(both under the worktree's `build/`, not committed).

| Call | Result |
|---|---|
| JPS, `start_at testfix/test/testfix/CalculatorTest.java:12` (`adds`) | JUnit `CalculatorTest.adds`, `temporary=true`; `tests`: passed 1/1 |
| `start_at qualifiedName=testfix.CalculatorTest` | JUnit `CalculatorTest`, temporary; `tests`: failed, 5 total, 2 passed, 2 failed (expected/actual), 1 ignored |
| `start_at path=testfix/test/testfix` | the saved `AllTests` (existing, not temporary) |
| `start_at src/runfix/Crash.java:5` / `qualifiedName=runfix.Crash executor=debug` | the saved `Crash`, exit code 3, run and debug |
| `start_at kotlinfix/src/kotlinfix/Hello.kt:4` | `HelloKt` (`JetRunConfigurationType`), temporary, exit 0, output "hello from Kotlin" |
| `start_at testfix/test/multifix/RunnableTest.java:8` (class line) | `AMBIGUOUS`: `'RunnableTest' (type Application)`, `'RunnableTest' (type JUnit)`; with `type=JUnit` passed 1/1; line 11 (in `main`): Application only, output "main of RunnableTest" |
| `start_at qualifiedName=abstractfix.ShapeContractTest#hasArea` | `UNSUPPORTED`, abstract class, "pass a concrete subclass" |
| `start_at quickfix/src/fixes/Shape.java:3` | `NOT_FOUND`, points to `get_run_configurations(filePath)` |
| Gradle, `start_at lib/src/test/java/testfix/CalculatorTest.java:12` / `qualifiedName=testfix.CalculatorTest` | `GradleRunConfiguration`, temporary; `tests` from the Gradle test task: 1/1 passed; 5 total, 2 failed, 1 ignored |
| Gradle, directory `lib/src/test/java/testfix` / `src/main/java/runfix/Beacon.java:16` | the saved `LibTests` (10 tests) / the saved `Beacon` run through Gradle, `READY`, stopped |
| Maven (IDE runner) / Maven (`-PmcpE2eMavenBuild=maven`) `start_at qualifiedName=testfix.CalculatorTest` | JUnit, temporary; `tests` 5 total, 2 failed, 1 ignored in both (the delegated run goes through Maven) |
| brave mode off, `start_at qualifiedName=testfix.CalculatorTest`, Escape | `DENIED`; `get_run_configurations` afterwards lists no `CalculatorTest` (the temporary configuration was removed) |
| brave mode off, `qualifiedName=testfix.SquareTest`, Enter | started, `tests`: 3 total, 1 failed |
| brave mode off, directory (existing `AllTests`) | question without the "temporary" line; Escape: `DENIED` |
| `mc_quick_fix list quickfix/src/fixes/Broken.java` | 13 problems; e.g. "Cannot resolve symbol 'Date'": Create type parameter (option 1.1), Create Class from Usage (editor only), Import class: java.util.Date (3.1), Import class: java.sql.Date (3.2); "Incompatible types": Wrap using 'Integer.parseInt()', Change field type and Migrate type (editor only) |
| same with `line=8 minSeverity=error`, with `limit=2 offset=2` | 1 problem; a page with `hasMore`, `nextOffset` |
| apply Import class (Widget, one class), Import class: java.util.Date (choice), Import class: java.util.List, Add exception to method signature, Wrap using `Integer.parseInt()`, Make 'Circle' abstract | applied, `changedFiles` the file; imports, `throws IOException`, `abstract class Circle` in the file |
| apply an id listed before another fix changed the file | `CONFLICT`, nothing changed |
| apply Implement methods / Create Class from New | `UNSUPPORTED`, "works only in an open editor ...", nothing changed |
| a class inside `junit-4.13.2.jar`, a missing file, a malformed id | `INVALID_ARGUMENT`, `NOT_FOUND`, `INVALID_ARGUMENT` |
| `mc_diagnostics` of both files afterwards | 1 error left ("Cannot resolve symbol 'Missing'") |

No dialog, popup, editor tab or window appeared during the `mc_quick_fix` and `start_at` rounds with brave mode on
(list of top-level windows the same before and after each round). Ctrl+Z in the project view asked "Undo Quick Fix:
Make 'Circle' abstract?" (`undo-quick-fix-en.png`) and reverted the file; in Chinese "撤消快速修复：将 'Circle' 设为
abstract?" (`undo-quick-fix-zh.png`). Confirmation dialog with the new lines: `consent-start-at-temporary-en.png`,
`consent-start-at-existing-en.png`, `-zh` variants. Settings page with the new row: `settings-en-1440x900.png`,
`settings-zh-1440x900.png` (13 rows, no scrolling, no cut text).

## Limits and what is not proven

- `start_at` skips `onFirstRun`: tests of an abstract Java class are refused; Kotlin abstract test classes are not
  checked (Kotlin PSI) and would run the abstract class; with several Gradle test tasks for one source set the first
  one runs instead of the IDE's popup; Kotlin multiplatform target choice is not offered. Not seen: TestNG, JUnit 5,
  Kotlin tests, other languages' producers.
- The new configuration is not selected in the toolbar (the IDE's gutter action would select it). In a sandbox run in
  Chinese the toolbar showed `AllTests`, which an earlier round in that session had started; whether the IDE selects
  a configuration it runs is the IDE's own behaviour and was not investigated further.
- A temporary configuration may be dropped by the IDE when newer temporary ones exceed its limit; `restart` of a
  process whose configuration was dropped answers `NOT_FOUND`. Two concurrent `start_at` calls for the same new
  location can both add a temporary configuration with the same name; the run manager keeps one.
- `mc_quick_fix` applies only `ModCommand` fixes; many fixes are still in the older form (seen: Implement methods,
  Create Class from Usage/New, Change field type, Migrate type, Safe delete, Add dependency on module) and are listed
  as needing the user. Lazily registered fixes other than Java's "Import class" and the unresolved-reference
  providers are missing (e.g. Java's static-import fixes, Kotlin's lazy fixes); Kotlin files were not tested.
  External annotators are not run, so their problems and fixes are not listed. Fixes that create, move or delete
  files are refused or limited (deletion only of writable project files). Messages and fix names are in the IDE's
  language (seen in Chinese), so ids differ between UI languages.
- The fix order is the order in which the IDE registers the fixes, not Alt+Enter's sorted order.
- Not run: Windows, macOS, large files, files open with unsaved changes in an editor while a fix is applied (the
  documents are committed first and the stamp check covers edits during the call).

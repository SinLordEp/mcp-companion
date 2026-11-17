# Step 3b: test results (`mc_run tests`), platform notes

Date: 2026-11-17. IntelliJ IDEA 2026.3 (IU-263.7307.200). Findings made while building the `tests` action of `mc_run`.

## What the bundled server already offers (263)

The sandbox's `tools/list` has 63 tools; none reports test results. `execute_run_configuration` returns the console
text of a run (and, unlike what `docs/notes-step3.md` recorded, it opened a modal "MCP client wants to execute run
configuration 'AllTests'" confirmation with Allow/Deny in this session; see "End to end"), `coverage_run_configuration`
returns coverage. So `tests` duplicates nothing and the equivalence catalog stays empty.

## Step 0: the dependency declaration

The API is in the content module `intellij.platform.smRunner` (`plugins/platform-testRunner-plugin/lib/modules/
intellij.platform.smRunner.jar`) of the bundled plugin `com.intellij.platform.testRunner`
(`implementation-detail="true"`; both its modules `intellij.platform.testRunner` and `intellij.platform.smRunner` are
`loading="required"` and `visibility="public"`). `TestStateInfo.Magnitude` lives in core
(`lib/intellij.platform.execution.impl.jar`).

How the IDE's own plugins declare it: bundled plugins, also those with a v1-style main descriptor (TestNG, Karma,
NodeJS), list `<module name="intellij.platform.smRunner"/>` and `<module name="intellij.platform.testRunner"/>` in a
`<dependencies>` element, next to their `<depends>`; that makes the dependency mandatory. Third-party plugins could not
be inspected offline.

| Attempt | Declaration | Compile (IntelliJ Platform Gradle Plugin 2.20.1) | `verifyPlugin` | Sandbox |
|---|---|---|---|---|
| A (kept) | `<depends optional="true" config-file="mcp-companion-test-runner.xml">com.intellij.platform.testRunner</depends>`; the extension and nothing else in that file | `bundledPlugin("com.intellij.platform.testRunner")` puts the content module jars on the compile classpath | `Compatible`, no warning, no internal/experimental usage | plugin loads, no class loading error in `idea.log`, the listener receives events (JPS, Gradle) |
| B (control) | no declaration at all, the extension registered in `plugin.xml` | same | `Compatible` (the verifier resolved the classes anyway) | also works: no error, results of a JUnit run returned |

Why B works: `ProductPluginInitContextKt.externalNonBundledPluginCompatibilityDependencies` (core.impl, 263) adds an
implicit dependency of every non-bundled plugin on a list of modules that were split out of the platform, among them
`intellij.platform.smRunner` (also `intellij.libraries.groovy`, `intellij.platform.structureView`, `todo`,
`bookmarks`). That is a compatibility shim of the platform, not a declaration; relying on it would also break the plugin
in an IDE without the test runner plugin (class loading errors instead of `UNSUPPORTED`). A stays: it states the real
dependency and keeps it optional. The mandatory v2 form (`<dependencies><module …/>` in the main descriptor) was not
tried: it would make the whole plugin require the test runner.

The first verifier run with the feature found `TestStateInfo.Magnitude` reported as internal API ("12 usages of
internal API"; it would also have told an error from an assertion failure). It is not used: outcomes come from the
proxies' public predicates (below). Final verifier line: see "Checks".

## Design

Packages (Konsist: `run.tests` is platform-free; `smrunner` is a new optional gateway segment; the test framework API
`com.intellij.execution.testframework` only in `smrunner`):

- `run/tests/` (use case, pure): `ReportTests` (the `tests` handler, its own parameter set), `TestModels` (`TestCase`,
  `TestFailure`, `TestRun`, `TestOutcome`, `TestFilter`, `SourcePosition`), `TestSummary` (`TestCounts`, `TestVerdict`),
  `TestSummaries` (the compact summary of `processes` and `status`), `TestReport` (`Results`/`Unavailable`,
  `BoundedFailure`), the gateway `TestResultsGateway` with `TestResults` and `TestRunReading`.
- `run/ide/`: the extension point `testResultsSupport` (`TestResultsSupport.observe(project, sink, lifetime)`),
  `TestEventSink`, `IdeTestTree`, `ProcessTests` (per record), `IdeTestResultsGateway`, `TestResultsObservation`
  (connects every support for the tracker's lifetime; the EP listener uses the tracker's service scope, which the
  platform injects into `ProcessTracker`).
- `run/smrunner/` (only in `mcp-companion-test-runner.xml`): `SmTestResultsSupport` (subscribes
  `SMTRunnerEventsListener.TEST_STATUS` on the project bus with the given lifetime), `SmTestEvents`
  (`SMTRunnerEventsAdapter`), `SmTestTree` (reads the tree, resolves positions).
- `toolsets/run/`: `TestsOutput` (`TestsOutput`, `TestSummaryOutput`, `TestCountsOutput`, `TestOutput`,
  `FailureOutput`), `TestFilterInput`, four new parameters.

### Binding results to the tracked process

A test event's proxy leads to its root (`SMTestProxy.getRoot()`), the root to the run's `ProcessHandler`
(`SMRootTestProxy.getHandler()`), the handler to the record `ProcessTracker` already keeps per handler. The first event
of a process stores a reader of its tree in that record (`ProcessTests`); later events only count up a `StateFlow`
(for waiting) and set the started/finished phase. Events of handlers the tracker does not know (not tracked, already
released) are dropped. There is no second registry: the tree reference lives in the record and goes when the record is
released (tab closed or reused after the end, more than 20 ended processes, project closed), so `tests` then answers
`NOT_FOUND`, like every other action. If the test runner plugin is unloaded, the observation's EP listener drops every
tree (`ProcessTests.forget`), so no class of the unloaded plugin stays referenced.

Seen end to end: in a JUnit run the root has the handler at `onTestingStarted`; in a Gradle test task it is `null` then
(the event comes on the EDT before the console is attached) and set by the first test or suite event, and the end of
testing is reported after the process ended. Hence every event tries to bind, and waiting has a grace period (below).

### Holding the IDE's tree, not a copy

The record keeps a reference to the IDE's own `SMRootTestProxy` and reads it on every call; nothing is copied while the
run goes on.

- Memory: the tree (with each test's output) is held by the run tab anyway; the record only adds a reference, and it
  is released no later than the tab (release rules above). A copy would have duplicated messages and stack traces of
  every test for every retained process.
- Liveness: a call reads the current state, also of tests that were announced but have not run (`not_run`), without
  any event being missed, and a process adopted after it started gets the whole tree with its next test event (the
  tree is complete, not only the events after adoption).
- Thread-safety: the tree is built on the console's reader thread (JUnit: "BaseDataReader", Gradle: "Connection
  worker") and the EDT. Its child lists are `CopyOnWriteArrayList`s and each proxy's state is a volatile field, so a read
  from the tool's thread sees a consistent state per test, possibly a few events behind. The reading is O(tests) per
  call, transient; positions are resolved only for the failed tests of the page, in one cancellable read action, and
  not while the IDE indexes (then the URL is given).
- After the tab closed: the record is released with it, so a disposed tree is never read.

### Outcomes, verdict, filter

Outcomes come from public `SMTestProxy` predicates, in this order: `isInProgress` → running, `isInterrupted` →
interrupted, `isIgnored` → ignored, `isDefect` → failed, `isPassed` → passed (includes skipped, as the IDE counts),
otherwise not_run. An error (exception) is not told apart from an assertion failure: only the internal `Magnitude` does
that; message and stack trace name the exception. Leaves are tests; a suite counts as a test only when it failed without
children.

Verdict: `running` while the IDE has not reported the end and the process lives (failures so far are counted);
otherwise `failed` if any test failed, `no_tests` if there is none, `interrupted` if the run ended before all tests
completed, else `passed`. `partial: true` while the IDE has not reported the end of testing. A run that was stopped is
reported finished by the IDE (its open tests become interrupted).

Filter `failed` (default), `passed`, `ignored`, `all`; paging through `PageRequest` (now with a per-action default:
`limit` 20 here, max 1,000); order = the IDE's tree order (append-only per suite, so pages are stable after the run;
during a run with parallel suites a page can shift). Per item: `name` and `suite` (enclosing suites outermost first,
e.g. `["NestedTest","Large"]` and `["SquareTest","[3]"]`, so equal names are told apart), `outcome`, `durationMs`; for
failed tests `path`+`line` of the declaration (via the run's test locator, e.g. `JavaTestLocator`) or else `url`, and
`failure` with `message`, `expected`/`actual` (from the comparison the IDE built: `DiffHyperlink` left = expected,
pinned in `RunTestsToolsetTest`), `stackTrace` cut to `stackLines` lines (default 10, 0-200, at most 20,000
characters; other texts at most 4,000 characters) and `truncated: true` when anything was cut. Passed tests carry no
failure, no position. Zero counts other than total/passed/failed are left out; no nulls are serialised.

`processes` and `status` add `tests: {verdict, partial?, counts}` for processes that have results.

### Waiting

`timeoutSeconds` (0-300, default 0 = answer at once) waits, event-driven, on the record's event counter combined with
the process state: until the IDE reports the end of testing, or the process ends. A process that ended before the IDE
reported the end of its testing gets 3 s (`END_GRACE`, on the injected clock) for the last events: a Gradle test task
that failed to compile delivered its (empty) tree only after the process ended (seen end to end: without the grace
`tests` said `no_results`, a later call `no_tests`). A wait on a process that is no test run therefore ends 3 s after
the process. `waitedMs` and `timedOut` are reported.

### Without results: a result, not a failure

`tests` on a process without results answers success with `verdict: no_results` (or `not_started`) and a `reason`,
the way `hotswap` answers `outcome: not_possible` for a process that cannot reload classes: the call was valid and
the process exists; the answer is a fact about that process. Failures stay for invalid arguments (`INVALID_ARGUMENT`),
unknown or released processes (`NOT_FOUND`) and an IDE without the test runner plugin (`UNSUPPORTED`, like `hotswap`
without the Java plugin). Reasons:

| Situation | Answer |
|---|---|
| process `not_started` (e.g. before-launch Make failed on a compile error) | `not_started`, "pN did not start …, so no test ran. Fix the cause (compile errors: the official build_project tool) …" |
| alive, no test event yet (also Gradle compiling) | `no_results`, "… not a test run, or its tests have not started yet (build tasks run first). Call again later, or pass timeoutSeconds" |
| adopted (running before the plugin loaded), no event since | `no_results`, "… results become available with its next test event" |
| ended without any test event | `no_results`, "… not a test run, it ended before its tests started …, or the IDE shows its tests only as console output (e.g. a Maven run). Read the console with action=output." |
| results, but no test | `no_tests` + `reason`: the console usually tells why; a Gradle test task's console is not captured, `build_project` lists compile errors |

## End to end

Sandbox on a private Xvfb (`:93`, 1440x900), `runIdeForMcpE2e -PmcpE2eAwtToolkit=XToolkit -PmcpE2ePort=64421` with
`-PmcpE2eFixture=jps|gradle|maven`, a Python Streamable HTTP client. The fixtures got JUnit 4 tests (all three: the
same `CalculatorTest` with two passing tests, `greets` failing `assertEquals("Hello, world", …)`, `divides` throwing
`ArithmeticException`, an `@Ignore`d test; `SquareTest`, parameterized, `square[3]` failing; `NestedTest` with
`Small.check` passing and `Large.check` failing; JPS and Gradle also `slowfix.SlowTest`, six tests of 1.5 s, `step3`
failing):

- JPS: module `testfix` with the project library `junit4`; `runIdeForMcpE2e` copies JUnit 4.13.2 and Hamcrest from the
  sandbox IDE's own JUnit plugin into `build/mcp-e2e-fixture/lib/` (no download). Configurations `AllTests` (package
  `testfix`), `SlowTest`, `BadJvmTest` (SlowTest with `-XX:+McpNoSuchOption`, so the JVM does not start).
- Gradle: `lib` has `testImplementation files(...)` on the same two jars, copied to `build/mcp-e2e-gradle/testlibs/`;
  configurations `LibTests` (Gradle, `:lib:test --tests "testfix.*"`, run as test) and `SlowTest` (JUnit).
- Maven: `core` has `junit:junit:4.13.2` (test scope), downloaded with Surefire 3.5.4 into the private
  `build/mcp-e2e-m2`; `~/.m2/repository` stayed empty (unchanged since Nov 11). Configuration `CoreTests` (`test` in
  `core`). The task now creates the Maven fixture's `.idea/` (git ignores it there, so a fresh checkout failed with
  `FileNotFoundException` before).

Results (raw exchanges in the scratch area, rounds `fj`, `fg`, `fm`; excerpts shortened):

| Case | Call | Answer |
|---|---|---|
| finished JUnit run (JPS) | `tests configuration=AllTests timeoutSeconds=120 stackLines=4` | `verdict: failed`, counts total 10, passed 5, failed 4, ignored 1, `waitedMs: 2781`; `CalculatorTest.greets`, suite `[CalculatorTest]`, `testfix/test/testfix/CalculatorTest.java:22`, message `org.junit.ComparisonFailure: `, expected `Hello, world`, actual `Hello, World`, 4 stack lines, `truncated: true`; `divides` failed with `java.lang.ArithmeticException: / by zero`; `Large.check` suite `[NestedTest, Large]`; `square[3]` suite `[SquareTest, [3]]`, expected `10`, actual `9` |
| paging, filters | `filter=all limit=4` / `offset=8` / `filter=ignored` | `hasMore: true, nextOffset: 4`; last page `square[2]`, `square[3]`; the ignored `CalculatorTest.subtracts` (no failure: the IDE keeps the ignore reason as test output, not as message) |
| in progress | `tests configuration=SlowTest filter=all` 6 s after start | `verdict: running, partial: true`, passed 2, failed 1, running 1, notRun 2; `status` shows the same summary |
| wait | `tests … timeoutSeconds=60` | returned at the end, `waitedMs: 4195`, `verdict: failed` |
| stopped mid-run | `stop`, then `tests filter=all` | after a failure: `failed`, interrupted 3; before any failure: `verdict: interrupted`, passed 1, interrupted 5 |
| JVM did not start | `BadJvmTest` | `verdict: no_tests`, total 0, `reason`; `output` has "Unrecognized VM option 'McpNoSuchOption'" |
| compile error before launch (JPS) | broken file via `create_new_file`, `start AllTests`, `tests timeoutSeconds=120` | process `not_started`, `verdict: not_started`, reason names build_project |
| not a test run | `Beacon` running / ended | `no_results` with the reasons above; `timeoutSeconds=10` on the ended process: `waitedMs: 3001` (grace) |
| released | `tests process=p1` after its tab was reused | `[NOT_FOUND] No process p1: it was released …` |
| arguments | `limit=0`; `output … filter=all`; `filter=bogus` | `INVALID_ARGUMENT`; `action=output does not take filter`; the server's own deserialisation error (as for other enums) |
| Gradle test task | `LibTests`, `tests` at once / `timeoutSeconds=120` | at once `no_results` (Gradle still compiling); then `failed`, total 10, passed 5, failed 4, ignored 1, `waitedMs: 909`, names without class prefix (`greets`, suite `[CalculatorTest]`), `lib/src/test/java/testfix/CalculatorTest.java:22`, expected/actual for `greets` and `square[3]`; no message for comparison failures, `/ by zero` for `divides`; Gradle's stack traces start with the exception line |
| Gradle compile error | broken test file, `LibTests` | process ends with exit 1, `verdict: no_tests` + reason (with the grace; `build_project` then lists `Broken.java` line/column) |
| JUnit configuration in the Gradle fixture | `SlowTest` | ran by the IDE's JUnit runner, not delegated (pid, IDE-style names); partial and final results as in JPS |
| Maven `test` | `CoreTests`, `tests timeoutSeconds=240` | `verdict: no_results`, reason points to `action=output`; the console has Surefire's summary (`Tests run: 10, Failures: 3, Errors: 1, Skipped: 1`, each failure with line). The IDE builds no test tree for a Maven run in 263. |
| test run the IDE starts (official tool) | `execute_run_configuration AllTests` (confirmed in its dialog) | `processes` lists it with `tests: {verdict: failed, counts …}` |

Every `mc_run` call returned without a window; the list of visible top-level windows was the same before and after each
round except for the editor title (the file `create_new_file` opened). The only dialog was the official
`execute_run_configuration` confirmation (Allow/Deny, "brave mode" checkbox), answered by a click on Allow on the
private display; the official call then did not return until the IDE was stopped.

## Limits and what is not proven

- Errors and assertion failures are both `failed` (internal API only). Ignore reasons are not available as text: the
  IDE keeps them as test output, not as the proxy's error message (platform test and end to end).
- Gradle test tasks: `output` captures nothing for them (the test console takes the output; the same for passing and
  failing runs), so a compile failure is explained only by `build_project`. Their results arrive with a delay after
  the process ended; within the 3 s grace end to end, not guaranteed for slower machines (a later call sees them).
- Maven runs have no structured results in 263; `tests` says so and points to the console.
- The release rules of step 3 apply: any later run that reuses an ended run tab (not only one of the same
  configuration) releases the earlier process and its results (`NOT_FOUND`).
- Adoption (a test run that started before the plugin loaded) is covered by unit tests and by reasoning (the tree is
  bound with the run's next test event and is then complete); not shown end to end, because loading the plugin into a
  running sandbox needs the plugin manager UI.
- Unloading the test runner plugin while our plugin stays (trees dropped) is not tested.
- TestNG, JUnit 5, Kotlin test, JavaScript test runners use the same tree and topic but were not run.
- Positions need the index; while the IDE indexes, `url` is given instead (not shown end to end).
- Paging during a run with parallel suites may shift; not reproduced.
- Pre-existing, not fixed: `runIdeForMcpE2e` reports "cannot serialize Gradle script object references" for the
  configuration cache (the `argumentProviders` lambda captures script values); the IDE still starts, the Gradle build
  ends as FAILED when the IDE is stopped.

## Checks

`./gradlew check buildPlugin verifyPlugin --rerun-tasks --no-build-cache`: green; `test` 314 tests (was 296),
`architectureTest` 20 (was 18), no failures; verifier: `Plugin com.github.sinlordep.mcpcompanion:0.0.1 against
IU-263.7307.200: Compatible`, no internal, experimental or deprecated usage.

## Tests

- Unit (fakes, manual clock): `TestResultsTest` (listing, positions only for the page's failed tests, filters, paging,
  stack bounds, verdicts), `TestResultsWaitTest` (reasons without results, `UNSUPPORTED`, summaries, parameter guard,
  waits: end event, timeout, grace with and without late results).
- Platform: `RunTestsToolsetTest` (a real tracked process, a tree built like the IDE's consoles, events on the real
  topic: running/partial with every outcome, final verdict, comparison orientation, URL without source, summaries,
  unbound and foreign trees ignored, `UNSUPPORTED` with the extension masked).
- Konsist: `smrunner` gateway segment and optional segment; `com.intellij.execution.testframework` only in `smrunner`;
  `run.tests` imports neither the platform nor `run.ide`/`run.smrunner`.

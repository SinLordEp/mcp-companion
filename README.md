# MCP Companion

![Build](https://github.com/SinLordEp/mcp-companion/workflows/Build/badge.svg)

<!-- Marketplace version and download badges are added after the first upload, when the plugin has a Marketplace id;
     see docs/RELEASING.md, "After the first upload". -->

MCP Companion is an IntelliJ Platform plugin that adds code navigation, safe delete, run configuration control,
module, diagnostics and build tools for AI agents to the MCP Server that is bundled with JetBrains IDEs. It complements the bundled server:
it never duplicates a built-in tool, and each of its tools steps aside by itself when the IDE ships an official tool
with the same capability.

## Requirements

- An IntelliJ-based IDE of the latest major version, currently 2026.3 (build 263). Tested with IntelliJ IDEA 2026.3.
- The bundled *MCP Server* plugin, enabled. MCP clients connect to the IDE through it.
- Optional parts load only where the IDE has them: Java (type hierarchy, super declarations, hot swap, JVM module
  facts, `mc_build`), Gradle and other external systems, Maven, the test runner (`mc_run tests`) and the platform's
  VCS support (`mc_diagnostics`, whose batch analysis lives there).

Only the latest major IDE version is supported (`since-build` 263, no `until-build`). When a new major version needs
changes, a new plugin version raises `since-build`; IDEs of earlier versions keep the last plugin version that supports
them (JetBrains Marketplace offers each IDE the newest compatible version). See `docs/PLAN.md`, section 3.

## Installation

- **From disk (now):** download `mcp-companion-<version>.zip` from the GitHub release or build it
  (`./gradlew buildPlugin`, output in `build/distributions/`), then <kbd>Settings</kbd> > <kbd>Plugins</kbd> >
  <kbd>⚙</kbd> > <kbd>Install Plugin from Disk...</kbd>. The plugin loads without an IDE restart. The IDE shows
  "Cannot update plugin" for it until it is on JetBrains Marketplace.
- **JetBrains Marketplace (after the first release):** <kbd>Settings</kbd> > <kbd>Plugins</kbd> >
  <kbd>Marketplace</kbd>, search for "MCP Companion".

## Configuration

- **Switching tools on and off:** on the bundled MCP Server's tool page, <kbd>Settings</kbd> > <kbd>AI</kbd> >
  <kbd>MCP Server</kbd> > <kbd>Exposed Tools</kbd>. MCP Companion's tools appear there like the built-in ones (search
  for `mc_`), and changes take effect immediately. MCP Companion keeps no on/off state of its own.
- **Overview and brave mode:** <kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>MCP Companion</kbd> lists every toolset
  with its state (available, or provided by an official tool) and links to the tool page. Its one editable setting is
  the MCP Server's brave mode (see [Safety](#safety)). The page is in English and Simplified Chinese.
- **MCP clients** must pass `projectPath` (or provide roots), so that the server knows the target project.

## Tools

All names start with `mc_`. Each tool is its own toolset, so an official replacement makes only that one step aside.

| Tool | What it does |
|---|---|
| `mc_status` | Plugin version, IDE build, target project and the state of every toolset: `yielded` with the official tools that replaced it, otherwise `available` (whether it is switched on is decided on the tool page). |
| `mc_find_usages` | Every reference to a symbol; scope `project` (all content roots) or also libraries, read/write filter, paging. |
| `mc_find_implementations` | Implementations, inheritors and overriding methods, or (`direction=up`) the supertypes and super methods. |
| `mc_type_hierarchy` | Supertypes and subtypes of a class as a graph, with depth and size limits (JVM languages). |
| `mc_safe_delete` | `preview` lists what blocks deleting a declaration; `apply` deletes it only when nothing does, as one undoable command, without ever opening a dialog (fixed rules for accessors, overriding methods and parameters). |
| `mc_run` | Existing run configurations: `start` (run or debug, returns a handle at once), `stop`, `restart`, `status`, `processes` (also those started in the IDE), `output` (bounded console capture: tail, regex filter, cursor), `wait_ready` (output pattern, failure pattern, port, end of process), `hotswap` (JVM debug sessions: build the way the project builds, also with Gradle delegation, and reload changed classes, with compile errors or the JVM's reason when it refuses), `tests` (results of a test run, also while it runs: verdict, counts, failed tests with location, message, expected/actual and a bounded stack trace; filter, paging, optional wait). |
| `mc_modules` | Read-only module model: `list` (modules with build system, SDK, language level, content roots and their folders; roots outside the project directory are marked `outsideProjectDir`), `graph` (declared dependencies with scope and exported flag, whole project or a walk from one module with depth; library edges with Maven coordinates on request; `format=mermaid` returns one Mermaid flowchart instead of paged edges), `cycles` (groups of mutually dependent modules with one shortest cycle each). |
| `mc_sync` | Re-import Gradle (and other external-system) and Maven projects after build file changes: `status` (linked projects, whether the IDE wants a reload, running syncs, the latest result) and `sync` (waits for the end with a timeout; the build tool's message on failure). |
| `mc_diagnostics` | Read-only. Problems without building: the analysis an editor shows (the language's error checks and the project's enabled inspections), run on files that need not be open. Scope: one `module` (with `include=dependencies` or `dependents`), a `directory` or `files`; `minSeverity` error (default) or warning. Grouped by file with line and column; totals per severity and per module; `limit` files per call (default 100, max 500) from `offset`, `timeoutSeconds`; `partial` and `nextOffset` say where to continue. |
| `mc_build` | `build` one module with the modules it depends on (`includeDependents`: also the modules that depend on it), through the IDE's build path, so Gradle and Maven delegation applies; returns the outcome, the modules included, the duration and the errors (and with `includeWarnings` the warnings) with file, line and column; after `timeoutSeconds` the build goes on and `status` reports it. One build at a time; refused in an untrusted project. |

Symbols are given by path, line and column or by fully qualified name (`com.example.Foo#member`). Paths in results use
the form of the bundled server's own tools (relative to the project directory, `..` for content roots outside it,
absolute for library archives), so they can be passed to `read_file` and back; every location has an `origin`
(`project`, `library_source`, `library_class`, `sdk`, `other`). Lists are paged with `limit`/`offset` in a stable order.

A typical `mc_run` sequence: `start` -> `wait_ready` -> `output` -> edit code -> `hotswap` -> `stop`; for tests:
`start` -> `tests` with `timeoutSeconds`. Process handles (`p1`, `p2`, ...) stay valid until the run tab of the process
is closed or reused. To check edits: `mc_diagnostics` for the module (seconds, no build), then `mc_build` for the module
(compiles what it needs), and the bundled `build_project` for the whole project. The plain module and library lists are
left to `get_project_modules` and `get_project_dependencies`.

## Safety

- **Confirmation before starting.** Like the bundled `execute_run_configuration`, `mc_run` asks in the IDE before
  `start`, `restart`, or a `hotswap` with `restartOnFailure` that restarts: a dialog names the configuration, its type
  and run or debug, with Allow and Deny. It asks once per call, after the checks that need no user. Deny, or no answer
  within 120 s, ends the call with `DENIED` and starts nothing; cancelling the call closes the dialog.
- **Brave mode decides whether the IDE asks.** It is the MCP Server's "Run shell commands or run configurations without
  confirmation", off by default, the same switch for the official tools and for MCP Companion. It can be turned on in
  the dialog, on <kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>MCP Companion</kbd> or on the MCP Server's own page.
  In a headless IDE nobody can be asked, so starts are refused unless brave mode is on.
- `mc_run` only runs run configurations that exist in the project, never arbitrary commands; it is marked as a
  destructive tool for MCP clients.
- `mc_safe_delete` previews first and refuses targets that are still used or that lie outside the project content.
- `mc_run`, `mc_sync` and `mc_build` refuse untrusted projects (safe mode). No tool opens a dialog other than the
  start confirmation. A build started by `mc_build` never makes the IDE offer to reload classes into a running debug
  session.

## Limits

A summary; details in the `docs/notes-*.md` files named in brackets.

- `isEnabled()` of a toolset is evaluated by the MCP Server at IDE start and when plugins change, not live; that is
  why on/off switching uses the official tool page (`docs/spike-step0.md`).
- Qualified names, `mc_type_hierarchy` and `mc_find_implementations direction=up` are for JVM languages (tested with
  Java and Kotlin); other languages get `UNSUPPORTED` there. Methods have no read/write access filter (`notes-step2`).
- Hot swap in delegated builds reads compile errors from the build tool's console, like `mc_build` (`notes-step7`).
  Processes run through Gradle have no `pid`; `stop` ends them with the Gradle task's exit code. Only the Run
  and Debug executors start; coverage and profiler processes are listed, read and stopped only (`notes-step3`).
- Test results: errors and assertion failures are both `failed`; ignore reasons are not available; Gradle test task
  results can arrive shortly after the process ends; Maven runs have no structured results in 263; TestNG, JUnit 5 and
  non-JVM test runners use the same API but were not run (`notes-step3b`).
- `mc_sync`: a failed Gradle sync leaves a partial model until the next successful one; `lastSync` covers only syncs
  that `mc_sync` started; Maven syncs that began before the plugin loaded are not seen (`notes-step4`).
- Confirmation: sessions of JetBrains' own agents with their own ask mode follow brave mode too (the mode is not in
  public API); turning brave mode on also lets the bundled terminal and script tools run without confirmation; the
  bundled `xdebug_start_debugger_session` starts the debugger without asking while `mc_run` asks
  (`notes-consent`).
- `mc_diagnostics` shows what the editor shows: unresolved symbols, type and syntax errors, inspection findings. It
  does not see what only a real compile finds: compiler limits such as "code too large", annotation processors and
  code generated during the build (missing generated classes show as errors), build script and dependency problems;
  where javac and the IDE judge an API differently the two disagree (seen: `new Integer(5)`, a removal warning in javac,
  a plain deprecation warning in the IDE). Weak warnings and information are never reported; the inspection id is
  rarely available through the public API. External annotators (XML schema validation, Javadoc HTML lint, KDoc links,
  ESLint and other linters) are not run for the analysed files, because their pass made batches wait 60 s; about
  30-50 files/s on a 16-core machine (`notes-step7`).
- `mc_build`: positions of builds delegated to Gradle and Maven are read from the build tool's console (javac, kotlinc,
  Maven compiler lines); other failures come as the build tool's reason without position. A Maven-delegated build of
  one module does not build the modules it depends on in the same Maven run, so it fails with "Could not resolve
  dependencies" unless they are installed. Messages are read only from the build's own compilations and build tool
  tasks; another delegated build of the same project running at the same time is mixed in. Builds started in the IDE
  at the same time are not coordinated
  (`notes-step7`).
- Tested on Linux only (IntelliJ IDEA 2026.3); not run on Windows or macOS, and not in other IntelliJ-based IDEs.

## Development

Requirements: JDK 25 for Gradle's toolchain (the build uses `jvmToolchain(25)`); the IntelliJ Platform Gradle Plugin
downloads IntelliJ IDEA 2026.3.

- `./gradlew check` runs the tests, detekt, ktlint and the Konsist architecture rules (`src/architectureTest`).
  `buildSrc` has its own tests, which run whenever `buildSrc` is rebuilt.
- `./gradlew verifyPlugin` runs the IntelliJ Plugin Verifier against IntelliJ IDEA 2026.3 and, once JetBrains publishes
  one, the newest EAP of the next major version.
- `./gradlew buildPlugin` writes the plugin ZIP to `build/distributions/`.
- Architecture and design rules: `docs/PLAN.md` section 5.1 (layers: MCP adapters in `toolsets/`, use cases, platform
  gateways; enforced by `src/architectureTest`). Platform findings: `docs/spike-step0.md` and `docs/notes-*.md`.

### End-to-end sandbox

`./gradlew runIdeForMcpE2e` starts a sandbox IDE with the MCP server enabled on `127.0.0.1:64399` and only a throwaway
copy of a fixture project under `build/` open. Options:

- `-PmcpE2eAwtToolkit=XToolkit` keeps the IDE on `$DISPLAY`, e.g. a private Xvfb (JBR 25 otherwise picks Wayland).
- `-PmcpE2ePort=<port>` changes the MCP port.
- `-PmcpE2eLaunchConfirmation=skip` (default) turns the sandbox's brave mode on, so scripted rounds start processes
  without a question; `ask` turns it off to see and answer the confirmation dialog. Only the sandbox's
  `options/mcpServer.xml` is written.
- `-PmcpE2eFixture=jps` (default): `src/mcpE2eFixture`, a Java project with a second content root next to it, run
  configurations `Beacon` (prints `READY`, listens on 127.0.0.1:64411), `Crash` (exit code 3), `Prompting` (refused by
  `mc_run`), JUnit configurations `AllTests`, `SlowTest`, `BadJvmTest`, and modules `cyclea`/`cycleb` that depend on
  each other. Its JDK is the sandbox IDE's own runtime, defined in the sandbox's JDK table; JUnit 4 is copied from the
  sandbox IDE.
- `-PmcpE2eFixture=gradle`: `src/mcpE2eFixtureGradle` (root, `app`, `lib` with JUnit tests, and the included build
  `src/mcpE2eFixtureGradleSibling`), built with an already unpacked local Gradle (`-PmcpE2eGradleHome=<dir>`, by
  default the newest under the build's Gradle user home), passed to the sandbox as the Gradle plugin's defaults and
  run offline, and a private Gradle user home under `build/`: nothing is downloaded.
- `-PmcpE2eFixture=maven`: `src/mcpE2eFixtureMaven` with a private local repository `build/mcp-e2e-m2`, so `~/.m2` is
  never used. `-PmcpE2eMavenBuild=maven` delegates building to Maven (default `ide`: the IDE builds the imported
  modules).
- Every fixture has the `mc_build`/`mc_diagnostics` modules `buildclean` (no problems), `buildok` (warnings only) and
  `buildbad` (a compile error, depends on `buildok`); the JPS fixture also `javaconly` ("code too large", which only
  javac reports). So a build of the whole project, and `mc_run hotswap` (which builds all modules), fail in the
  fixtures until `buildbad` (and in the JPS fixture `javaconly`) are fixed or removed.

The sandbox preparation is the task `prepareMcpE2eSandbox` (in `buildSrc/src/main/kotlin/mcpcompanion/e2e`); it runs
before every start and replaces the fixture copies, so edits of an earlier run are gone.

## Release process

Releases follow `docs/RELEASING.md`: the version in `gradle.properties`, user-facing entries under `[Unreleased]` in
`CHANGELOG.md` (the plugin's change notes are generated from it), the Build workflow's release draft, and the Release
workflow, which signs the plugin and publishes it to JetBrains Marketplace when the draft is published. The first
upload to Marketplace is manual.

## License

[Apache License 2.0](LICENSE).

---
Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).

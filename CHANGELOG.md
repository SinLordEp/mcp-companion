<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MCP Companion Changelog

## [Unreleased]

## [0.2.1] - 2026-11-19

### Changed

- Exposed Tools shows each MCP Companion tool under its group title without a group description that repeated the
  tool's own; the MCP Companion settings page lists toolsets by their short names.

### Fixed

- A tool steps aside only for tools that JetBrains ships (the IDE, its bundled plugins and plugins developed by
  JetBrains), no longer for a third-party plugin's tool that happens to have the same name.

## [0.2.0] - 2026-11-18

### Added

- `mc_diagnostics`: errors (and on request warnings) of one module, optionally with the modules it depends on or that
  depend on it, of a directory or of a list of files, found by the IDE's own analysis without building and without
  opening the files; grouped by file with line and column, totals per severity and per module, bounded and resumable
  (`limit`, `offset`, `timeoutSeconds`), waiting until the IDE finished importing and indexing. External annotators
  (external linters, XML schema validation) are not run for the analysed files.
- `mc_build`: build one module with the modules it depends on, optionally also the modules that depend on it, through
  the IDE's own build path (also when building is delegated to Gradle or Maven); errors and warnings with file, line
  and column (from the IDE's compiler, or read from Gradle's and Maven's console output), the build tool's reason when
  a build fails before compiling, a timeout after which the build goes on, `status`, one build at a time.
- `mc_modules graph format=mermaid`: the dependency graph as a Mermaid flowchart.
- An optional guide for AI agents on how the tools work together (check loop after edits, sync after build file
  changes, paging, consent): Settings | Tools | MCP Companion | Copy Guide puts it on the clipboard for `AGENTS.md` or
  `CLAUDE.md`; the repository also has it as the Claude skill `skills/mcp-companion`.

### Changed

- `mc_run hotswap`: compile errors of builds delegated to Gradle or Maven now come with file, line and column.

## [0.1.0] - 2026-11-17

### Added

- MCP tools for AI agents on the IDE's built-in MCP Server, next to the built-in tools; their names start with `mc_`.
  A tool steps aside by itself when the IDE ships an official tool with the same capability.
- `mc_find_usages`: every reference to a symbol, in the project or also in libraries, with a
  read/write filter and paging.
- `mc_find_implementations`: implementations, inheritors and overriding methods, or the supertypes and super methods.
- `mc_type_hierarchy`: supertypes and subtypes of a JVM class as a graph, with depth and size limits.
- `mc_safe_delete`: `preview` lists what still uses a declaration; `apply` deletes it only when nothing does, as one
  undoable command and without any dialog. Unused overriding methods are deleted together with their method.
- `mc_run`: start, stop and restart existing run configurations (run or debug), list processes (also those started in
  the IDE), read their console output (tail, regular expression filter, cursor), wait until they are ready (output
  pattern, failure pattern, port, end of process) and hot-swap changed classes into JVM debug sessions, built the way
  the project builds (also when the build is delegated to Gradle).
- `mc_run tests`: results of test runs shown in the IDE's test console (tested with JUnit 4 and Gradle test tasks),
  also while the tests are running: verdict, counts, failed tests with location, message, expected and actual values and a shortened
  stack trace.
- `mc_modules`: modules with build system, SDK, language level, content roots (also those outside the project
  directory) and folders; the dependency graph with scopes, also from one module towards its dependencies or
  dependents; dependency cycles.
- `mc_sync`: status of linked Gradle and Maven projects, and re-import after build file changes, waiting for the
  result with a timeout and reporting the build tool's message on failure.
- `mc_status`: plugin version, IDE build, target project and the state of every MCP Companion tool.
- Confirmation before `mc_run` starts a process: `start`, `restart` and a `hotswap` that has to restart ask in the IDE
  (Allow/Deny), as the built-in `execute_run_configuration` does. The MCP Server's brave mode decides whether the IDE
  asks; a refusal, or no answer within 120 seconds, starts nothing.
- Locations in results use the path form of the built-in MCP tools, so they can be passed to `read_file`.
- Settings | Tools | MCP Companion: the state of every tool, a link to the MCP Server's tool page where tools are
  switched on and off, and the brave mode setting. English and Simplified Chinese.

[Unreleased]: https://github.com/SinLordEp/mcp-companion/compare/0.2.1...HEAD
[0.2.1]: https://github.com/SinLordEp/mcp-companion/compare/0.2.0...0.2.1
[0.2.0]: https://github.com/SinLordEp/mcp-companion/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/SinLordEp/mcp-companion/commits/0.1.0

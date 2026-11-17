<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MCP Companion Changelog

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- `mc_status` MCP tool: plugin version, IDE build, target project and the state of every MCP Companion toolset
- Toolsets step aside by themselves when an official MCP tool provides the same capability
- Settings | Tools | MCP Companion: overview of the toolsets and their state, with a link to the bundled
  MCP Server's tool page, where tools are switched on and off (English and Simplified Chinese)
- `mc_find_usages`: every reference to a symbol in any language, with scope, read/write filter and paging
- `mc_find_implementations`: implementations, inheritors and overriding methods, or the super declarations
- `mc_type_hierarchy`: supertypes and subtypes of a JVM class as a graph, with depth and node limits
- `mc_safe_delete`: preview what blocks deleting a declaration; apply deletes only without blockers, undoably and
  without any dialog; unused overriding methods go with a deleted method
- Locations in results match the bundled MCP Server's path form (usable with `read_file`) and carry their origin
- `mc_run`: start, stop, restart and inspect existing run configurations (run or debug) by process handle, read their
  captured console output (tail, regex filter, cursor), wait until they are ready (output pattern, failure pattern,
  port, end of process) and hot-swap changed classes into JVM debug sessions; also sees processes started in the IDE;
  asks the user before it starts anything (see below) and opens no other dialog
- `mc_run tests`: structured results of test runs (JUnit, TestNG, Gradle test tasks and other IDE test consoles):
  verdict and counts, also while the run goes on, failed tests with location, message, expected and actual values and
  a bounded stack trace, filters and paging, optional wait for the end; `processes` and `status` show a test summary
- `mc_modules`: read-only module model: modules with build system, SDK, language level, content roots (also those
  next to the project directory) and folders; dependency graph with scopes and the exported flag, walks towards
  dependencies or dependents with depth, library edges with Maven coordinates; dependency cycles
- `mc_sync`: status of linked Gradle and Maven projects and re-import after build file changes, waiting for the result
  (the build tool's message on failure) with a timeout; one sync at a time per project; never opens a dialog
- `mc_run` confirmation: `start`, `restart` and a `hotswap` that restarts ask the user in the IDE first (Allow/Deny,
  naming configuration, type and run or debug), as the bundled `execute_run_configuration` does, once per call and
  after the checks that need no user; governed by the bundled MCP Server's brave mode, which the dialog's checkbox and
  Settings | Tools | MCP Companion can turn on; a refusal or no answer within 120 s returns the new error code
  `DENIED`; cancelling the call closes the dialog
- `runIdeForMcpE2e -PmcpE2eLaunchConfirmation=ask|skip`: brave mode of the sandbox IDE (default skip)

---
name: mcp-companion
description: Use when working in a project that is open in a JetBrains IDE whose MCP server exposes tools named mc_ (the MCP Companion plugin), to find usages, implementations and type hierarchies, search code by its structure, check edits without a full build, build one module, run the application, tests or a single test method (with or without a run configuration), apply the IDE's quick fixes, sync Gradle or Maven after build file changes, move classes, change method signatures and delete declarations safely, outline large files, label and restore the Local History, and read commit history, blame and diffs.
---

# Working with the IDE's MCP tools (MCP Companion)

The JetBrains IDE that has this project open serves MCP tools whose names start with `mc_` next to its built-in tools. They use the IDE's index, module model, build and run configurations. `mc_run tests` below means `mc_run` with `action=tests`.

## Prefer the IDE over the shell

- References, implementations, supertypes and subtypes: `mc_find_usages`, `mc_find_implementations`, `mc_type_hierarchy` instead of grep. Give the symbol by path, line and column or by qualified name (`com.example.Foo#member`). They do not search comments or plain text; use text search for those. For code of a shape, use `mc_structural_search` instead of a regular expression: `pattern="$Instance$.equals($Arg$)"` finds every `equals` call however it is formatted; `constraints` narrow a variable by text, type or count; `action=templates` lists the IDE's ready-made templates.
- Checking edits: `mc_diagnostics` instead of a full build.
- Compiling: `mc_build` for one module instead of building everything; `build_project` only when the whole project must build.
- Running the application or tests: `mc_run` instead of a shell command: `start` for an existing run configuration, `start_at` for a test method, test class, test directory or main function without one (like the IDE's Run gutter icon; it may create a temporary run configuration, never a saved one).
- Module names, content roots and dependencies: `mc_modules`.
- Reading a large file: `mc_file_structure` first (classes, methods, fields with their lines, from the IDE's Structure view), then `read_file` only the lines you need instead of the whole file.
- Where code came from and why: `mc_vcs_history` (`log` of a file or of some lines, `blame`, `diff` against a revision) instead of running the VCS in a shell.

## Before a large or risky change

Put a Local History label: `mc_local_history action=label name="before ..."`. If the change goes wrong, `action=diff` (or `preview`) with `path` and `label` shows what changed since, and `action=revert` restores it (a file: its content then; a directory: everything under it), as one undoable command. Without a label, `time` names the state at a point in time (files only for `revert`). The Local History also covers changes that were never committed.

## After editing code

1. `mc_diagnostics` with the edited `module` (or `files`): seconds, builds nothing. Fix what it reports: when the IDE has a fix for a problem (missing import, unhandled exception, missing method, wrong type, ...), prefer `mc_quick_fix` (`list` with the file and line, then `apply` with one fix id) to editing by hand, then check again.
2. `mc_build` on the affected module only when you need a real compile: errors only the compiler finds (e.g. "code too large"), annotation processors, code generated during the build, build script or dependency problems, or class files to run.
3. A debug session of the application runs: `mc_run hotswap` (it builds the changes itself; `restartOnFailure` restarts when the JVM refuses them). Otherwise `mc_run restart`. After `start` or `restart`, `mc_run wait_ready` before you use the application.
4. Tests: `mc_run start_at` the test class or method you changed (`path` and `line`, or `qualifiedName`; `AMBIGUOUS` lists candidates, pick one with `type`), or `mc_run start` a test configuration; then `mc_run tests` with `timeoutSeconds` for the verdict, counts and failed tests with message and location, instead of reading console output.

## After changing build files

Call `mc_sync sync` (Gradle, Maven) before `mc_diagnostics` or `mc_build`; until a sync succeeds, the IDE's model is out of date. `mc_modules list` then shows the result.

## Refactoring

- Moving a class to another package or source root: `mc_move`. Changing a method's parameters, return type or visibility: `mc_change_signature`. Renaming: the built-in `rename_refactoring`. They update every reference in Java and Kotlin; do not move files or edit imports and call sites by hand.
- Deleting a declaration: `mc_safe_delete`, or check `mc_find_usages` first.
- Call each with `action=preview` (the default) first and read the conflicts and affected files; then `action=apply`, which changes the code only when nothing conflicts, as one undoable command. `CONFLICT` means nothing changed: change the code or the request first.

## Project boundaries

A project can contain modules whose content roots lie outside the project directory, e.g. a sibling Gradle build of a composite build. `mc_modules list` shows them with a `../` path and `outsideProjectDir=true`. Check it before you conclude that a path is not part of the open project and fall back to plain file edits.

## Bounded results

- Lists are paged: while `hasMore` is true, call again with `offset=nextOffset`. `mc_diagnostics` also reports `partial` (timeout or indexing) when it stopped early; continue the same way.
- `state=running` from `mc_build` or `mc_sync` means the timeout passed and the work goes on: call `status` later instead of repeating the call.
- `mc_run output` continues with `since=nextCursor`.

## Consent and missing tools

- `mc_run start`, `start_at`, `restart` and a `hotswap` that restarts may ask the user in the IDE first. `DENIED` means the user declined or did not answer: do not retry in a loop; tell the user.
- If an `mc_` tool is missing, the user switched it off on the MCP Server's tool page, or an official tool with the same capability exists and the MCP Companion tool stepped aside. `mc_status` lists each toolset as `available` or `yielded`, with the official tools that replace it.

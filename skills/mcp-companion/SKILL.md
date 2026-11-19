---
name: mcp-companion
description: Use when working in a project that is open in a JetBrains IDE whose MCP server exposes tools named mc_ (the MCP Companion plugin), to find usages, implementations and type hierarchies, check edits without a full build, build one module, run the application or tests through run configurations, sync Gradle or Maven after build file changes, move classes, change method signatures and delete declarations safely.
---

# Working with the IDE's MCP tools (MCP Companion)

The JetBrains IDE that has this project open serves MCP tools whose names start with `mc_` next to its built-in tools. They use the IDE's index, module model, build and run configurations. `mc_run tests` below means `mc_run` with `action=tests`.

## Prefer the IDE over the shell

- References, implementations, supertypes and subtypes: `mc_find_usages`, `mc_find_implementations`, `mc_type_hierarchy` instead of grep. Give the symbol by path, line and column or by qualified name (`com.example.Foo#member`). They do not search comments or plain text; use text search for those.
- Checking edits: `mc_diagnostics` instead of a full build.
- Compiling: `mc_build` for one module instead of building everything; `build_project` only when the whole project must build.
- Running the application or tests: `mc_run` with an existing run configuration instead of a shell command. It never creates or edits run configurations.
- Module names, content roots and dependencies: `mc_modules`.

## After editing code

1. `mc_diagnostics` with the edited `module` (or `files`): seconds, builds nothing. Fix what it reports.
2. `mc_build` on the affected module only when you need a real compile: errors only the compiler finds (e.g. "code too large"), annotation processors, code generated during the build, build script or dependency problems, or class files to run.
3. A debug session of the application runs: `mc_run hotswap` (it builds the changes itself; `restartOnFailure` restarts when the JVM refuses them). Otherwise `mc_run restart`. After `start` or `restart`, `mc_run wait_ready` before you use the application.
4. Tests: `mc_run start` a test configuration, then `mc_run tests` with `timeoutSeconds` for the verdict, counts and failed tests with message and location, instead of reading console output.

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

- `mc_run start`, `restart` and a `hotswap` that restarts may ask the user in the IDE first. `DENIED` means the user declined or did not answer: do not retry in a loop; tell the user.
- If an `mc_` tool is missing, the user switched it off on the MCP Server's tool page, or an official tool with the same capability exists and the MCP Companion tool stepped aside. `mc_status` lists each toolset as `available` or `yielded`, with the official tools that replace it.

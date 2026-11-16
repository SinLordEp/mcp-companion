# MCP Companion

![Build](https://github.com/SinLordEp/mcp-companion/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

## Template ToDo list
- [x] Create a new [IntelliJ Platform Plugin Template][template] project.
- [ ] Get familiar with the [template documentation][template].
- [ ] Adjust the [group](./gradle.properties), as well as the [id](./src/main/resources/META-INF/plugin.xml), [name](./src/main/resources/META-INF/plugin.xml), and [sources package](./src/main/kotlin).
- [ ] Adjust the plugin [description](./src/main/resources/META-INF/plugin.xml) (see [Tips][docs:plugin-description]) and this README to describe what your plugin does.
- [ ] Review the [Legal Agreements](https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html?from=IJPluginTemplate).
- [ ] [Publish a plugin manually](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate) for the first time.
- [ ] Set the `MARKETPLACE_ID` in the above README badges. You can obtain it once the plugin is published to JetBrains Marketplace.
- [ ] Set the [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html?from=IJPluginTemplate) related [secrets](https://github.com/JetBrains/intellij-platform-plugin-template#environment-variables).
- [ ] Set the [Deployment Token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html?from=IJPluginTemplate).
- [ ] Click the <kbd>Watch</kbd> button on the top of the [IntelliJ Platform Plugin Template][template] to be notified about releases containing new features and fixes.

MCP Companion complements the MCP Server bundled with JetBrains IDEs. It adds the MCP tools AI agents need but the bundled server does not provide, without duplicating the built-in ones. Requires the bundled MCP Server plugin and IntelliJ-based IDEs 2026.3 or later.

## What it does

- Registers its tools on the bundled MCP Server, so every MCP client connected to the IDE sees them next to the
  built-in tools. All MCP Companion tool names start with `mc_`.
- Steps aside by itself: when an IDE version ships an official MCP tool with the same capability, the corresponding
  MCP Companion toolset leaves the tool list. The decision is re-evaluated when plugins are installed, removed or
  updated, and at IDE start.
- `mc_status` reports the plugin version, the IDE build, the target project and each toolset's state:
  `yielded` together with the official tools that replaced it, otherwise `available`. `available` only means that
  the toolset has not stepped aside; whether its tools are switched on is decided on the MCP Server's tool page. MCP clients must pass `projectPath`
  (or provide roots) so that the server knows the target project.

## Tools

| Tool | What it does |
|---|---|
| `mc_status` | Plugin version, IDE build, target project and the state of every toolset. |
| `mc_find_usages` | Every reference to a symbol, any language; scope, read/write filter, paging. |
| `mc_find_implementations` | Implementations, inheritors and overriding methods, or (`direction=up`) the supertypes and super methods. |
| `mc_type_hierarchy` | Supertypes and subtypes of a class as a graph, with depth and size limits (JVM languages). |
| `mc_safe_delete` | `preview` lists what blocks deleting a declaration; `apply` deletes it only when nothing does, as one undoable command, without ever opening a dialog (fixed rules for accessors, overriding methods and parameters). |
| `mc_run` | Existing run configurations: `start` (run or debug, returns a handle at once), `stop`, `restart`, `status`, `processes` (also those started in the IDE), `output` (bounded console capture: tail, regex filter, cursor), `wait_ready` (output pattern, failure pattern, port, end of process), `hotswap` (JVM debug sessions: build the way the project builds, also with Gradle or Maven delegation, and reload changed classes, with compile errors or the JVM's reason when it refuses). Never asks the user anything. |

A typical `mc_run` sequence: `start` -> `wait_ready` -> `output` -> edit code -> `hotswap` -> `stop`. Process handles
(`p1`, `p2`, ...) stay valid until the run tab of the process is closed or reused. `mc_run` only runs existing run
configurations, like the bundled `execute_run_configuration`, and is marked as a destructive tool for MCP clients.

Symbols are given by path, line and column or by fully qualified name (`com.example.Foo#member`). Paths in the
results use the form of the bundled server's own tools (relative to the project directory, `..` for content roots
outside it, absolute for library archives), so they can be passed to `read_file` and back; every location also has
an `origin` (`project`, `library_source`, `library_class`, `sdk`, `other`). Lists are paged with `limit`/`offset`
in a stable order.

## Switching tools on and off

Use the bundled MCP Server's tool page: <kbd>Settings</kbd> > <kbd>AI</kbd> > <kbd>MCP Server</kbd> >
<kbd>Exposed Tools</kbd>. MCP Companion's tools appear there like the built-in ones, and changes take effect
immediately. MCP Companion keeps no on/off state of its own.

<kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>MCP Companion</kbd> is a read-only overview: every toolset with its
state, and a link to the tool page.

## Development

- `./gradlew check` runs the tests, detekt, ktlint and the Konsist architecture rules (`src/architectureTest`).
- `./gradlew verifyPlugin` runs the IntelliJ Plugin Verifier against the IDE the plugin is built with.
- `./gradlew runIdeForMcpE2e` starts a sandbox IDE with the MCP server enabled on `127.0.0.1:64399` and only a
  throwaway copy of `src/mcpE2eFixture` open. On a private X display add `-PmcpE2eAwtToolkit=XToolkit`.
- The e2e fixture has run configurations `Beacon` (prints `READY`, listens on 127.0.0.1:64411) and `Crash` (exit
  code 3), `Prompting` (asks for its arguments, refused by `mc_run`); its JDK is the sandbox IDE's own runtime, defined
  in the sandbox's JDK table. `-PmcpE2eFixture=gradle` opens a Gradle-delegated fixture instead
  (`src/mcpE2eFixtureGradle`), built with an already unpacked local Gradle distribution.
- Design rules: `docs/PLAN.md` section 5.1. Platform findings: `docs/spike-step0.md`, `docs/notes-step1.md`,
  `docs/notes-step2.md`, `docs/notes-step3.md`.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "MCP Companion"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/SinLordEp/mcp-companion/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation

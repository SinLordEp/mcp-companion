# Step 0 spike: findings

Date: 2026-11-16. Target: IntelliJ IDEA 2026.3 (IU-263.7307.200, the latest 2026.3.x on the JetBrains repository
that day). Bundled MCP Server plugin of that build.

## Q1. Is `McpToolset.isEnabled()` re-evaluated dynamically?

**No.** The server evaluates `isEnabled()` when it builds an application-wide tool cache, and it rebuilds that cache
only when an extension is added to or removed from the `mcpToolset` extension point. Flipping our flag alone changes
nothing that the server serves until the IDE restarts or the `mcpToolset` EP changes.

### Evidence from bytecode

- The only caller of `McpToolset.isEnabled()` in the MCP server plugin is `McpToolset.Companion.getEnabledToolsets()`.
  Its only callers are `impl.ReflectionToolsProvider.getTools()` / `getToolsAsync()`.
- `impl.McpToolsListProvider` caches the tools per provider in `toolsByProvider` and publishes them in the
  `StateFlow allTools`. `Companion.collectTools` reuses a cached entry and calls a provider only when the entry is
  missing.
- The cache is recomputed (`requestUpdate`) only by two EP listeners registered in its constructor:
  `McpToolsListProvider$2` on `mcpToolsProvider` and `McpToolsListProvider$3` on `mcpToolset`. Only `$3` calls
  `invalidateReflectionProviders()`, which drops the cached `ReflectionToolsProvider` entries. So only a
  `mcpToolset` EP change re-evaluates `isEnabled()`. A `mcpToolsProvider` EP change recomputes but keeps the stale
  reflection entry.
- `impl.McpServerService.toolsStateProviderOrNull` is assigned in one place only, `publishToolsStateProvider`, the
  first time it is needed. `stop()` only sets `McpServerSettings.enableMcpServer = false` and calls
  `settingsChanged`. A server restart or a new session therefore reuses the same cache.
- Sessions (`impl.McpSessionHandler`, `impl.McpFilteredToolsListProvider`) collect `allTools` and the
  `McpToolFilterProvider.getUpdates` flows, then call the SDK `Server.addTools/removeTools`. The server advertises
  `"tools":{"listChanged":true}` (seen in the e2e `initialize` response), so changes that reach those flows are pushed
  to connected clients.

### Evidence from tests (`spike/IsEnabledReevaluationTest`, all pass)

"Served" means `McpServerService.getMcpTools()` (impl, test only).

- `testFlagChangeAloneDoesNotChangeTheServedList`: after `setEnabled(status, false)`, `McpToolset.enabledToolsets` no
  longer contains `StatusToolset`, but after waiting 1.5 s `mc_status` is still served.
- `testToolsetExtensionPointChangeReevaluatesIsEnabled`: registering a probe `McpToolset` extension at runtime makes
  the server drop `mc_status` within the 10 s timeout. Re-enabling the flag changes nothing. Disposing the probe brings
  `mc_status` back.
- `testOfficialDisallowListHidesToolImmediately`: putting `mc_status -> ToolState(enabled = false)` into the
  official `com.intellij.mcpserver.settings.McpToolDisallowListSettings` removes it from the served list at once.

### Ways to make a change take effect

| Way | Works | Public API? | Notes |
|---|---|---|---|
| Restart the IDE | yes | yes | Always works. |
| Restart the MCP server or open a new session | no | n/a | Reuses the cache. Verified by reading bytecode only. |
| Register or unregister a `mcpToolset` extension at runtime | yes | **no** | `ExtensionPoint.registerExtension(.., Disposable)` is `@TestOnly`; the overload without a disposable is deprecated. Not for main code. |
| Unload and load our plugin (dynamic plugin) | yes | yes | This is an EP change. Verifier: "Plugin can probably be enabled or disabled without IDE restart". Too heavy for a settings toggle. |
| Official per-tool disallow list `McpToolDisallowListSettings` | yes, immediately | yes, formally (package `com.intellij.mcpserver.settings`, not `impl`, no `@ApiStatus` annotation), but not a documented extension API | Keyed by `descriptor.fullyQualifiedName`, falling back to `descriptor.name`. Live sessions should get `tools/list_changed` through `DisallowListBasedMcpToolFilterProvider.getUpdates`, but that is verified by reading bytecode only. Writing here changes the user's own settings in the official page. |
| `mcpToolFilterProvider` | yes | no | Its signature uses `impl` classes. Excluded. |

No route uses only public API, avoids touching the user's official settings and still takes effect immediately.

**Consequence for the design.** "Step aside" (PLAN 3.4) still fits `isEnabled()`. An official equivalent can only
appear when a plugin is loaded or the IDE is updated, and both fire a `mcpToolset` EP change or a restart. So
`isEnabled()` is re-evaluated exactly when its answer can change. A user on/off switch (PLAN 3.5) cannot be immediate
through `isEnabled()`. See "PLAN.md changes" below.

## Q2. Enumerating MCP tools contributed by others (public API only)

**Recommendation: (b)**, the `mcpToolset` EP plus reflection over `@McpTool` functions, mirroring the server. Mark
ownership by our own type (a marker interface), not by classloader.

| | (a) `McpToolsProvider.EP` -> `getTools()` -> `descriptor.name` | (b) `McpToolset.EP` -> reflect `@McpTool` |
|---|---|---|
| Finds `get_file_problems`, `search_symbol`, `rename_refactoring` | yes | yes |
| Same result as the server's own list (enabled toolsets) | it *is* the server's list | yes: `testToolsetReflectionMatchesTheServersOwnList` reports `only in (a): []; only in (b): []` |
| Depends on `isEnabled()` | **yes**: it only sees enabled toolsets, and calling it from our own `isEnabled()` would recurse (`getTools` -> `getEnabledToolsets` -> our `isEnabled`) | no: it also sees disabled toolsets (116 foreign tools, against 94 foreign tools from enabled toolsets in the test IDE) |
| Cost | builds every tool's JSON schema | annotation lookup only |
| Who owns a tool | not visible from the provider: the only provider in IU 2026.3 is `impl.ReflectionToolsProvider` (a single provider for the whole platform). Only the descriptor strings show it: `category.fullyQualifiedName` is the toolset class name | the toolset instance itself |
| Blind spot | none | tools from a non-reflection `McpToolsProvider`. There are none in IU 2026.3 (only `ReflectionToolsProvider` is registered), but a third-party plugin could add one |

Tool name rule, from `impl.util.ToolsetReflection_utilKt.asToolDescriptor`: the name is `@McpTool.name`, or the Kotlin
function name when that is blank. The annotation may also sit on an overridden supertype function
(`toolAnnotationCandidates`). The test helper `ToolEnumerationTest.toolNames` mirrors both rules with Kotlin
reflection.

**Telling ours apart.** Public options are limited in 2026.3:
`ExtensionPointName.processWithPluginDescriptor`, `filterableLazySequence`, `PluginManager.getPluginByClass`,
`PluginManagerCore.getPlugin` and the `PluginAwareClassLoader` class are all `@ApiStatus.Internal`.
Classloader identity works in a real IDE but **not in tests**: in the test runtime all 34 toolsets share one
`PathClassLoader` (`testClassLoaderIdentityDoesNotSeparatePluginsInTests`). The tests therefore use our package
prefix. For main code, a marker interface (`interface CompanionToolset : McpToolset`) is the robust choice:
`toolset is CompanionToolset` holds in tests and in production.

Tests (`spike/ToolEnumerationTest`, all pass): `testProvidersApproachFindsOfficialToolsButOnlyForEnabledToolsets`,
`testProvidersApproachCannotTellOwnersApartByProvider`, `testToolsetReflectionFindsOfficialToolsAndSeparatesOurs`,
`testClassLoaderIdentityDoesNotSeparatePluginsInTests`, `testToolsetReflectionDoesNotDependOnIsEnabled`,
`testToolsetReflectionMatchesTheServersOwnList`.

## End-to-end check (real MCP client, curl)

Run with `./gradlew runIdeForMcpE2e`, a new task in `build.gradle.kts`. It starts the sandbox IDE with
`-Didea.mcp.server.force.enable=true` (this also skips the consent dialog; see `McpServerConsentGate.awaitConsent`),
`-Didea.mcp.server.force.port=64399` and `-Didea.trust.all.projects=true`. It opens only `build/mcp-e2e-fixture` (a
generated folder with one README). Correction (found in step 1): the IDE was meant to run on a private Xvfb display
`:97`, but its log says `toolkit: sun.awt.wl.WLToolkit`, so JBR 25 used the Wayland toolkit and its window opened
on the desktop session instead; see `docs/notes-step1.md`. The client talked Streamable HTTP
over `POST http://127.0.0.1:64399/stream`; `/sse` also exists. Observed:

- `initialize`: `serverInfo {"name":"IntelliJ IDEA MCP Server","version":"2026.3"}`, `capabilities {"tools":{"listChanged":true}}`, header `Mcp-Session-Id`.
- `tools/list`: 56 tools, `mc_status` among them. Its schema has an optional `projectPath` (added by the server
  because `@McpTool.includeProjectPath` defaults to true), and an `outputSchema` generated from `StatusOutput`.
- `tools/call mc_status` without arguments: `isError: true`, "Unable to determine the target project for the current
  MCP tool call…". This happens even with a single project open: curl sends no `roots`, so the server cannot pick
  the project.
- `tools/call mc_status` with `projectPath` = the fixture:
  `{"pluginVersion":"0.0.1","ideBuild":"IU-263.7307.200","projectName":"mcp-e2e-fixture"}`, returned both as text and
  as `structuredContent`.

Not covered end to end: flipping a switch while a client is connected and observing `tools/list_changed`.

## Dependency setup that worked

- `intellijIdea("2026.3")`, `bundledPlugin("com.intellij.mcpServer")`, plus `<depends>com.intellij.mcpServer</depends>`
  in `plugin.xml`.
- `since-build = 263`, `untilBuild = provider { null }`. The patched `plugin.xml` contains only
  `<idea-version since-build="263" />`.
- Kotlin plugin `org.jetbrains.kotlin.plugin.serialization` 2.4.21 (compiler plugin only). There is **no**
  kotlinx-serialization library dependency: the platform ships `intellij.libraries.kotlinx.serialization.core/json`
  (json 1.11.0) on its boot classpath, and kotlin-stdlib inside `lib/util-8.jar`. `kotlin.stdlib.default.dependency=false`
  was already set. The built zip contains only `mcp-companion/lib/mcp-companion-0.0.1.jar`. For comparison, the
  reference plugin idea-mcp-ext 0.2.1 bundles kotlin-stdlib 2.2.0 and kotlinx-serialization 1.7.3.
- `kotlin { jvmToolchain(25) }`: `product-info.json` of 263 says `minRequiredJavaVersion: 25`.
- IntelliJ Platform Gradle Plugin bumped from 2.19.0 to 2.20.1, because the build warned that it was outdated.
- `pluginVerifier()` dependency and `pluginVerification { ides { current() } }` were added. The template had neither,
  so `verifyPlugin` failed with "IntelliJ Plugin Verifier executable not found".
- Plugin Verifier result: `Compatible`. No internal, experimental, deprecated or override-only API usages were
  reported.

## Surprises in the 2026.3 MCP and platform API

- `McpToolset.isExperimental()` defaults to **true**. `StatusToolset` overrides it to `false`.
- `CoroutineContext.projectOrNull` **throws** outside an MCP call ("mcpCallAdditionalData called outside of a MCP
  call"). `McpCallInfo` has no public constructor, so the tests build it reflectively (`testing/McpCallContext.kt`).
- The plugin's own descriptor: `PluginManagerCore.getPlugin` and `PluginManager.getPluginByClass` are
  `@ApiStatus.Internal` in 263. `StatusToolset` implements the public `PluginAware` interface instead; the platform
  calls `setPluginDescriptor` when it instantiates the extension.
- `McpToolDisallowListSettings.ToolState.defaultFor(tool)` is `enabled = true, routerOnly = !category.alwaysIncluded`.
  In the e2e session `mc_status` was still listed directly, so router-only did not hide it under the default
  invocation mode.
- `@McpDescription` takes a compile-time constant, so tool descriptions cannot come from the resource bundle.
  They are text for the AI, not UI, and stay in code. `displayName()` and `displayDescription()` do come from
  `McpCompanionBundle`.
- `DynamicBundle(String)` is deprecated; `McpCompanionBundle` uses `DynamicBundle(Class, String)`.

## Suggested PLAN.md changes

1. **Section 2, row `isEnabled()`**: add that it is evaluated once into an application-wide cache and re-evaluated
   only on a `mcpToolset` EP change (plugin load or unload) or an IDE restart.
2. **Section 3.5 (settings page) and risk "isEnabled() may not be dynamic"**: the risk is real. Options:
   (a) our switch keeps driving `isEnabled()` and the page says "takes effect after IDE restart";
   (b) our page writes the toolset's tools into the official `McpToolDisallowListSettings` (immediate, public
   package but undocumented, and shares state with the official per-tool page);
   (c) drop our own on/off state and send users to the official per-tool page (2026.2+), keeping `isEnabled()` only
   for stepping aside.
   This spike implemented (a) without the UI. Pick one before step 1.
3. **Section 3.4 (stepping aside)**: reflection over the `mcpToolset` EP is reliable; it matched the server's list
   exactly. Do not call `McpToolsProvider.getTools()` from `isEnabled()`, because it recurses. Identify our toolsets
   with a marker interface, because classloader and plugin-descriptor lookups are internal or do not work in tests.
   Decide whether an official tool that is present but disabled by the user should still make ours step aside: (b)
   sees it, the server's list does not.
4. **Section 5.1**: plugin metadata comes through `PluginAware` (no `PluginManagerCore`). Tool descriptions are
   exempt from "UI strings in the resource bundle".
5. **Section 7, end to end**: use `./gradlew runIdeForMcpE2e`. Clients must pass `projectPath` (or `roots`).

## Suggested CI changes (not applied)

- `.github/workflows/build.yml` sets up Java 21, while the build needs a Java 25 toolchain. It works because foojay
  downloads one, but `actions/setup-java` with `java-version: 25` is cleaner.
- The verifier now runs only against `current()`. PLAN 7 also wants the next EAP: add it to
  `pluginVerification.ides` once a 2027.1 EAP is published.

## Local-environment notes

- The network on this machine was flaky: IPv6 is unreachable and IPv4 connections were intermittently refused.
  Gradle ran with `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true` and was retried on network failures. Nothing
  from this is in the repository.

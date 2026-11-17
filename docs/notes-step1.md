# Step 1: platform notes

Date: 2026-11-16. IntelliJ IDEA 2026.3 (IU-263.7307.200). These are findings made in step 1; `docs/spike-step0.md`
still holds the step 0 results (one correction there, about the display, is marked).

## MCP server

- **The tool never runs without a project.** `McpCallInfo.project` is not nullable, and the session handler resolves
  the project before it calls a tool. A call that cannot be resolved (no `projectPath`, no client roots) is answered
  by the server with "Unable to determine the target project…"; our code is never reached. So the no-project case
  cannot be turned into our own `ToolResult` failure through public API, and `mc_status` keeps the platform
  behaviour. Verified end to end: the call without `projectPath` returned that platform error.
- **The official per-tool switch works for our tools, live.** Unchecking `mc_status` on Settings | AI | MCP Server |
  Exposed Tools and pressing Apply removed it from `tools/list` of a new MCP session (55 instead of 56 tools) and a
  call answered "Tool mc_status not found"; re-checking brought it back. The page shows our toolset under its
  bundle display name and description (screenshots `tool-page-*.png`).
- **The page's configurable id is `com.intellij.mcpserver.settings.filter`**; its display name is "Exposed Tools"
  ("公开的工具" in Chinese). `ConfigurableEP.getDisplayName()` returns it localised. Note: in Kotlin,
  `ep.displayName` binds to the *field* `ConfigurableEP.displayName`, which is null for key/bundle registrations;
  call `getDisplayName()` explicitly.
- **Navigation to another settings page:** `Settings.KEY` from the component's data context, then
  `settings.find(id)` and `settings.select(page)`. Outside a Settings dialog we fall back to
  `ShowSettingsUtil.showSettingsDialog(project, predicate, null)`. Both are public. Verified by clicking the link in
  the sandbox (English and Chinese).

## Stepping aside

- `isEnabled()` of our toolsets reads the foreign tool names on each call. The gateway iterates
  `McpToolset.EP.extensionList` (already instantiated, cheap) and caches the reflection result per class in a
  `ClassValue`, so it costs one map lookup per toolset. It never calls `isEnabled()` of other toolsets and never asks
  the server for its tool list, so there is no recursion. `ExtensionPointForeignToolGatewayTest` proves both, and
  that a server recomputation completes while our `isEnabled()` walks the same extension point.
- `isEnabled()` never throws because of a foreign class. kotlin-reflect over another plugin's toolset can fail: a
  missing optional dependency (`NoClassDefFoundError`), Kotlin reflection limits, Java or synthetic classes.
  `McpToolNameReader` contains any such failure. The class then contributes no names, and a warning naming the
  class is logged once. The empty result is memoised: reflection of a loaded class is deterministic, and a reloaded
  plugin brings a new class. Control-flow exceptions (`ProcessCanceledException`), `CancellationException` and
  `OutOfMemoryError` are rethrown, and nothing is cached for them. The contract test still uses the raw reflection,
  so a failing official class would show up there loudly.
- A toolset that has not stepped aside is reported as `available` ("Available" / "可用"), not "active". We do not
  read the official per-tool switch, so we cannot claim that its tools are switched on.
- Because the server caches `isEnabled()` (step 0, Q1), the decision only takes effect at the next `mcpToolset` EP
  change or IDE restart. That is exactly when an official equivalent can appear. `CompanionToolsetContractTest`
  shows it: with a catalog entry `status = search_symbol`, `mc_status` reports itself yielded at once, and the
  server drops it at the next EP change.
- Stepping aside end to end in the sandbox IDE was **not** done. The shipped catalog is empty, and making it point at
  an official tool would need test-only code in main. It is verified at platform-test level against the server's
  real tool list (`McpServerService.getMcpTools()`, used in tests only).

## Test runtime

- A `@Service` class may have an extra `internal` constructor for tests: the light-service container uses the
  no-argument one, and tests swap the instance with `replaceService`.
- Konsist reads the sources at run time. The `architectureTest` task therefore declares `src/main` as an input;
  otherwise Gradle keeps it UP-TO-DATE after production code changes. A deliberate violation (a `*Helper` class in
  `toolsets/` importing PSI and `mcpserver.impl`) made three rules fail; the probe file was removed again.

## Quality tools on Kotlin 2.4.21 / Java 25

- **detekt:** 1.23.8 (latest 1.x) embeds Kotlin 2.0.21. Used 2.0.0-alpha.6 (embeds Kotlin 2.4.10), plugin id
  `dev.detekt`. Runs fine on JBR 25. It is an alpha; switch to 2.0.0 final when it ships.
- **ktlint:** `org.jlleitschuh.gradle.ktlint` 14.2.0 with ktlint 1.8.0, `intellij_idea` code style. Works.
- **Konsist:** 1.0.0-beta.1 (built against Kotlin 2.4.21). Runs as a separate JUnit 5 suite (`architectureTest`). It
  needs its own `kotlin-stdlib` dependency because `kotlin.stdlib.default.dependency=false` applies project-wide.

## Sandbox IDE on a private display, and in Chinese

- **JBR 25 picks the Wayland toolkit (`sun.awt.wl.WLToolkit`) by itself**, even with `WAYLAND_DISPLAY` unset and
  `DISPLAY` pointing at Xvfb. Its windows then open on the desktop session. The step-0 e2e run and the first step-1
  run did this unnoticed. The IntelliJ Platform Gradle Plugin passes `-Dawt.toolkit.name=auto`, so
  `JAVA_TOOL_OPTIONS` cannot override it. `runIdeForMcpE2e` therefore accepts `-PmcpE2eAwtToolkit=XToolkit`, which
  adds `-Dawt.toolkit.name=XToolkit` after the plugin's default; the log then says `toolkit: sun.awt.X11.XToolkit`.
- **Chinese UI:** the Chinese language pack is bundled in 263 (`plugins/localization-zh`). `-Di18n.locale=zh-CN` did
  *not* work, because the sandbox's stored registry value wins. What works is writing
  `<component name="LocalizationStateService"><option name="selectedLocale" value="zh-CN"/></component>` into the
  sandbox's `config_runIdeForMcpE2e/options/ide.general.xml` before the start. The plugin then picks
  `McpCompanionBundle_zh_CN.properties`.
- On this Xvfb, `、` and `。` render as small dots in every Chinese string, the IDE's own included. That is the
  fallback font of the display, not our text.

## Paths

- Client paths are parsed lexically by `common/paths/PathSyntax`, independent of the OS the IDE runs on. Unix
  (`/a`), drive (`C:\a`, `c:/a`) and UNC (`\\server\share\a`) paths are absolute, and backslashes are separators.
  `.` and `..` are applied before deciding inside or outside: `../outside/File.java` and `<root>/../x` are outside the
  project. In an absolute path `..` stops at the prefix (`/`, `C:/`, `//server/share`). Symbolic links are not
  resolved.
- The root prefix is compared case-insensitively when the file system is (`SystemInfoRt.isFileSystemCaseSensitive`).
  Drive letters and UNC server/share names always compare case-insensitively.
  `VirtualFile.isCaseSensitive()` would be more precise per file system, but it is `@ApiStatus.Experimental` in 263.
- Files inside the root are looked up relative to the project directory, so in-memory test file systems work too;
  anything else goes through `LocalFileSystem`.

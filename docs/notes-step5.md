# Step 5: release preparation, notes

Date: 2026-11-17. The checklist for step 6 is `docs/RELEASING.md`.

## Listing rules applied

Sources (read 2026-11-17):
[best practices for listing](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html),
[approval guidelines](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html),
[plugin configuration file](https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html),
[plugin icon file](https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html).

| Rule | Applied |
|---|---|
| The first 40 characters of the description are English and a short summary; they appear on the preview card | First sentence: "Adds code navigation, safe delete, run configuration control and module tools for AI agents to the IDE's built-in MCP Server." |
| English first; correct grammar; no marketing adjectives, unverifiable claims, keyword stuffing, donation links | Plain statements only; every claim is backed by a test or an end-to-end run in the notes; "tested with" wording where only some cases ran |
| Bullet points for the main benefits; inline links to resources such as the issue tracker | Tool list and safety list as bullets; links to the repository and its issues |
| All external links valid and reachable (§1.7) | Links point to the GitHub repository; it must be public before upload (RELEASING, 1.4) |
| Name: original, at most 30 characters, Latin, no "Plugin"/"IntelliJ"/"JetBrains"/product names (§1.2) | "MCP Companion" unchanged |
| `<vendor>` required; optional `url`, `email` | Name and repository URL; no email (owner's decision, RELEASING 1.3) |
| Pick a stable id; it cannot change after public release | `com.github.sinlordep.mcpcompanion` unchanged |
| `until-build`: "highly recommended not to set this attribute" | Not set; `since-build="263"` |
| Change notes: short summary per version, no template placeholders (§1.5) | Generated from `CHANGELOG.md`; template line removed |
| Icon: SVG, 40x40, at least 2 px transparent padding, not the template's, not resembling JetBrains or other brands; visible on light and dark, dark variant allowed | See Icon |

The template version this project came from keeps the description in `plugin.xml` only (its README: "Maintain the
plugin description directly in this file"); there are no `<!-- Plugin description -->` markers and the build does not
read the README. `plugin.xml` is the single source; the README has its own, longer text.

## Icon

Chosen by the owner on 2026-11-17: a blue puzzle piece (rounded square with a knob on top and on the right, the usual
sign for an extension; light `#3574F0`, dark variant `#548AF7`) with the letters "AI" in white, drawn as 3 px strokes
(no font), and a green plus (`#3DAE5E`, dark `#57C278`) outside its top-right corner. Original artwork; visible shapes
span 2..38 px, no gradients. Rendered with librsvg at 40, 80 and 160 px on `#FFFFFF`, `#F7F8FA`, `#1E1F22`, `#2B2D30`.
The plugin icon guide advises against text in icons; two stroke-drawn letters stay legible at 40 px, and the owner
chose them knowingly.

History: the first icon (a tool panel with a plus disc) was rejected by the owner as unrelated to MCP. A version
carrying the official Model Context Protocol mark was dropped the same day for trademark reasons: the mark belongs to
LF Projects, LLC, whose trademark policy (https://lfprojects.org/policies/trademark-policy/) says, without written
permission, "A trademark should not be altered or amended in any way", "A logo should not be displayed with color
variations, or with other elements superimposed on top of the logo" and "A trademark should not be incorporated into
your company's logos or designs"; Marketplace approval guideline 1.8(b) requires that marketing assets "do not
infringe on any copyright or third-party trademarks". The repository licences (Apache-2.0, CC-BY-4.0) cover copying
the SVG, not trademark use. The same policy says "A trademark should not be used as part of your product name" and
allows "<your product name> for <mark>"; the name "MCP Companion" is kept (many Marketplace plugins carry MCP in
their names), which is a smaller, known risk.

## Build

- `plugin.xml` as built: `<idea-version since-build="263" />` without `until-build`, `<version>0.1.0</version>`,
  change notes = the `[Unreleased]` section (after `patchChangelog`, the `[0.1.0]` section; dry run, file restored).
  `changelog.versionPrefix = ""`, because the release draft tags `0.1.0`, not `v0.1.0`; otherwise the links that
  `patchChangelog` writes would point to tags that do not exist.
- ZIP: `mcp-companion/lib/mcp-companion-0.1.0.jar` (763 classes, all `com.github.sinlordep.mcpcompanion`, descriptors,
  icons, both bundles, `official-equivalents.txt`) and the searchable options jar; about 1.4 MB. No test, fixture or
  e2e classes or resources, no Kotlin standard library (`Kotlin-Stdlib-Bundled: false`), no third-party jars.
- Plugin versions on 2026-11-17: IntelliJ Platform Gradle Plugin 2.20.1, Gradle Changelog Plugin 2.5.0, Kotlin
  2.4.21, ktlint Gradle plugin 14.2.0 (ktlint 1.8.0), foojay resolver 1.0.0, Konsist 1.0.0-beta.1 are the newest
  releases. detekt has no release after `2.0.0-alpha.6` (no beta, RC or final), so it stays. Nothing upgraded.
- Gradle prints "Configuration.setVisible(boolean) ... deprecated" from one of the applied plugins, not from this
  build's scripts.

## `runIdeForMcpE2e` and the configuration cache

The task's `argumentProviders`/`jvmArgumentProviders` lambdas and its `doFirst` block captured script-level values,
which the configuration cache cannot serialize (3 problems, also with `--dry-run`). The e2e harness now lives in
`buildSrc/src/main/kotlin/mcpcompanion/e2e`: `PrepareMcpE2eSandbox` (an untracked task that copies the fixtures and
writes the sandbox's brave mode and JDK table), `McpE2eJvmArguments` and `McpE2eOpenedProject` (argument providers
with `@Input` properties), and small pure helpers (`BraveModeOption`, `SandboxJdkTable`, `GradleFixtureFiles`,
`MavenFixtureFiles`, `McpE2eLayout`, `McpE2eFixture`). `build.gradle.kts` only wires them. The task reads the
sandbox's directories from the run task through providers, so `./gradlew prepareMcpE2eSandbox` also works alone.
`buildSrc` has unit tests (8) that run whenever it is rebuilt; detekt checks its sources.

Found on the way: the default Gradle distribution for the Gradle fixture was chosen by a version key that ranked
`9.8.1` above `9.10`; fixed and tested. `-PmcpE2eFixture` now rejects unknown values instead of falling back to
`jps`.

Result: `runIdeForMcpE2e -PmcpE2eAwtToolkit=XToolkit` stored and then reused the configuration cache entry; the
sandbox ran with XToolkit, port 64399 and the private Gradle user home, answered `mc_status`, and after
File-close/Exit the build ended `BUILD SUCCESSFUL`. Stopping the sandbox with SIGTERM instead ends the build as
FAILED (exit value 143); that is Gradle reporting the killed process, not the cache.

## Plugin Verifier

`pluginVerification.ides`: `current()` and `latest { types = IntellijIdea; channels = EAP; sinceBuild = 264 }`. On
2026-11-17 JetBrains' release feed has no build above 263 (newest: 2026.3 release 263.7307.200; newest EAP
263.7307.44), so only IU-263.7307.200 is verified and nothing fails. The same filter with `sinceBuild = 263` returned
`IU-263.7307.44` (checked with `printProductsReleases`), so the EAP is picked up once one exists.

## Signing

Proved with a throwaway self-signed key pair in the scratch directory (deleted afterwards), never with real
credentials, and never `publishPlugin`:

- PEM text in `CERTIFICATE_CHAIN`/`PRIVATE_KEY`: `signPlugin` and `verifyPluginSignature` succeed.
- Base64 in both: `signPlugin` succeeds (the signed ZIP verifies with the CLI), `verifyPluginSignature` fails with
  "No certificate data found". RELEASING therefore says PEM text.
- Negative checks with the signer CLI the plugin uses: the signed ZIP against another certificate ("not signed by
  provided certificate authority") and the unsigned ZIP ("not signed") both fail.
- `verifyPluginSignature` read `signPlugin`'s output without depending on it (Gradle stopped the build with an
  implicit-dependency error). The build now declares that dependency, and `publishPlugin` depends on
  `verifyPluginSignature`.

## CI (static review)

Compared with the current template workflow: same jobs and action majors (`actions/checkout@v7`,
`actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, `actions/upload-artifact@v7`,
`jlumbroso/free-disk-space@v2.0.0`), Java 25 (the template uses 21). No Qodana or Codecov steps. Added: top-level
`permissions: contents: read` in `build.yml` (only the release draft job writes), `permissions: {}` in `release.yml`
(its job keeps `contents: write` and `pull-requests: write`, needed to upload the release asset and open the
changelog PR). Dependabot: Gradle and Actions, weekly instead of daily. Parsed with PyYAML; `actionlint` is not
installed. Only a push proves: the workflows run, the release draft, the secrets, the Release workflow and the
changelog PR (which needs "Allow GitHub Actions to create pull requests").

## Install from disk

IntelliJ IDEA 2026.3 (IU-263.7307.200, the copy the Gradle plugin downloaded) with config, system, plugins and log
directories under `build/clean-ide`, the MCP server forced on 127.0.0.1:64398, a copy of the JPS fixture, on a private
Xvfb (1440x900, openbox). The plugin was installed through Settings > Plugins > ⚙ > Install Plugin from Disk with the
built ZIP, after the IDE's third-party plugin notice.

- It loaded without a restart (dynamic plugin), and in a later start from the plugins directory ("Loaded custom
  plugins: MCP Companion (com.github.sinlordep.mcpcompanion, 0.1.0)"). No `ERROR` line in `idea.log` in three runs.
- The Plugins page shows the icon, name, vendor, version, the description and What's New; the IDE adds "Cannot update
  plugin. Choose where it was installed from" because the plugin is not on Marketplace yet.
- Settings | Tools | MCP Companion lists 8 toolsets as available; its brave mode checkbox wrote and removed
  `enableBraveMode` in the IDE's `mcpServer.xml`; its link opens AI > MCP Server > Exposed Tools, which lists all 8
  `mc_` tools.
- MCP client (Streamable HTTP): `tools/list` 63 tools including the 8; `mc_status`, `mc_find_usages`, `mc_modules
  cycles`, `mc_type_hierarchy`, `mc_find_implementations` returned results; `mc_run start Beacon` showed the
  confirmation (English and Chinese), Allow started it, `wait_ready`, `output` and `stop` worked, Deny returned
  `DENIED`.
- Chinese: IDE language zh-CN; settings page and confirmation dialog in Chinese; the description stays English.

## Surprises

- The "clean" IDE is not isolated from the machine's JetBrains state outside its config directory: it found the
  owner's JetBrains Account (its log: "Found licenses that are active", and an AI-terms request for that account) and
  the data-sharing consents, which its settings page says apply "to all installed JetBrains products". They were not
  changed.
- The bundled Air plugin uses a per-user socket `/tmp/codex-ipc/ipc-1000.sock`; the clean IDE found it in use (by
  another IDE) and did not take it.
- 2026.3's MCP Server has a "router-only mode" (default: for ACP agents only) in which tools marked router-only, by
  default ours too, are reached only through a router tool. External MCP clients saw all tools directly.
- Bundled "Coverage MCP Toolset" and "Debugger MCP Toolset" plugins, and an official `analyze_calls` tool: none
  overlaps with ours; all toolsets stayed available.
- Background jobs of a non-interactive shell ignore SIGINT, and an IDE started that way passes it on to its run
  configurations: `mc_run stop` then waited 10 s and needed `force=true`. With SIGINT restored the same stop took
  0.35 s. A property of the launch, not of the plugin.
- The jar manifest written by the IntelliJ Platform Gradle Plugin contains the build machine's OS and kernel version.

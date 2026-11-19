# Releasing MCP Companion

Checklist for step 6 of `docs/PLAN.md` (first release to JetBrains Marketplace) and for later releases. Everything in
the repository is prepared (step 5); the items marked **Owner** need the owner's accounts, secrets or decisions, and
nothing here may be done by an agent without the owner's explicit consent.

Sources (read 2026-11-17):

- Approval guidelines: https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html
- Listing best practices: https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html
- Uploading a new plugin: https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html
- Publishing (Gradle): https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html
- Plugin signing: https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
- Legal agreements: https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html

## What the repository already does

- Version `0.1.0` in `gradle.properties`; `since-build` 263 and no `until-build` in the built `plugin.xml`.
- The description lives only in `src/main/resources/META-INF/plugin.xml` (the build does not read README markers).
  The change notes are generated from `CHANGELOG.md` by the Gradle Changelog Plugin: the section of the current
  version, or `[Unreleased]` before `patchChangelog` has run.
- Plugin icon: `META-INF/pluginIcon.svg` and `pluginIcon_dark.svg` (40x40, original artwork: a blue puzzle piece with
  "AI" and a green plus).
- Signing and publishing read only environment variables: `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`,
  `PUBLISH_TOKEN`. `publishPlugin` signs, verifies the signature, then uploads. The Marketplace channel follows the
  version: `1.2.0` goes to the default (stable) channel, `1.2.0-beta.1` to `beta`.
- `verifyPlugin` checks IntelliJ IDEA 2026.3 and, once JetBrains publishes one, the newest EAP of the next major
  version (none existed on 2026-11-17).
- CI: `.github/workflows/build.yml` (build, `check`, `verifyPlugin`) and `release.yml` (on a published GitHub
  release: patch the changelog, sign and publish, attach the ZIP, open a changelog PR; needs the secrets of section 3
  and is not used by the manual process of section 6).

## 1. Decisions and accounts (Owner)

1. **License: Apache License 2.0** (`LICENSE` in the repository root, the unchanged text from apache.org; chosen by
   the owner on 2026-11-17). Marketplace requires a license at upload: "You must provide your own end user license
   agreement with any plugin via JetBrains Marketplace" (uploading-a-new-plugin), and approval guideline §3 requires,
   for an open-source license, a link to the source code. At upload give the license URL
   `https://github.com/SinLordEp/mcp-companion/blob/main/LICENSE` (reachable once the repository is public) and keep
   the source link in the description.
   - Third-party code: none is bundled today. If code from idea-mcp-ext (MIT) is ever copied (PLAN section 5), add a
     `NOTICE` with its copyright notice.
2. **JetBrains Account and Developer Agreement.** Sign in at plugins.jetbrains.com, accept the JetBrains Marketplace
   Developer Agreement (signed on the website before the first upload), and declare trader or non-trader status
   (EEA consumer law).
3. **Vendor profile.** Create a vendor profile (personal or organization). Guideline §1.3: the vendor website and
   email address must be provided, valid and functional. This is the profile's contact data; no email address is in
   any repository file, and none should be added without deciding to publish it. `plugin.xml` says
   `<vendor url="https://github.com/SinLordEp/mcp-companion">SinLordEp</vendor>`: change the name if the profile uses
   another one.
4. **Public repository.** The description links to `https://github.com/SinLordEp/mcp-companion` and its issue
   tracker, and the vendor URL points there. Guideline §1.7: every link must be valid and reachable. If the
   repository stays private, remove those links (description paragraph at the end, vendor `url`) before uploading.
5. **Name.** "MCP Companion" (13 characters, no "IntelliJ", "JetBrains" or "Plugin", Latin characters). Search
   Marketplace for an existing plugin of that name before the first upload. The plugin id
   `com.github.sinlordep.mcpcompanion` cannot change after the first public release.

## 2. Signing certificate and tokens (Owner)

Generate the key pair once, on your machine, outside the repository (commands from plugin-signing):

```sh
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 3650 -subj "/CN=SinLordEp" -out chain.crt
```

The first command asks for the password (`PRIVATE_KEY_PASSWORD`). `-days` is the certificate's validity; a longer
period means fewer renewals. Keep `private.pem` and the password secret and backed up; never commit them.

Marketplace token: profile > **My Tokens** > generate a token for publishing. It is shown only once.

GitHub repository > Settings > Secrets and variables > Actions > New repository secret:

| Secret | Value |
|---|---|
| `CERTIFICATE_CHAIN` | the whole text of `chain.crt` (`-----BEGIN CERTIFICATE-----` ... `-----END CERTIFICATE-----`) |
| `PRIVATE_KEY` | the whole text of `private.pem` |
| `PRIVATE_KEY_PASSWORD` | not needed with `private.pem` (the second command above removes the password); set it only if you store the encrypted key instead |
| `PUBLISH_TOKEN` | the Marketplace token |

Give the key and chain as **PEM text, not Base64**. With IntelliJ Platform Gradle Plugin 2.20.1, `signPlugin` accepts
Base64 too, but `verifyPluginSignature` (which `publishPlugin` runs before uploading) fails with "No certificate data
found" (tested in step 5 with a throwaway key).

Also in GitHub: Settings > Actions > General > Workflow permissions: allow GitHub Actions to create pull requests, or
the Release workflow's last step (changelog PR) fails.

## 3. Before the first upload

```sh
./gradlew check buildPlugin verifyPlugin --rerun-tasks --no-build-cache
```

All green; the verifier says `Compatible` for every IDE. Then build the signed ZIP locally, with the four values in
the environment of that one shell (never in a file in the repository):

```sh
CERTIFICATE_CHAIN="$(cat chain.crt)" PRIVATE_KEY="$(cat private.pem)" ./gradlew signPlugin verifyPluginSignature
# -> build/distributions/mcp-companion-0.1.0-signed.zip
```

Install that ZIP into a clean IDE once (Settings > Plugins > ⚙ > Install Plugin from Disk) and look at the plugin
page: icon, description, What's New.

## 4. First upload (Owner, manual)

"The first plugin publication must always be uploaded manually" (publishing-plugin); `publishPlugin` only works for a
plugin that already exists on Marketplace.

1. plugins.jetbrains.com > your account menu > **Upload plugin**; choose the vendor profile.
2. Upload `mcp-companion-0.1.0-signed.zip`.
3. License: the URL from 1.1. Tags: choose the ones that match (they are search filters).
4. Channel: default (stable) for `0.1.0`. Optionally tick **Hidden** to keep it unlisted after approval.
5. Submit. Every new plugin and every update is reviewed by hand; if nothing happens within 3–4 working days, write to
   marketplace@jetbrains.com (approval guidelines).
6. Optional but recommended: Media screenshots on the plugin page (at least 1200 × 760, default IDE theme, no personal
   data).

No GitHub release for a version that is already on Marketplace: publishing one starts the Release workflow, whose
`publishPlugin` would upload the version a second time, which Marketplace refuses ("won't accept multiple artifacts
with the same version").

## 5. After the first upload (fill in)

- [x] Marketplace numeric id: `36214` (from the plugin page URL `plugins.jetbrains.com/plugin/<id>-...`).
- [x] README: add the badges under the Build badge
  ```markdown
  [![Version](https://img.shields.io/jetbrains/plugin/v/<id>.svg)](https://plugins.jetbrains.com/plugin/<id>)
  [![Downloads](https://img.shields.io/jetbrains/plugin/d/<id>.svg)](https://plugins.jetbrains.com/plugin/<id>)
  ```
  and replace the "after the first release" Marketplace line under Installation with the plugin page link.
- [ ] `docs/PLAN.md` step 6: record the date, the version and the review outcome.
- [ ] Check the public plugin page: description, links, icon on light and dark, change notes, compatible products.

## 6. Later releases

1. Add user-facing entries under `## [Unreleased]` in `CHANGELOG.md` (Keep a Changelog groups: Added, Changed,
   Fixed, Removed, Deprecated, Security).
2. Raise `version` in `gradle.properties` (Marketplace never accepts the same version twice). A pre-release such as
   `0.2.0-beta.1` goes to the `beta` channel.
3. Merge to `main`; the Build workflow builds, tests and verifies.
4. On `main`, sign and check the signature (section 3 for the variables):
   `./gradlew signPlugin verifyPluginSignature`. The change notes of the built plugin are the `[Unreleased]` entries.
5. On the plugin's Marketplace page, **Upload Update** with `build/distributions/mcp-companion-<version>-signed.zip`
   (channel Stable, not hidden). Updates are reviewed by hand, usually within two working days.
6. Tag the signed commit with the bare version (`git tag -a 1.2.0 -m "MCP Companion 1.2.0"`) and push the tag; the
   compare links in `CHANGELOG.md` rely on these tags.
7. Run `./gradlew patchChangelog` (it moves `[Unreleased]` into the version's section) and merge that change with a
   pull request.

The Build workflow used to prepare a draft GitHub release for the Release workflow to publish from; it no longer
does, because releases are signed on the maintainer's machine and the repository holds no signing secrets. The
Release workflow remains for a later automated process: it runs when a GitHub release is published by hand.

A new major IDE version: run `verifyPlugin` (the next EAP is checked automatically once it exists) and the tests
against it; if APIs changed, release a new version and raise `since-build` (`supportedMajorBuild` in
`build.gradle.kts`) and `intellijIdea(...)` together (`docs/PLAN.md` section 3).

## What step 5 could not prove

- The GitHub workflows were checked by reading and YAML parsing only; they run for the first time after a push.
  `actionlint` was not available on the machine.
- `publishPlugin` and the Marketplace upload were never run; no real credentials were used.
- The review outcome, the Marketplace rendering of the description and the link checks happen only on Marketplace.

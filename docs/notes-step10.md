# Step 10: file structure, Local History and VCS history, platform notes

Date: 2026-11-19. IntelliJ IDEA 2026.3 (IU-263.7307.200). Goals: an outline of a file without reading it
(`mc_file_structure`), the IDE's Local History as a safety net for edits (`mc_local_history`) and the committed history
of project files (`mc_vcs_history`). Also two fixes found in the review of step 9: the build's heap and `mc_run stop`
of a process that ignores SIGINT. All part of 0.3.0.

## Fixes from the step-9 review

- **Build heap.** `gradle.properties` had no `org.gradle.jvmargs`, so the Gradle daemon ran with Gradle's default heap
  and `compileKotlin` failed with "GC overhead limit exceeded" (twice in step 9, reproduced with `--no-daemon`). Now
  `org.gradle.jvmargs = -Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8` and `kotlin.daemon.jvmargs = -Xmx3g
  -XX:MaxMetaspaceSize=1g` (the Kotlin compile daemon is a separate JVM). The workflows set no `GRADLE_OPTS` or
  `-Dorg.gradle.jvmargs`, so nothing conflicts. Gradle's default arguments include a heap dump on
  OutOfMemoryError; step 9's commit contained such a dump (`java_pid1344146.hprof`, 500 MB). It is removed from the
  tree here and `*.hprof` is ignored, but it stays in the history of the step-9 commit (that branch is not changed
  here).
- **`mc_run stop`.** `destroyProcess()` of a `KillableProcessHandler` sends SIGINT to the process tree. A process whose
  parent ignores SIGINT inherits the ignored signal (a JVM started so installs no handler), so it survives; before,
  `stop` waited `timeoutSeconds` and returned `terminated=false` unless `force=true`. Now `stop` does what the IDE's
  Stop button does when pressed again: after the grace period (`timeoutSeconds`, default 10) it calls
  `KillableProcess.killProcess()` (public, not annotated; SIGKILL to the tree) when `canKillProcess()`, waits 5 s more,
  and reports `outcome` (`ended`, `killed`, `detached`, `not_running`, or with `terminated=false` `not_killable`,
  `alive_after_kill`, `still_alive`) with the reason. Attached processes are only detached. `force=true` kills at once
  (falls back to the graceful stop for handlers that cannot be killed). The stop moved into `ProcessStop`. Tested with
  a program started as `sh -c 'trap "" INT; exec java ...'` (`RunStopToolsetTest`, Unix only), fakes for the
  handler that cannot be killed (`RunStopTest`), and the whole run test set with SIGINT ignored for Gradle itself
  (`sh -c 'trap "" INT; exec ./gradlew --no-daemon test --tests "*RunToolsetTest" ...'`): all pass, the two tests of
  the review take the grace period (about 10 s) instead of failing.

## Step 0: what the bundled server offers

Every `mcpToolset` registration of the IDE's descriptors was listed and each toolset's `@McpTool` methods read with
`javap -v` (descriptions included). Pinned by `StructureAndHistoryContractTest`.

| Tool | What it does | Consequence |
|---|---|---|
| `get_symbol_info` | Quick Documentation of the symbol at one position | One symbol, no outline |
| `search_symbol` | Symbols by name fragment across the project, with coordinates; `paths` filters by glob | Needs a query; no hierarchy, kinds, ranges or completeness for one file |
| `generate_psi_tree` (Qodana) | Parses a Java or Kotlin snippet passed as text and prints PSI node types | Not a project file, PSI nodes instead of declarations; not an outline |
| `list_directory_tree`, `read_file` | Files and their text | - |
| `get_repositories`, `git_status` | VCS roots; porcelain status of the working tree | No history, annotation or diff |
| `get_changelists`, `create_changelist`, `set_active_changelist`, `move_changes_to_changelist`, `delete_changelist` | Pending changes grouped in changelists | No history |
| `run_ide_script` | Arbitrary Kotlin in the IDE, brave mode and per-script consent | Not a tool for these tasks (and not served in the sandbox) |

Nothing outlines a file, reads the Local History, or shows a file's commits, annotation or a diff against a
revision. All three tools are new; no catalog entry.

## `mc_file_structure`

API (all public, not annotated): `LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile)`;
`TreeBasedStructureViewBuilder.createStructureViewModel(editor = null)`; `StructureViewModel.getRoot()`,
`TreeElement.getChildren()/getPresentation()`, `StructureViewTreeElement.getValue()`; `ElementDescriptionUtil` with
`UsageViewTypeLocation` (the language's term for an element, as Find Usages names it); `PsiNameIdentifierOwner`,
`PsiCompiledElement.getMirror()` (positions in decompiled library classes). Nothing is written per language: the tree
comes from the language's own Structure view. For JVM languages an extension point `declarationFacts`
(`structure.psi.DeclarationFactsProvider`, Java implementation `structure.java.JvmDeclarationFacts` through UAST)
gives stable kinds (`class`, `interface`, `enum`, `record`, `annotation`, `method`, `constructor`, `field`,
`enum constant`) and modifiers (visibility first, `static`, `abstract`, `final` for fields). Other languages get the
language's term (`property`, `xml tag`, `markdown header`, `record component`, ...), in the IDE's language.

Rules: the model's sorters, filters and node providers are not applied (inherited members, anonymous classes and
lambdas are not listed); siblings are sorted by position; elements of other files are dropped; a model is disposed
after use; the read waits for committed documents and smart mode (`constrainedReadAction` with
`withDocumentsCommitted` and `inSmartMode`) instead of committing on the EDT, which a modal dialog would block; at most
10,000 elements per call (`complete=false` beyond). Output elements are flat in pre-order with tree ids (`2.3.1`) that
do not change with filters.

## `mc_local_history`

### What the public API allows

`com.intellij.history.LocalHistory` (`intellij.platform.lvcs`, core class path): `putSystemLabel(project, name)`,
`isLabelValid(project, id)`, `getByteContent(file, FileRevisionTimestampComparator)`, `revertToLabel(project, id,
file)`, `isEnabled`; `Label.getByteContent(path)`. `putUserLabel` is `@Internal`; `Label.revert` is `@Obsolete`.

### What it does not allow, and why it is not done

The list of change sets (the revisions the Local History view shows), the differences between two of them and a
revert to an arbitrary change set live in `com.intellij.history.core` / `integration` and `com.intellij.platform.lvcs.impl`.
Those classes sit in the product module `intellij.platform.lvcs.impl` (`productModuleV2`, an optional content module
of the IDE that depends on the VCS plugin), which is not on the plugin's class path (`compileKotlin` cannot resolve
them); the entry `LocalHistoryImpl` is `@Internal`, the whole `com.intellij.platform.lvcs.impl` package is `@Internal`
(package-info), and `ActivityProvider`/`ActivityItem` are `@Experimental`. Reaching them would need a plugin-model-v2
content module of ours with a module dependency, for internal entry points. So `mc_local_history` has no revision list:
it lists the labels it put itself (kept per project in the workspace file, `RecordedLabels`, dropped when
`isLabelValid` says the IDE dropped them) and works with labels and points in time.

### Versions and the bug they work around

- At a label put in this IDE session: `Label.getByteContent(path)` (the history reverted back to the label; no content
  means the file did not exist then). The label objects are kept in memory for that.
- At a time, or at a label of an earlier session: `getByteContent(file) { it <= time }`. Its implementation
  (`ByteContentRetriever.getResult`) never offers the state after the last recorded change to the condition, so a file
  whose only recorded change is after the time gets no version (seen end to end: a file changed once after the label
  read as "no version"). Such files are reported as `no_earlier_version` instead of being shown as created.
- A directory is compared file by file: only files with a newer modification stamp than the time, or unsaved changes,
  can differ (others are skipped without reading the history); files deleted since are not visible to the public API.
- Diffs: `common/diff` (pure unified-diff rendering with 3 context lines, a shared line budget, truncation) over the
  IDE's `ComparisonManager.compareLines`.

### Revert

- A file: the content at the label or time is written into its document inside `UndoableCommand` (the step-8 command
  marked global), named "Local History: Revert <path> to <label or time>" (Chinese: "本地历史：把 … 恢复到 …"). One Edit
  | Undo reverts it (asked "Undo Local History: Revert …?", screenshots `undo-local-history-revert-en.png`, `-zh`).
- A directory to a label: `LocalHistory.revertToLabel` (the IDE's own revert, which deletes files created since and
  recreates deleted ones). Before it, unsaved documents under the directory are saved (otherwise the IDE would ask
  which version to keep). Edit | Undo in the project view reverted it (seen end to end). The changed files are not
  reported (the API returns nothing).
- A directory cannot be reverted to a time (no public API): `UNSUPPORTED`.
- Read-only files: `CONFLICT` before anything is written; writability comes from the file system for local files,
  since the VFS attribute lags behind a change made outside the IDE (seen: `chmod a-w` was not noticed by the VFS).

## `mc_vcs_history`

API (all public, in the VCS plugin's content module `intellij.platform.vcs`, optional dependency
`mcp-companion-vcs.xml`; the toolset itself is registered there, so it is absent without VCS support):
`ProjectLevelVcsManager.getVcsFor`, `AbstractVcs.getVcsHistoryProvider/getAnnotationProvider/getDiffProvider`,
`VcsHistoryProvider.reportAppendableHistory` with a `VcsAppendableHistorySessionPartner` (stopped by cancelling our
own indicator once enough revisions are in, `HistoryCollector`), `AnnotationProvider.annotate(file)` with
`FileAnnotation.getLineRevisionNumber/getLineDate/getRevisions/getAspects` (`dispose()` is deprecated and not
called), `VcsFileRevision.loadContent()`, `DiffProvider.getCurrentRevision` (only to name the working copy's base),
`ChangeListManager.getChange` (local changes). Blocking VCS calls run under `common/progress/ide/runUnderIndicator`
(extracted from step 9's `FileHighlighting`, which now uses it), so a cancelled call stops the VCS command. No VCS
command line of our own and no Git4Idea dependency.

- `log`: newest first, `limit`/`offset` (the provider is asked for `offset + limit` revisions only). The VCS settings'
  history limit (Git: `--max-count` when "Limit history" is on) applies. With `line`/`endLine`: the commits that last
  changed those lines, from the annotation - not every commit that ever touched them (Git's `log -L` has no
  VCS-neutral API; it would need Git4Idea internals or parsing Git's output).
- `blame`: segments of consecutive lines from one revision, with the commits they name; line numbers are those of the
  annotated revision, and `locallyModified=true` warns when the working copy differs.
- `diff`: the revisions must be among the file's newest 1000 (full number or unique prefix of 4+ characters), and the
  contents come from `VcsFileRevision.loadContent()`. Not `DiffProvider.createFileContent`: Git's implementation opens
  a modal error dialog ("Finding revision for diff ... bad revision") for an unknown revision, seen end to end
  (`screenshots/r2-hang.png`), and it cannot tell an unknown revision from one without the file. Names such as
  `HEAD~2` are therefore not accepted.
- Authors are output as the VCS reports them; nothing is stored. The fixtures use "Test Author" with
  `test@example.invalid`.

## Design

- `structure/` (pure: `OutlineFile`, models, `StructureGateway`), `structure/psi` (`PsiStructureGateway`,
  `OutlineWalker`, extension point `DeclarationFactsProvider`, factory), `structure/java/JvmDeclarationFacts`.
- `localhistory/` (pure: `LocalHistoryControl`, handlers per action, `HistoryTarget`, models, `LocalHistoryGateway`),
  `localhistory/ide` (`IdeLocalHistoryGateway`, `FileVersions`, `WritableFiles`, `RecordedLabels`, factory).
- `vcshistory/` (pure: `VcsHistoryControl`, handlers, models, `VcsHistoryGateway`), `vcshistory/vcs`
  (`IdeVcsHistoryGateway`, `HistoryCollector`, `RevisionContents`, factory).
- `common/diff` (`UnifiedDiff`, pure; `ide/TextComparison`), `common/progress/ide/runUnderIndicator`.
- `toolsets/structure`, `toolsets/localhistory`, `toolsets/vcshistory`, `toolsets/TimeTexts` (ISO-8601 in the IDE's
  time zone).
- Konsist: the three are feature packages; `StructureAndHistoryLayersTest` pins the layers, that the Structure view
  API is used only in `structure.psi`, the Local History API only in `localhistory.ide` (and never its implementation
  packages), VCS APIs only in `vcs` packages, and that the diff rendering is shared and platform-free.

## End to end

Sandbox `runIdeForMcpE2e -PmcpE2eAwtToolkit=XToolkit -PmcpE2eVcs=git` on a private Xvfb `:93` (1440x900, openbox), a
Python Streamable-HTTP client on 127.0.0.1:64399 (`build/e2e-step10/mcp.py`). New: `-PmcpE2eVcs=git`
(`buildSrc/.../GitFixture.kt`) turns the throwaway JPS fixture copy into a Git repository with three commits of
`src/history/Ledger.java` by "Test Author" (fixed dates; no global or system Git configuration read) and maps it in
`.idea/vcs.xml`; fixture files `src/structure/Inventory.java`, `src/history/Ledger.java`, `config/settings.json`,
`config/notes.md`. Requests and responses: `build/e2e-step10/*.json`; screenshots: `build/e2e-step10/screenshots/`
(under the worktree's `build/`, not committed). The final round (`final-*`) ran on the final code, in Chinese.

| Call | Result |
|---|---|
| `mc_file_structure src/structure/Inventory.java` | 19 elements: class, fields with types and `final`, constructor, methods with signatures, record with record components, enum with enum constants and a method, interface, abstract nested class; lines and ranges right |
| JSON, Markdown, `.idea/modules.xml` (`maxDepth=3`), `lib/junit-4.13.2.jar!/org/junit/Assert.class` (`limit=4`) | properties with values as location; headers nested by level; XML tags; the decompiled library class with lines, `hasMore` |
| `kinds=["method"] limit=3 offset=2`; `README.md`; a jar; `src`; a missing file | the page; 0 elements; `UNSUPPORTED`; `INVALID_ARGUMENT`; `NOT_FOUND` |
| `mc_local_history label`, edit (official `apply_patch`), `diff`/`preview` with `label` | the 3 added lines, and their removal for `preview` (file and directory) |
| `revert` the file to the label, again | reverted, `changedFiles`; then "Nothing differs"; Edit \| Undo in the project view asked and restored the edit |
| second edit, `diff`/`revert time=<between the edits>` | only the second edit; reverted to the first edit's state |
| new file + edit, `preview path=src/history label=...`, `revert` the directory to a time, to the label | created file listed as `deleted` by the revert; `UNSUPPORTED`; the IDE's revert deleted the new file and restored `Ledger.java`; Edit \| Undo brought both back |
| read-only file (`chmod a-w`): `revert` file, directory | `CONFLICT`, nothing changed |
| `labels`; unknown label; a jar entry | the labels; `NOT_FOUND`; `INVALID_ARGUMENT` |
| `mc_vcs_history log` (file, page, directory) | 3 commits with author, ISO date, summary and message; paging; directory history |
| `blame` (whole, `line=8 endLine=10`), `log line=7 endLine=11` | 5 segments over 3 commits, `locallyModified=true` with a local edit; one segment; the two commits of those lines |
| `diff` (working copy), `from=31c0 to=f3e257d6`, `maxLines=5`, `from=31c`, `from=nosuchrev` | the local edit; the second commit's change; truncated; `NOT_FOUND` (prefix too short / unknown) without a dialog |
| an untracked file: `blame`, `log`, `diff`; a directory outside the repository | `NOT_FOUND` (Git's message), empty log, `NOT_FOUND`; `UNSUPPORTED` |
| `tools/list` | 71 tools, 16 `mc_` tools |

Settings page with the three new rows: `settings-en-1440x900.png`, `settings-zh-1440x900.png` (16 rows, no scroll
bar, no cut text). The sandbox's locale was switched for the Chinese round in the sandbox's own
`options/ide.general.xml` and restored afterwards.

## Limits and what is not proven

- `mc_local_history` has no revision list (see above); labels put in the IDE itself are not listed (use `time`);
  `no_earlier_version` for files whose history starts after the time; directory diffs do not show files deleted since;
  a directory is reverted to a label only; the changed files of a directory revert are not reported. The Local History
  keeps a few working days by default; old labels disappear with it.
- The tools wait while a modal dialog of the IDE is open: in the sandbox Git's "Add File to Git?" question (asked
  after the official `apply_patch` created a file) held `mc_local_history revert` of a directory until it was
  answered. `mc_file_structure` no longer commits documents on the EDT; whether it now passes such a dialog was not
  reproduced (the dialog did not come up in that round).
- `mc_vcs_history`: only revisions among the file's newest 1000 and no symbolic names (`HEAD~2`, branches) for
  `diff`; `log` by lines is "last changed", not full line history; tested with Git only (Mercurial, Subversion,
  Perforce not run); the annotation is the annotated revision's, not the working copy's.
- `mc_file_structure`: kinds of non-JVM languages are in the IDE's language; modifiers only for JVM languages; Kotlin
  `internal` reads as public and `object` as class (UAST). Kotlin files were not checked end to end.
- Not run: Windows, macOS, very large files (the 10,000-element cut was tested with fakes only).

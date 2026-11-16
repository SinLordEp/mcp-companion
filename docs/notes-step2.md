# Step 2: code navigation tools, platform notes

Date: 2026-11-16. IntelliJ IDEA 2026.3 (IU-263.7307.200). Findings made while building `mc_find_usages`,
`mc_find_implementations`, `mc_type_hierarchy` and `mc_safe_delete`.

## No official equivalent in 263

All `@McpTool` names of every `mcpToolset` in the test IDE (116 foreign tools, dumped with the same reflection the
yield gateway uses) were checked. There is no find-usages, implementations, type-hierarchy or safe-delete tool.
Closest: `analyze_calls` (call hierarchy, incoming/outgoing calls of a callable only), `get_symbol_info` (quick
documentation), `search_symbol`, `rename_refactoring`. The equivalence catalog therefore stays empty.

## Package layout

- `navigation/usages`, `navigation/implementations`, `navigation/hierarchy`, `safedelete`: one feature package per
  tool, each with use case and models at its root, the language-neutral gateway in `psi/` and Java/JVM parts in
  `java/`. Konsist treats each as a feature that may not depend on the others, because each tool steps aside on its
  own.
- Shared, tool-independent pieces live in `common/`: `scope` (search scope), `source` (code site with line excerpt
  and enclosing declarations), `paging/StableResults`, `actions` (command pattern registry), `paths/psi`
  (`ProjectFiles`, the client-path/file mapping).

## API choices

| Need | API | Why |
|---|---|---|
| References | `ReferencesSearch` | language-neutral; every language plugin contributes |
| Read/write access | `ReadWriteAccessDetector.findDetector` | language-neutral EP in platform core; Java and Kotlin implement it |
| Implementations | `DefinitionsScopedSearch` (deep) | what Go to Implementation uses; Java and Kotlin contribute |
| Super declarations, type relations | `PsiClass.getSupers`, `PsiMethod.findSuperMethods`, `DirectClassInheritorsSearch`, UAST `toUElementOfType` | Java plugin only, behind our EPs `superDeclarations` and `typeHierarchy` registered in `mcp-companion-java.xml`; UAST turns Kotlin (and other JVM) declarations into their light classes |
| Safe delete analysis | `SafeDeleteProcessorDelegate.findUsages`, `findConflicts` (4-argument, not deprecated; Java delegate only, see below), `SafeDeleteProcessor.findGenericElementUsages`, `validElement` | the platform's own safe-delete analysis, without the parts that consult the user |
| Safe delete execution | `WriteCommandAction.writeCommandAction(project).compute` on `Dispatchers.EDT` (the coroutine `writeCommandAction` is `@ApiStatus.Experimental` in 263) running what `SafeDeleteProcessor.performRefactoring` does: `SafeDeleteCustomUsageInfo.performRefactoring` for the cleanups, `prepareForDeletion` of the delegate, `PsiElement.delete` | one undoable command, no dialog (see below) |
| Usage kind (import, call, ...) | not used | `UsageTypeProvider` is `@ApiStatus.Internal` in 263 and `UsageType` only has a localized `toString`; `access` is the only kind reported |

## Safe delete without UI (review item 1)

The first version ran `SafeDeleteProcessor.createInstance(..., askForAccessors = true).executeEx(usages)`. Headless
tests cannot show whether that consults the user, so the 263 bytecode was scanned for UI calls (Messages, dialogs,
usage views, modal progress, read-only prompts) in `SafeDeleteProcessor`, `BaseRefactoringProcessor`,
`JavaSafeDeleteProcessor` (and its `usageInfo` classes) and Kotlin's `KotlinFirSafeDeleteProcessor` plus the Kotlin
safe-delete helpers.

| Place | What it shows | When |
|---|---|---|
| `BaseRefactoringProcessor.doRun` (from `run()`) | error dialog, modal progress for the usage search, dumb-mode notification, usage preview (`previewRefactoring`), read-only prompt (`ensureElementsWritable` -> `CommonRefactoringUtil.checkReadOnlyStatus`) | always on `run()`; preview when `isPreviewUsages` (non-code usages, or the user asked) |
| `BaseRefactoringProcessor.showConflicts` / `processConflicts` | conflicts dialog | conflicts exist (`preprocessUsages`) |
| `BaseRefactoringProcessor.doRefactoring` (from `execute`/`executeEx`) | modal progress (`runProcessWithProgressSynchronously`) for `RefactoringHelper.prepareOperation`; status bar text | every `executeEx` |
| `SafeDeleteProcessor.preprocessUsages` / `checkConflicts` | `ConflictsDialog`, usage view with "Rerun Safe Delete" | unsafe usages or conflicts |
| `SafeDeleteProcessor.performRefactoring` | error dialog (`RefactoringUIUtil.processIncorrectOperation`) | the deletion throws `IncorrectOperationException` |
| `JavaSafeDeleteProcessor.getElementsToSearch` | "delete super method too?" (`SuperMethodWarningUtil.checkSuperMethods`, `Messages.showYesNoCancelDialog`), modal progress | the method overrides another |
| `JavaSafeDeleteProcessor.getAdditionalElementsToDelete` | "delete getter/setter too?" (`Messages.showYesNoDialog`) | `askForAccessors` and the field has accessors |
| `JavaSafeDeleteProcessor.preprocessUsages` | `OverridingMethodsDialog` | the method has overriding methods |
| `KotlinFirSafeDeleteProcessor.getElementsToSearch` | `checkSuperMethods`, "propagate to expected declarations?" (`Messages.showYesNoDialog`), parameter hierarchy (`Messages.showOkCancelDialog`, modal progress) | overriding members, `actual` declarations, parameters |
| `KotlinFirSafeDeleteProcessor.findConflicts` | `analyzeInModalWindow` (modal progress window; asserts the EDT) | members of some classes (found by a test: a property in a class) |

`findUsages` and `prepareForDeletion` of both delegates and the Java `usageInfo` classes' `performRefactoring` contain
no UI calls.

**What the tool does instead.** It never calls `run`, `executeEx`, `preprocessUsages`, `getElementsToSearch` or
`getAdditionalElementsToDelete`. The analysis calls `findUsages` and, only for delegates on an allow-list of checked
ones (`JavaSafeDeleteProcessor`), `findConflicts`; Kotlin's conflicts are skipped because they need a modal window.
The deletion runs inside one `WriteCommandAction` on the EDT: performs the cleanup usages, then for each deleted element calls the
delegate's `prepareForDeletion` and `PsiElement.delete()` (not `DeleteHandler.deleteElementImpl`, which may prompt for
read-only files; we refuse non-writable targets up front). An `IncorrectOperationException` becomes a `CONFLICT`
failure instead of an error dialog. Lost compared to `executeEx`: refactoring events, a local-history label and
`RefactoringHelper`s (e.g. import optimisation); the cleanups already remove imports of the deleted symbol.
`SafeDeleteDelegatesContractTest` pins the delegate set that was checked.

**Rules where the IDE would ask** (`SafeDeleteRulesTest`, and end to end below):

| Case | Decision |
|---|---|
| Field with getter/setter | Accessors are never deleted along. A getter that reads the field blocks (`reference`). A write in the setter is a platform cleanup: the assignment is removed with the field. |
| Method that overrides or implements another | Refused with a `conflict` blocker at the overridden method: deleting it alone would break its class (abstract super) or change behaviour (concrete super). Delete the topmost method instead. |
| Method with overriding or implementing methods | The overriders are deleted along and listed in `alsoDeleted`, if nothing outside the deleted methods references any of them (checked with an extra `ReferencesSearch` per overrider); otherwise `reference` blockers. Other dependent usages (e.g. `@Override` removal for an overrider of two methods) block as `dependent`. |
| Parameter | `UNSUPPORTED`: deleting it changes the signature and every call site. |
| Kotlin property | Deleted with its accessors (they are part of the property). |
| Class alone in its file | Deleted with the file (`PsiClass.delete`). |

JVM knowledge (parameter, overrides) comes through the new EP `safeDeleteSupport` (`JvmSafeDeleteSupport`, UAST).
Without it, dependents stay blockers and parameters are not detected.

Between the analysis (smart read action, background) and the deletion (EDT) the code may change. Inside the write
command the gateway compares `PsiModificationTracker.modificationCount` and reports `Stale`; the use case repeats the
analysis up to three times and then fails with `CONFLICT`. Any PSI change anywhere counts, so a user typing during the
call costs a retry.

### What blocks a safe delete

References follow the platform's rule: a `SafeDeleteReferenceUsageInfo` with `isSafeDelete() == true` outside
generated code is a cleanup (imports, writes to a deleted field), any other reference blocks. Dependent declarations
follow the rules above. Language conflicts from `findConflicts` are `conflict` blockers with their message (HTML
stripped; localized by the IDE). The delegate excludes usages inside the deleted element. Comments and strings are not
searched.

## Stable paging (found end to end)

The first end-to-end run returned page 1 and page 2 of `mc_find_usages` with an overlap and a gap: the platform
searches files in parallel (`ReferencesSearch` and `DefinitionsScopedSearch` call the processor from several threads)
and the order differs on every call. Offset paging is only correct over a fixed order, so the whole result, bounded to
10,001 hits, is sorted before the page is cut (`common/paging/StableResults`). Over 10,000 hits the call fails with
`INVALID_ARGUMENT` and asks for a narrower `scope`. This trades the "stop after page + 1" laziness of the brief for
correct pages; the search itself stops at the bound.

**Only the page is described (review item 2).** A full row (document, line text, enclosing walk, path rendering,
origin, smart pointer) is built only for the hits on the page. The search builds a cheap key per hit
(`SourceKey`: file URL and offset; for usages also the access kind, only when an access filter is active); the use
case hands the gateway a `pick` function (filter, bound check, sort, cut), and the gateway describes the picked keys.
Search, pick and description run in **one** smart read action, so a restart after a write action redoes all three and
a key always matches the code it is described from; a hit that cannot be described is dropped without moving
`nextOffset` (`Page.mapNotNull`), which cannot happen within one read action for keys built from a file. Tests:
`FindUsagesTest` (`only the usages of the page are described...`), `PsiUsageSearchGatewayTest` (60 references, page of
2, 2 descriptions), `FindImplementationsTest`. Implementations work the same way. In `mc_type_hierarchy` the gateway
hands the use case the keys (qualified name, else file and offset) of all direct relations; the use case keeps those
already in the graph plus, in key order, as many new ones as the node limit allows, and only those are described
(`BuildTypeHierarchyTest`: 500 subtypes, `maxNodes=4`, 3 described). Safe-delete blockers are still described in full:
a symbol rarely has many, and `apply` needs them all for the decision.

Order is now file URL, then offset (previously path, line, column), so a sibling content root sorts by its absolute
URL, not by its rendered `../` path. Direct subtypes are ordered by qualified name.

## Locations: consistent with the official tools

The bundled server's tools (`com.intellij.mcpserver.util.Fs_utilKt`, public package, used here in tests only):

- project directories are the project base path plus `BaseProjectDirectories` (top-level content roots), so a
  content root outside the base directory counts as "in the project";
- `relativizeIfPossible(projectDirectory, file)` renders such files relative to the base path with `..`;
- `read_file` accepts relative paths with `..`, absolute paths, archive entries and `jar://`/`file://` URLs.

Our model (`ProjectPathRenderer`, pure, with the project directories passed in; `ProjectFiles` in the gateway):

- under the base directory or any project directory: relative to the base, `..` allowed;
- inside an archive: the absolute entry `/abs/x.jar!/pkg/Foo.java`. **This deliberately differs**: the official
  `relativizeIfPossible` relativises archive entries too (`../../.gradle/.../x.jar!/...`, pinned in
  `ProjectLocationsTest`); `read_file` accepts our absolute form (verified);
- anything else: normalised absolute path;
- every location carries `origin` from `ProjectFileIndex`: `project` (content), `sdk` (an order entry of the file is
  a `JdkOrderEntry`), `library_source`, `library_class`, `other`.

Input accepts the same forms plus `file://`/`jar://` URLs; a file that is neither project content nor in a
library/SDK is rejected with `NOT_FOUND`. Base directory = `guessProjectDir()` (equals the base path in a real
project). In light tests the project base path (`/tmp/unitTest_…`) and the content root (`temp:///src`) are on
different file systems and the official tools render `../../src/a/A.java` there; our tests in light fixtures keep
rendering `a/A.java`. The contract is therefore checked on a heavy fixture with real directories
(`ProjectLocationsTest`: sibling content root, library with a sources jar, class-only library, official `read_file`
on each path we emit).

`mc_safe_delete` refuses targets outside project content (`INVALID_ARGUMENT`); light elements (e.g. a Kotlin class
found by qualified name as its light class) are deleted as their `navigationElement`.

## Languages

- **Java**: everything, in light tests, heavy tests and end to end.
- **Kotlin** (bundled in the test IDE, K2): `KotlinSymbolsTest` passes for find usages (by position, Kotlin
  references to Java, property read/write access), implementations both ways (by qualified name through light
  classes), type hierarchy (Kotlin supertypes, a Kotlin subclass of a Java class) and safe delete (preview refused for
  a used interface, apply of an unused function, apply of an unused property in a class). Kotlin safe-delete conflicts
  are not asked for (modal window, see above).
- **Other languages**: `mc_find_usages` and `mc_find_implementations direction=down` use only language-neutral
  searches. `direction=up` and `mc_type_hierarchy` answer `UNSUPPORTED` without a provider (JVM languages only today).
  `mc_safe_delete` works where the language has a safe-delete delegate or the element is a named element.
- Qualified names are still JVM-only (`JavaQualifiedNameLocator`, step 1).

### Enclosing declaration

`enclosing` is the chain of named, stub-based ancestors (`StubBasedPsiElement` and `PsiNameIdentifierOwner`), e.g.
`Main.total`, leaving out parameters and local variables (review item 3). Which ancestors are local is
language-specific, so it comes through the EP `localDeclarations`: `JvmLocalDeclarationDetector` (UAST: `UParameter`,
`ULocalVariable`) for Java, Kotlin and other JVM languages. A reference in a parameter's type now reports the
function (`use`, not `use.a`; `KotlinSymbolsTest`), and Kotlin local properties are skipped. In languages without a
detector the stub-based rule stays (parameters may appear).

### Access kind

Java's `ReadWriteAccessDetector` also accepts classes and reports their references as `read`; we pass that on.
Methods have no detector, so `access=read|write` on a method is `UNSUPPORTED`.

## Threading

All PSI work runs in `smartReadAction` (waits for indexing, cancellable, restarted after a write action, with fresh
collectors per attempt). Index-not-ready was provoked with `DumbModeTestUtils.startEternalDumbModeTask`: the call
waits and completes after dumb mode ends (`FindUsagesToolsetTest.testWaitsForIndexingInsteadOfFailing`).

## Test runtime

- Platform tests call tools off the EDT (`testing/ToolCalls`) while the EDT test thread dispatches events; with
  `timeoutRunBlocking` on the EDT, the safe-delete switch to `Dispatchers.EDT` would deadlock.
- Undoing a deleted class is a global undo; the IDE asks "Undo Deleting class …?", answered with `TestDialog.OK`.
- In a `HeavyPlatformTestCase`, `createTempDirectory()` lies inside the project base directory, which was a content
  root, so library sources written there became project sources. The test creates its sibling root, jars and stray
  files next to the base directory instead.
- `VfsTestUtil.createFile` failed with `NoSuchFileException` for directories outside the project; plain `java.io`
  plus `refreshAndFindFileByIoFile` works.

## End to end

`runIdeForMcpE2e` now copies `src/mcpE2eFixture` (a Java module with `.idea`/`.iml` and a second content root at
`../mcp-e2e-sibling`) into `build/` on every start, so earlier `apply` calls are undone. No JDK is configured in the
sandbox, so JDK library locations were not exercised end to end (covered by the heavy test with jar libraries).

Safe-delete rules end to end (non-headless sandbox on a private Xvfb, XToolkit, real MCP calls; fixture files in
`src/mcpE2eFixture/project/src/rules`): `apply` on an implementing method (`rules.Hello#greet`) -> `CONFLICT` (our
conflict plus the Java delegate's); `apply` on the unused interface method `rules.Greeter#greet` -> deleted together
with `Hello.greet` (`alsoDeleted`); `apply` on the field `rules.Box#size` -> `CONFLICT` (the getter reads it); `apply`
on the parameter of `Box.touch` -> `UNSUPPORTED`; `apply` on the Kotlin property `rules.Settings#retries` -> deleted.
Each call returned in under a second without interaction; the list of visible top-level windows of the display was
the same before and after every call (no dialog), the screenshots show no dialog, and the IDE log has no error from
the calls. Files on disk matched (`Greeter`/`Hello` without `greet`, `Box` unchanged, `Settings` without `retries`).

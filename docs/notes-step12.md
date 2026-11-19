# Step 12: `mc_pull_up` and `mc_extract_method`; inline examined and not built

Date: 2026-11-19. IntelliJ IDEA 2026.3 (IU-263.7307.200). Goal: three more tools that change code through the IDE's
own refactorings, on the preview/apply core of step 8 (`docs/notes-step8.md`): inline, pull members up, extract
method. The last step of 0.3.0; the version stays 0.3.0.

Rule of the step: a tool is built only when it needs no `@ApiStatus.Internal`/`@Experimental`/`@OverrideOnly` API and
when it opens no dialog and hands over the conflicts before it changes anything.

## Step 0: what the platform and the bundled server offer

### Official tools

The `@McpTool` names of every `mcpToolset` in the IDE's jars (the bundled server and the other bundled plugins) were
scanned for `inline`, `extract`, `pull`, `push` and `refactor`: the only refactoring tool is still `rename_refactoring`
(`extract` occurs only in the database plugin's `list_data_extractors`). No inline, no pull up or push down, no extract
method: nothing to step aside for, no catalog entry. Pinned by `RefactoringsContractTest` (pattern widened, with word
boundaries).

### API, member by member

Classes decompiled with the IDE's Fernflower, which also prints class-retention annotations (`@ApiStatus.*`). All
classes below are in the Java plugin's content module `intellij.java.impl.refactorings` (`visibility="public"`, the
module `mc_move` and `mc_change_signature` already use) or in `intellij.java.impl` and the platform.

#### Inline: not built

| Need | What the IDE has | Verdict |
|---|---|---|
| Method: usages | `InlineMethodProcessor.findUsages` (`@OverrideOnly`) → `InlineMethodProcessorUtil.findUsages` | class `@Internal` |
| Method: conflicts | `InlineMethodProcessor.preprocessUsages` (`@OverrideOnly`) → `InlineMethodProcessorUtil.collectConflicts` (overrides, Javadoc and reflection uses, fall-back to single-return form, inaccessible members and super calls) | class `@Internal`; the processor keeps them private and runs them under `runProcessWithProgressSynchronously` (a modal progress window in the IDE) |
| Method: inliners for non-Java call sites | `InlineMethodProcessorUtil.initInliners` → `GenericInlineHandler.initInliners` | both `@Internal`; set only inside `preprocessUsages`, so `executeEx` without it hands Kotlin call sites a null inliner map |
| Method: pre-checks | `InlineMethodHandler.createInlineContext`, `checkRecursive`; `InlineMethodProcessor.checkBadReturns` | public, but `createInlineContext` runs a modal progress for abstract methods and the handler shows its errors with `showErrorHint` (editor) |
| Method: the deprecated public statics | `InlineMethodProcessor.addInaccessibleMemberConflicts`, `checkUnableToInsertCodeBlock` | `@Deprecated`, delegate to the internal util, cover a fraction of the analysis |
| Constant/field | `InlineConstantFieldProcessor`: `findUsages` `@OverrideOnly`, conflicts computed inside `preprocessUsages` only (private), `myInliners` the same | no public way to the conflicts |
| Local variable | `InlineLocalHandler.doInline(ActionContext, PsiVariable, ref, InlineMode)` returns a `ModCommand`: errors as `ModDisplayMessage`, the conflict "variables used in the initializer are changed" as a `ModChooseAction` of `InlineLocalStep`s (`getMode()` public), the change as `ModUpdateFileText` | public, no annotation: feasible |

The interactive path (`BaseRefactoringProcessor.run()` with an overridden `showConflicts`) was rejected for the same
reasons as in step 8: the search and the conflict analysis run under a modal progress window on the EDT, the search is
not cancelled with the MCP call, and `preprocessUsages` cannot be called directly. So inline of methods and constants
cannot both open no dialog and hand over the IDE's conflicts with public API: **not built**. Inline of a local
variable alone is possible through the public `ModCommand` path, but a tool named `mc_inline` that cannot inline a
method would mislead, and an agent inlines a local variable correctly by editing one method; it is left for a later
step if a need shows up.

#### Pull members up: built (pull up only)

| Need | API (all public, without `@ApiStatus` at class or member level) |
|---|---|
| Members | `MemberInfo(PsiMember)`, `setChecked`, `setToAbstract` (`MemberInfoBase`) |
| Conflicts | `PullUpConflictsUtil.checkConflicts(infos, subclass, superClass, targetPackage, targetDirectory, InterfaceContainmentVerifier)` with `PullUpProcessor.checkedInterfacesContain` as the verifier, exactly as `JavaPullUpHandler.checkConflicts` does before its `ConflictsDialog` |
| Usages | references to moved static members (`ReferencesSearch`), what the processor's `@OverrideOnly` `findUsages` collects |
| Execution | `PullUpProcessor.moveMembersToBase()` and `moveFieldInitializations()` (public; Extract Superclass uses them the same way), then `PullUpHelper.INSTANCE.forLanguage(Java).createPullUpHelper(processor).updateUsage(element)` |
| Javadoc policy | `DocCommentPolicy(JavaRefactoringSettings.PULL_UP_MEMBERS_JAVADOC)`, the user's setting, as the dialog uses it |

Why not `executeEx`: `PullUpProcessor.performRefactoring` ends with `processMethodsDuplicates`, which searches the
hierarchy under `runProcessWithProgressSynchronously` (modal progress) and then calls
`MethodDuplicatesHandler.invokeOnScope`, which, when it finds code duplicating a moved method, opens
`ReplacePromptDialog`/`Messages.showYesNoDialog` for each duplicate, asynchronously after the refactoring. The tool
runs the rest of `performRefactoring` (three public calls) inside its own write action and global command instead.
Left out with it: refactoring listener events and the duplicate replacement.

Kotlin: `PullUpHelper` has a Kotlin factory, but `createPullUpHelper` casts the target class to `KtElement` and threw
`ClassCastException` for a Java target class in the platform test (the processor itself would do the same for a Kotlin
reference to a moved static member). And `Car.SEATS` in Kotlin no longer compiles once `SEATS` is declared in the
superclass (Kotlin does not reach Java statics through a subclass). So a non-Java reference to a moved static member
is a **conflict** ("This Kotlin reference to SEATS is not updated by the IDE and would no longer compile"); Kotlin calls
of a pulled-up instance method need no change and stay as they are (tested). Kotlin declarations are `UNSUPPORTED`
(step 8: the Kotlin refactoring module is internal).

Push down: `PushDownProcessor` (platform) delegates usages (`findInheritors`, which also collects functional
expressions) and conflicts (`checkSourceClassConflicts`, `checkTargetClassConflicts`) to `PushDownDelegate`, which is
`@ApiStatus.Internal`; the public `PushDownConflicts` covers Java only, and without inheritors `preprocessUsages` asks
with `Messages.showYesNoCancelDialog` whether to create a subclass. Rebuilding the delegate's search would copy
internal logic: **not built**.

#### Extract method: built (current implementation, no editor)

The old `ExtractMethodProcessor` needs an `Editor` and opens its dialog; the current implementation
(`com.intellij.refactoring.extractMethod.newImpl`) is a set of public Kotlin classes without `@ApiStatus`
annotations, of which the IDE's own flow (`MethodExtractor.extract`, `doTestExtract`) is composed. The in-place part
(`InplaceMethodExtractor`, templates, `ExtractMethodPopupProvider`, `restartInDialog`) is not used.

| Need | API |
|---|---|
| Statements or expression in the range | `ExtractSelector().suggestElementsToExtract(file, range)` (empty: not a set of statements or an expression; the IDE's message `RefactoringBundle "selected.block.should.represent.a.set.of.statements.or.an.expression"`) |
| Inference (parameters, output, exceptions, static) | `ExtractMethodPipeline.findAllOptionsToExtract(elements)` (`findExtractOptions` plus each enclosing class as target); throws `ExtractException(message, file, ranges)` for code the IDE cannot extract (several exits, an observable throw), which becomes conflicts at those places |
| Static on request | `ExtractMethodPipeline.withForcedStatic(CodeFragmentAnalyzer(elements), options)`, null when instance members cannot become parameters (a conflict) |
| Signature for the preview and name check | `MethodExtractor().prepareRefactoringElements(options).method` (non-physical), `ConflictsUtil.checkMethodConflicts(targetClass, null, method, conflicts)` as the IDE's `suggestSafeMethodNames` does |
| Execution | `MethodExtractor().extractMethod(options)` (its own write action: adds the method, replaces the code with the call) |

Target class: the innermost class that is not anonymous, as the IDE preselects (`findAllOptionsToExtract` lists the
nearest first). Visibility default private, static as the IDE decides (`withDefaultStatic`: in a static context, or a
`this`/`super` call). Not done: the duplicate search and replacement the in-place flow offers afterwards, choosing
another target class, parameter renaming or folding, nullability choices other than the IDE's default.

### No dialog, focus, cancellation; Plugin Verifier

Both tools run through `PsiRefactoringRunner`: analysis in a smart read action in the background (waits for indexing,
cancelled with the MCP call, restarted by write actions), execution on the EDT in one global command
(`UndoableCommand`), the same path in tests and in the IDE. In the sandbox the visible window list was the same before
and after every `apply` (the IDE frame only), also with the modal Settings dialog open during an `apply`.

Plugin Verifier 1.411 against IU-263.7307.200: Compatible; its report has no internal, experimental, override-only
or deprecated API usages (only the verdict, dependency and telemetry files).

## Design

- The shared core is generalised over what a tool refactors: `RefactoringTargets<Q, T>` locates the target from the
  client's query (`SymbolTargets` for declarations, `SourceRangeTargets` for code given by positions);
  `RefactoringRequest<Q, D>`, `RefactoringGateway<T, D>`, `RefactoringActions<Q, T, D>`, `RefactoringOutcome<T>`.
  `mc_move` and `mc_change_signature` use `SymbolTargets`.
- `PreparedRefactoring` and `RefactoringAnalysis` gain `summary` (what the refactoring creates when the request does
  not say it, e.g. the extracted method's signature and the exact code taken); an extraction has no usages, only the
  file. `PsiRefactoringRunner.writableFile` checks a client path like `writableDeclaration` checks a symbol.
- Output: `RefactoringOutput` has `symbol` (declaration tools) or `range` (`mc_extract_method`) and `summary`, the rest
  unchanged (`ofSymbol`, `ofRange`).
- `Visibility` moved to `common/refactoring`, its Java modifier to `common/refactoring/java/JavaVisibility.kt`,
  `VisibilityInput` to `toolsets/refactoring`; `SourceRange` (pure) in `common/source`, its offsets in
  `common/source/psi`.
- `pullup/`: `MemberPullUp`, `PullUpMembers`; `pullup/psi`: extension point `pullUp` (`PullUpSupport`),
  `PsiPullUpGateway`, `pullUpMembersFor`; `pullup/java`: `JavaPullUpSupport`, `JavaPullUpTargets`.
- `extractmethod/`: `MethodExtraction`, `ExtractMethod`; `extractmethod/psi`: extension point `methodExtraction`
  (`MethodExtractionSupport`), `PsiMethodExtractionGateway`, `extractMethodFor`; `extractmethod/java`:
  `JavaMethodExtractionSupport`.
- `toolsets/pullup/PullUpToolset` (`pull_up`), `toolsets/extractmethod/ExtractMethodToolset` (`extract_method`).
- Konsist: both are feature packages and refactoring features; the pull-up, extract-method, `ConflictsUtil`,
  `JavaRefactoringSettings`, `PsiMember` and `PsiJavaFile` APIs count as Java refactoring APIs; the language-neutral
  rule for `common.refactoring` excludes its `java` package. `LayeringTest` had reached detekt's class size limit; the
  rules for the optional external-system, Maven and test runner APIs moved to `OptionalPluginApisTest`.
- Settings page: 19 rows (Status and 18 tools), within the limit of 20, so the limit stays.

## Tool surface

`mc_pull_up`: member by `path`/`line`/`column` or `qualifiedName`; `targetClass` (qualified or simple name of a
superclass or interface of the member's class; default: the superclass when it is a project class, else the only
project interface); `withMembers` (simple names of more members of the same class; an overloaded name is
`AMBIGUOUS`); `makeAbstract` (methods: abstract in the target, body stays); an interface target without default
methods (Java < 8) makes methods abstract. Refused: not a member, constructors, initializers, enum constants
(`UNSUPPORTED`); a target that is no supertype, a library or read-only target, `makeAbstract` without a method
(`INVALID_ARGUMENT`); Kotlin members and targets (`UNSUPPORTED`). Conflicts are the IDE's (members left behind,
accessibility, the target already has it, a concrete subclass would inherit a new abstract method, a broken functional
interface, a non-static field into an interface) plus non-Java references to moved static members and read-only files.

`mc_extract_method`: `path`, `startLine`, `endLine` (required), `startColumn` (default 1), `endColumn` (exclusive,
default the end of the line), `methodName` (required, a Java identifier), `visibility`, `makeStatic`. The range must
lie in the file (`INVALID_ARGUMENT`) and cover whole statements or one expression (`INVALID_ARGUMENT` with the IDE's
message). Conflicts: the IDE's `ExtractException` (several exits, observable throw) at the places it names, a method of
that signature in the class, static impossible or required. `summary`, e.g. `private int sumOf(int[] amounts), from
11:9-14:10`, shows the exact code the IDE takes (whitespace trimmed; an `if` whose branch only exits shrinks to its
condition). `fileCount` 1, `referenceCount` 0.

Both: `action` `preview` (default) or `apply`, paging of the affected files, conflicts listed up to 50, `apply`
analyses again and refuses with `CONFLICT`, one undoable command. Command names (Edit | Undo): "Pull {members} up to
{target}" / "把 {0} 上移到 {1}", "Extract Method {name}" / "提取方法 {0}".

## Tests

Platform tests (`PullUpToolsetTest` 10, `ExtractMethodToolsetTest` 8): preview changes nothing; apply and undo; paging;
conflicts (members left behind and moved along, abstract method another subclass lacks, instance field into an
interface, Kotlin reference to a static, several exits, existing signature, static context); not found; unsupported
(Kotlin member, Kotlin file, class instead of member); invalid input; interface targets (default and abstract); JSON
through the server's tool. `RefactoringDetailsTest`, `RefactoringActionsTest` (summary, generic targets),
`RefactoringsContractTest` (official tools, Java's pull-up helper), `StatusToolsetTest`, `McpCompanionConfigurableTest`,
`LayeringTest`, `RefactoringLayersTest`, `OptionalPluginApisTest`.

## End to end

Sandbox `runIdeForMcpE2e -PmcpE2ePort=64412 -PmcpE2eAwtToolkit=XToolkit` (JPS fixture with the new `src/hier`,
`src/garage` and `src/extract`) on a private Xvfb `:112` (1440x900, openbox), Python Streamable-HTTP client
(`build/e2e-step12/mcp.py`, `batch.py`); answers in `round1.txt` (previews and refusals), `round2.txt` (applies),
exchanges in `build/e2e-step12/*.json`, screenshots in `build/e2e-step12/screenshots/` (all under the worktree's
`build/`, not committed).

| Call | Result |
|---|---|
| `mc_status`, `tools/list` | 19 toolsets available, 74 tools of which 19 `mc_` |
| `mc_pull_up hier.Car#describe` preview / page `limit=1 offset=1` | `Car.java`, `Vehicle.java`, 0 references, no conflicts (1.1 s first call) / the second file |
| `hier.Car#WHEELS` preview → apply | 3 files, 2 references; applied: `Vehicle.WHEELS` in `garage/Garage.java` with the import added |
| `hier.Car#faster` preview / apply | 2 conflicts at `speed` ("private and will not be accessible", "not moved to the superclass") / `CONFLICT` |
| `faster` with `withMembers=["speed"]` | no conflicts; applied, both in `Vehicle` |
| `hier.Car#SEATS` (used in `GarageReport.kt`) | conflict at `GarageReport.kt:6:67` |
| `describe` `makeAbstract=true` | "Concrete class hier.Bike will inherit a new abstract method" |
| `describe` into `Named` | no conflicts |
| target `hier.Bike`, `java.lang.Object`; `Car#nope`; `withMembers=["nothing"]`; Kotlin function; class `hier.Car` | `INVALID_ARGUMENT` (listing `hier.Vehicle, hier.Named`) x2, `NOT_FOUND` x2, `UNSUPPORTED` x2 |
| `mc_extract_method Invoice.java 11-14 sumOf` preview → apply | `private int sumOf(int[] amounts), from 11:9-14:10`; applied: `int sum = sumOf(amounts);` and the new method |
| `sum - discount` by columns, `public`, `makeStatic=true` | `public static int netOf(int discount, int sum), from 15:19-15:33` |
| lines 21-22 (two `if`s with `return` and `break`) | conflict "There are multiple exit points." at 21:13; `apply` → `CONFLICT` |
| `methodName=count` | conflict "Method count(int[]) is already defined in class extract.Invoice" at the existing method |
| half a statement; lines 80-81; `class`; Kotlin file; missing file | `INVALID_ARGUMENT` x3, `UNSUPPORTED`, `NOT_FOUND` |
| `mc_diagnostics` on the five changed files after the applies | 0 errors |

No dialog and no new window during any call (`windows-before.txt`, `windows-after-apply.txt`: the IDE frame only).
Undo with Ctrl+Z in the IDE frame: "Undo Extract Method sumOf?" (`undo-extract-ask.png`), "Undo Pull faster, speed
up to Vehicle?", "Undo Pull WHEELS up to Vehicle?"; after the three undos and Save All the sandbox copies of
`src/hier`, `src/garage` and `src/extract` were identical to the fixture (`undo-check.txt`). After the last code
change the round was repeated on a fresh sandbox (`final-round.txt`, `final-*.json`): every answer identical, and the
three undos restored the files again.

Chinese (sandbox `options/ide.general.xml` with `LocalizationStateService` zh-CN, restored afterwards): the IDE's
conflict message "有多个出口点。", the undo prompt "撤消把 describe 上移到 Vehicle?" (`undo-zh.png`; that `apply` ran
while the modal Settings dialog was open, and worked).

Settings page at 1440x900: English (`settings-en-1440x900.png`) and Chinese (`settings-zh-1440x900.png`): 19 rows with
"Pull Members Up"/"上移成员" and "Extract Method"/"提取方法" after Change Signature, no scroll bar, no cut text.

## Limits and what is not proven

- Java only; Kotlin declarations and files are `UNSUPPORTED`. A Kotlin reference to a moved static member is a
  conflict rather than updated.
- `mc_pull_up` moves members of one class per call to one target; it does not move `implements` clauses, does not push
  down, and does not offer to replace code duplicating a moved method. Refactoring listener events of the processor
  are not fired.
- `mc_extract_method` does not search or replace duplicates, does not let the caller choose the target class, rename
  or drop parameters, or change the inferred return type; the method goes into the innermost named class.
- Inline: not built (above). Push down: not built.
- An `apply` with an editor focused was not repeated for these tools (step 8 showed the shared command is global either
  way). Not run: Gradle and Maven fixtures, Windows, macOS, large projects; cancelling a running analysis end to end.

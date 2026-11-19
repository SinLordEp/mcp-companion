# Step 8: `mc_move` and `mc_change_signature`, platform notes

Date: 2026-11-19. IntelliJ IDEA 2026.3 (IU-263.7307.200). Goal: two tools that change code through the IDE's own
refactorings instead of text edits: move a class to another package (optionally another source root) and change a
method's signature, each updating every reference. Version 0.3.0.

## Step 0: what the platform and the bundled server offer

### Official tools

Every `mcpToolset` declared in the IDE's jars (64 declarations, the bundled server's and those of the other bundled
plugins) was listed and the public methods of each toolset class read with `javap`. The only refactoring tool is
`rename_refactoring` (`RefactoringToolset`); `move_changes_to_changelist` is about VCS change lists. No move and no
signature change: nothing to step aside for, no catalog entry. Pinned by `RefactoringsContractTest`.

### Java: which public API avoids every dialog

Decompiled with the IDE's own Fernflower; annotations read with `javap -v`. The interactive entry point
`BaseRefactoringProcessor.run()` → `doRun()` is unusable for a tool:

- usages are searched in `ProgressManager.runProcessWithProgressSynchronously` on the EDT: a modal progress window;
- `preprocessUsages` → `showConflicts`: in the IDE a `ConflictsDialog` **only when an interactive callback was set**
  (`setPrepareSuccessfulSwingThreadCallback`); without one the conflicts are silently ignored and the refactoring
  goes ahead. In unit-test mode the same call throws `ConflictsInTestsException`. So neither mode hands the conflicts
  to the caller, and tests and IDE behave differently on this path;
- `isPreviewUsages` may open the usage view; `ensureElementsWritable` may ask to make files writable;
- the Java Change Signature's `preprocessUsages` asks with `Messages.showYesNoDialog` about weaker visibility of
  overriding methods and covariant return types, and `setupDefaultValues` opens `DefaultValueChooser` for new
  parameters without a default value (all skipped in unit-test mode, shown in the IDE);
- `ChangeSignatureProcessorBase.collectConflictsFromExtensions` runs under `ActionUtil.underModalProgress`.

What the tools use instead, all public and without `@ApiStatus` annotations at class or member level (checked
member by member; the Plugin Verifier agrees, see the end):

| Need | API |
|---|---|
| Move setup | `JavaRefactoringFactory.getInstance(project).createSourceRootMoveDestination(package, root)` (`AutocreatingSingleSourceRootMoveDestination`: creates missing package directories), `createMoveClassesOrPackages(elements, destination, false, false)` → `MoveClassesOrPackagesRefactoring` |
| Move usages | `Refactoring.findUsages()` (public interface; the processor's own search), then `UsageViewUtil.removeDuplicatedUsages`, as the processor's `preprocessUsages` does |
| Move conflicts | `MoveClassesOrPackagesProcessor.detectConflicts(usages, conflicts, elements, targetPackage, destination)` (public static: accessibility after the move, module dependencies) |
| Move execution | `Refactoring.doRefactoring(usages)` → the processor's `execute`: its command, local history, refactoring listeners and helpers, write action |
| Signature setup | `new ChangeSignatureProcessor(project, method, false, visibility, name, returnType, ParameterInfoImpl[])`, `ParameterInfoImpl.create(oldIndex)/createNew().withName().withType().withDefaultValue()` |
| Signature usages | `ChangeSignatureProcessorBase.findUsages(changeInfo)` (public static; asks every language's `ChangeSignatureUsageProcessor`) |
| Signature conflicts | every `ChangeSignatureUsageProcessor.findConflicts(changeInfo, usages)` directly (the same loop as `collectConflictsFromExtensions`, without its modal progress) and `RenameUtil.addConflictDescriptions` |
| Signature execution | `BaseRefactoringProcessor.executeEx(usages)` (public) → `execute` as above |

Not used: `MoveClassesOrPackagesUtil` (class `@ApiStatus.Internal`), `JavaChangeInfoImpl`'s `@Internal` fields and
`setPropagateVisibility`, `BaseRefactoringProcessor.findUsages/preprocessUsages/execute` (`@OverrideOnly`), the
factory's `createChangeSignatureProcessor` (it does not hand out the `ChangeInfo` that conflicts need).

So both tools search and collect conflicts in a background smart read action (waits for indexing, cancelled with
the MCP call, restarted by write actions) and return the conflicts before anything is executed. Only an analysis
without conflicts is executed, on the EDT. The path is the same in tests and in the IDE, because neither `run()` nor
`preprocessUsages` nor `showConflicts` is called.

Undo: the processors mark their command global only when no editor has the focus (`isGlobalUndoAction` reads the
focused data context). An MCP call cannot know what has the focus, so the runner wraps the execution in a command of
its own (`Move <class> to <package>`, `Change Signature of <method>`, in the UI language) marked with
`CommandProcessor.markCurrentCommandAsGlobal`; the processor's command nests into it. One Edit | Undo reverts all
files from anywhere.

Consent: neither tool asks the user, like `mc_safe_delete`: they change only project files, refuse while the IDE
reports conflicts, preview by default and are one undoable command.

### Kotlin

- `K2MoveDeclarationsRefactoringProcessor`, `K2MoveOperationDescriptor` and `KotlinChangeSignatureProcessor` are not
  annotated, but they live in the Kotlin plugin's content module `intellij.kotlin.refactorings`, which its
  `plugin.xml` declares `<idea-plugin visibility="internal">`: not available to other plugins. Building a
  `KotlinChangeInfo` also needs the Analysis API (`KaSession`, much of it `@KaExperimental`). **Not done**: Kotlin
  declarations are `UNSUPPORTED`, said in both tool descriptions; no `org.jetbrains.kotlin` dependency was added.
- What works across languages without it: Kotlin references to a moved Java class (imports, types) and Kotlin call
  sites of a changed Java method are rewritten, because the Java processors find usages through the platform's
  reference search and Kotlin's `ChangeSignatureUsageProcessor` (registered by the Kotlin plugin on the public
  extension point) rewrites its own call sites for a Java `ChangeInfo`. Tested in platform tests and end to end.
- Java code calling Kotlin declarations: not applicable (Kotlin targets are refused).

## Tool surface (decided)

Both: `action` `preview` (default) or `apply`; the symbol as `path`/`line`/`column` or `qualifiedName`; `limit`/
`offset` page the affected files (sorted by path). Output (`RefactoringOutput`): `canApply`, `applied`, `fileCount`,
`referenceCount`, `files` (path and references per file), `conflictCount`, `conflicts` (message, path, line, column,
line text, enclosing declaration; at most 50 listed), `changedFiles` after `apply` (paths as they are after the
change, recorded from the PSI change events of the command), `hasMore`, `nextOffset`. `apply` analyses again; with
conflicts it fails with `CONFLICT` and changes nothing; if the code changed between the analysis and the execution
it analyses again, three times at most.

`mc_move`: `targetPackage` (required; validated as a qualified name, Java keywords refused), `targetSourceRoot`
(optional project path of a source or test source folder; default: the class's current root). Refused: a class of
that name or a file of that name already in the target package (`CONFLICT`, also in preview); a target root that is
not a directory (`NOT_FOUND`) or not a source root (`INVALID_ARGUMENT`); the same package and root
(`INVALID_ARGUMENT`); nested and local classes, Kotlin and other languages (`UNSUPPORTED`); library or read-only
declarations (`INVALID_ARGUMENT`). Moving within the same package into another root changes no references (as the
processor does). Comments, strings and non-Java files are not searched (the IDE's defaults).

`mc_change_signature`: `parameters` (the complete new list; omitted: unchanged), `returnType`, `visibility`
(`public`, `protected`, `package_private`, `private`). Each parameter: `from` (current parameter by name, or by 0-based
position written as a string) keeps it and its arguments, with optional new `name`/`type`; without `from` it is new
and needs `name`, `type` and `defaultValue` (inserted at existing call sites). Validation: types and default values
must parse in the method's context and class types must resolve (`INVALID_ARGUMENT` otherwise); a current parameter
at most once; unique names; Java identifiers; no return type for constructors; something must change. The name stays
(`rename_refactoring`).

Overriding rule, the same as `mc_safe_delete`: a method that overrides or implements another one is a conflict that
names the topmost declarations (`findDeepestSuperMethods`); changing the topmost one changes the methods overriding
it. Reason: changing a method in the middle of a hierarchy alone breaks the override, and silently redirecting the
call to another method than the one the agent named would surprise it. Because `preprocessUsages` is never run:
overriding methods keep their visibility (the IDE would ask), overriding methods get the new return type (the IDE
would ask about covariant ones), and new parameters always come with the caller's default value.

## Design

- `common/refactoring` (pure): `RefactoringRequest`, `AffectedFile`, `RefactoringConflict`, `RefactoringAnalysis`,
  `RefactoringAttempt`, `RefactoringOutcome`, the gateway interface `RefactoringGateway<D>` and `RefactoringActions`
  (`PreviewRefactoring`, `ApplyRefactoring` on `ActionRegistry`: paging, conflict cap, refusal, retries).
- `common/refactoring/psi`: `PreparedRefactoring` (usages, conflicts, declaration files, command name, how to
  execute), `PsiRefactoringRunner` (writable-declaration check, smart read action, affected files, read-only files as
  conflicts, staleness check, the global command), `ChangedFilesRecorder` (PSI change listener during the command).
- `move/`: `ClassDestination` (validation), `MoveClass` (use case); `move/psi`: extension point `classMove`
  (`ClassMoveSupport`), `PsiClassMoveGateway` (source-root check, picks the support), `moveClassFor`; `move/java`:
  `JavaClassMoveSupport` (in `mcp-companion-java.xml`).
- `changesignature/`: `SignatureChange`, `ParameterChange`, `ParameterRef`, `Visibility` (validation),
  `ChangeSignature` (use case); `changesignature/psi`: extension point `signatureChange` (`SignatureChangeSupport`),
  `PsiSignatureChangeGateway`, `changeSignatureFor`; `changesignature/java`: `JavaSignatureChangeSupport`,
  `JavaSignature` (parameters, types, visibility in the method's context).
- `toolsets/refactoring`: the shared input `RefactoringActionInput`, output `RefactoringOutput` and description texts;
  `toolsets/move/MoveToolset`, `toolsets/changesignature/ChangeSignatureToolset` (+ `ParameterInput`,
  `VisibilityInput`). A further refactoring tool needs a details type, a gateway with its language extension point,
  and a toolset; preview, apply, paging, conflicts, undo and the output are shared.
- Konsist: `move` and `changesignature` are feature packages; their use cases import neither their `psi` nor their
  `java` packages, the `psi` gateways not the `java` ones; `common.refactoring` uses no Java plugin API; Java
  refactoring classes, `PsiNameHelper`, `PsiModifier` and `VisibilityUtil` count as Java plugin APIs.
- Settings page: two new rows (Move Class, Change Signature); the table now shows up to 20 rows without scrolling,
  room for the tools of the next steps.

## End to end

Sandbox `runIdeForMcpE2e -PmcpE2eAwtToolkit=XToolkit` (JPS fixture, new files under `src/refac` and `src/catalog`) on
a private Xvfb `:88` (1440x900, openbox), a Python Streamable-HTTP client on 127.0.0.1:64399. Requests and responses
are in `build/e2e-step8/*.json`, screenshots in `build/e2e-step8/screenshots/` (both under the worktree's `build/`,
not committed).

| Call | Result |
|---|---|
| `mc_status` | `move` and `change_signature` available |
| `mc_move refac.Order → shop.model` preview | 3 files: `Order.java` (0), `OrderReport.kt` (1), `OrderService.java` (1); no conflicts; 0.59 s (first call) |
| same by position with `limit=1, offset=1` | `OrderReport.kt`, `hasMore`, `nextOffset=2` |
| `→ catalog` (has a `catalog.Order`) | `CONFLICT`, nothing changed |
| `refac.UsesHidden → shop` preview | 2 conflicts ("uses package-private class refac.Hidden", "... method Hidden.secret()") at `Hidden.java` 4:7 and 5:16; `apply` → `CONFLICT` |
| `mc_move refac.Order → shop.model` apply | moved in 0.96 s; `changedFiles`: `OrderReport.kt`, `OrderService.java`, `src/shop/model/Order.java`; Java and Kotlin got `import shop.model.Order` |
| `targetSourceRoot=../mcp-e2e-sibling/src` (content root outside the project directory), `sib.orders` | applied, `../mcp-e2e-sibling/src/sib/orders/Order.java` |
| `targetSourceRoot=src/refac` | `INVALID_ARGUMENT` (not a source root) |
| `mc_change_signature refac.Pricing#total`: `(unit, long quantity, int discount = 0)`, `returnType=long`, preview | 3 files, 4 usages (2 Java calls, 1 Kotlin call, the parameter use in the body) |
| `parameters=[{"from":1},{"from":0}]` (numbers, not strings) | accepted |
| `returnType=Money` | `INVALID_ARGUMENT`, "'Money' is not a known type here" |
| `visibility=private` preview / apply | 1 conflict ("... won't be accessible from method OrderService.price(Order)"), `canApply=false` / `CONFLICT` |
| the change applied | `long total(String unit, long quantity, int discount)`; `pricing.total("kg", order.quantity(), 0)` in Java and Kotlin |
| Kotlin function / Kotlin class as target | `UNSUPPORTED` with the reason |

No dialog, no new window and no focus change during any call (window list before and after each `apply` the same:
the IDE frame only; `after-move-apply.png`). Undo: Ctrl+Z in the project view asked the IDE's usual "Undo Move Order
to shop.model?" (`undo-move.png`, `undo-sig.png`) and reverted every file of the command, including the Kotlin file and
the created package; again with `OrderService.java` open and focused in an editor during the `apply` and the undo
(`sig-applied-editor.png`, `undo-in-editor.png`). In Chinese the command reads "撤消移动 Order 到 shop.model?"
(`undo-zh.png`).

Settings page at 1440x900: English (`settings-en-1440x900.png`) and Chinese (`settings-zh-1440x900.png`, sandbox
with `LocalizationStateService` zh-CN): twelve rows with "Move Class"/"移动类" and "Change Signature"/"修改签名" after
Safe Delete, all visible without scrolling, no cut text.

## Limits and what is not proven

- Java only. Kotlin declarations are `UNSUPPORTED` (see Kotlin above); Groovy and Scala classes are refused the same
  way. Kotlin code that uses the Java declaration is updated (tested); Kotlin call sites are not checked for
  conflicts by Kotlin's processor beyond what it reports (the private-visibility case was reported by Java's).
- `mc_move` moves top-level classes only (no nested classes, members, packages or several classes at once) and does
  not search comments, strings or non-Java files (e.g. a class name in XML or a properties file stays). A class that
  shares its file with other top-level classes was not tested.
- `mc_change_signature` does not change exceptions, does not create delegates, cannot rename the method, and adapts
  no caller to a new return type: changing `int` to `long` left `int price() { return total(...) + ... }` not
  compiling, as the IDE's own refactoring does. Run `mc_diagnostics` after it. Overriding methods keep their
  visibility and take the new return type (the IDE would ask).
- The `symbol` in an `apply` result is where the target was before the refactoring.
- The processors' own write action runs with `runWriteActionWithCancellableProgressInDispatchThread`; on a very large
  refactoring the IDE may show its progress window for the write action. Not seen on the fixture (all `apply` calls
  under 2 s). The search and conflict analysis are cancelled with the MCP call; the execution, once started, is not
  (a half-done refactoring would be worse).
- A conflict the IDE's analysis does not report is not found either; the tool adds only read-only files and the
  overriding rule. Module-descriptor (`module-info.java`) conflicts of a move are checked by
  `detectConflicts`; the processor's additional `ModuleInfoUsageDetector` check is part of an `@Internal` class and
  is not run.
- Not run: Gradle and Maven fixtures, Windows, macOS, large projects.

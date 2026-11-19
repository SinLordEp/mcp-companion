# Step 11: structural search, Spring and HTTP Client, platform notes

Date: 2026-11-19. IntelliJ IDEA 2026.3 (IU-263.7307.200). Planned: three independent tools for 0.3.0, each built only
when public API carries it and the sandbox IDE proves it. Built: `mc_structural_search`. Not built: the Spring view
(`mc_spring`) and the HTTP Client. The reasons are below; nothing of the two is in the code.

## Step 0: what the bundled server offers

`StructuralSearchContractTest` pins the official tools of the test setup whose names touch searching, frameworks or
HTTP (all `mcpToolset` registrations of the IDE's descriptors, read by reflection; the test setup loads the IDE's
Ultimate features):

| Tool | What it does | Consequence |
|---|---|---|
| `search_text`, `search_regex` | Text or regular expression over file contents | Characters, not syntax: no variables, types, counts, formatting-independent matching |
| `search_symbol`, `search_file` | Declarations by name; files by name or glob | Names only |
| `*_inspection_kts*` (6 tools) | Write, compile and run a Kotlin inspection script | A program, not a search template; not served in the sandbox |
| `spring_web_controllers`, `spring_web_endpoints`, `spring_web_endpoint_info`, `spring_webflux_functional_routes` (module `intellij.spring.mvc.ai`, `visibility="internal"`) | Spring MVC/WebFlux controllers, endpoints with filters, one endpoint's contract | The endpoint half of the planned Spring tool is official in an IDE with Ultimate features |
| `swagger_endpoints`, `swagger_endpoint_info` (OpenAPI plugin) | Endpoints of OpenAPI specifications | - |
| `get_configuration_files`, `get_configuration_properties`, `get_configuration_values` (Spring Boot, `intellij.spring.boot.core.ai`) | Spring Boot configuration | Not about beans |

Nothing searches by syntax tree, lists Spring beans or their injections, or sends an HTTP request.

## The licence gate: why Spring and the HTTP Client could not be proven

IntelliJ IDEA 2026.3 is one distribution; its paid features live in the plugin `com.intellij.modules.ultimate` and
everything that depends on it. The IntelliJ Platform Gradle Plugin (2.20.1, `PrepareSandboxTask.disabledPlugins`)
writes `com.intellij.modules.ultimate` into the sandbox's `disabled_plugins.txt` unless a `subscriptionKey` (an
`idea.key` licence file) is configured. The sandbox log then lists `intellij.spring`, `intellij.spring.core`,
`intellij.spring.boot`, `intellij.spring.mvc.*`, `intellij.microservices.*` and the rest as excluded, the title bar
shows "Unlock Ultimate", and:

- a Spring Boot module (Spring 6.2.11 / Boot 3.5.5 jars resolved from the Gradle cache into a throwaway fixture module)
  showed no Spring gutter icons or bean model (`build/e2e-step11/screenshots/x-spring-open.png`);
- an `.http` file showed "Activate IDE license to use HTTP Client" above the editor
  (`build/e2e-step11/screenshots/x-http-open.png`).

A licence key or a trial needs the user's JetBrains Account, which this step must not use. Unit tests do load the
Ultimate features, but the brief asks for proof in the sandbox (test mode and the real IDE differ), so both tools
were not built. The fixture module, its jar resolution and the `.http` fixture were removed again.

### Spring view (not built)

What the check found, for a later step with a licensed sandbox (`intellijPlatform { ... prepareSandbox
subscriptionKey }`, supplied by the user):

- Public API: plugin `com.intellij.spring`, content modules `intellij.spring` and `intellij.spring.core` have
  `visibility="public"`; `intellij.spring.core.shared` is internal. `SpringManager` (`getAllModels`,
  `getCombinedModel`), `CommonSpringModel` (`getAllCommonBeans`, `processByClass`, `getActiveProfiles`),
  `SpringModelSearchers` (`findBeans`, `findBean`), `SpringModelSearchParameters`, `SpringBeanPointer`
  (`getName`, `getBeanClass`, `getEffectiveBeanTypes`, `getContainingFile`, `getSpringBean`), `CommonSpringBean`
  (`getSpringScope`, `getProfile`, `isPrimary`), `SpringProfile`, `SpringBeanScope`, `SpringAutowireUtils`
  (`getAutowireAnnotationProperties`: field and setter injection) carry no `@ApiStatus` annotation (checked with
  `javap -v`, member by member). Constructor injection would need `SpringAutowireUtils.constructorInjectorHandler()`,
  whose type lives in `com.intellij.microservices.jvm`, or resolving constructor parameters with `findBeans`.
- Spring MVC: `intellij.spring.mvc.core` and `intellij.spring.mvc.impl` are `visibility="internal"` (request
  mappings unusable). The platform's `EndpointsProvider` (`com.intellij.microservices.endpoints`, in
  `intellij.platform.lang`, no annotations; only `getEndpointData` is deprecated) is public, but every provider for
  Java servers (Spring MVC, JAX-RS, Micronaut, Ktor) is an Ultimate feature, and with them the official
  `spring_web_endpoints` tools exist. A bean tool would be the only part left to build.

### HTTP Client (not built)

- `mc_run start_at` on a request line of an `.http` file: `NOT_FOUND`, and the official `get_run_configurations`
  lists no run point either. `HttpRequestRuntimeConfigurationProducer.createConfigurationFromContext` returns nothing
  unless `JbaLicenseService.requiresLicense()` is false and `HttpClientSafeMode.isExecutionAllowed` (trusted file)
  holds; in the sandbox the licence check fails (editor banner above). The run profile state
  (`RunHttpRequestProfileState`, `execute()` is `@ApiStatus.Internal`) uses an `HttpClientRequestProcessHandler` and
  shows the response in its own `HttpSingleResponseConsole` with an SM test tree, so even with a licence `mc_run
  output` would have to be checked for the response text.
- A request execution API: `com.intellij.httpClient.execution.common` (`RequestExecutionSupport`) is
  `@ApiStatus.Experimental` (package-info), `com.intellij.httpClient.execution.adapters` `@ApiStatus.Internal`. No
  public API executes a request and returns the response.
- Both ways fail: not built. No request left the machine (the probe ran against a test server on 127.0.0.1:18431,
  started and stopped for it).

## `mc_structural_search`

### API (all public)

Plugin `com.intellij.structuralSearch`, content module `intellij.platform.structuralSearch` (`visibility="public"`;
optional dependency `mcp-companion-structural-search.xml`, which registers the toolset: without the plugin the tool
does not exist). Used: `MatchOptions` (`setFileType`, `setSearchPattern`, `setScope`, `setCaseSensitiveMatch`,
`getVariableConstraint`, `addNewVariableConstraint`, `copy`), `MatchVariableConstraint` (regexp, invert, expression
types, within hierarchy, min/max count, part of search results), `Matcher.validate`, `new Matcher(project, options)`,
`Matcher.findMatches(MatchResultSink)`, `MatchResult` (`getMatch`, `getStart`, `getMatchImage`, `getName`,
`getChildren`, `isMultipleMatch`), `MatchingProcess.stop`, `StructuralSearchUtil.getSuitableFileTypes/
getDefaultFileType`, `ConfigurationManager.getAllConfigurations` (built-in plus the user's IDE and project templates),
`SearchConfiguration`, `Configuration.isPredefined/getCategory`, the exceptions `StructuralSearchException`,
`NoMatchFoundException`. Not used: `MatchVariableConstraint.convertRegExpTypeToTypeString` and
`convertTypeStringToRegExp` (the only `@Internal` members), `PatternCompiler` (an `impl` package), the replace API.

### Behaviour

- `search`: `pattern` (code of `fileType` with `$Name$` variables; default Java, any language with a Structural Search
  profile, matched by display name, language id or file type name) or `template` (a template's name; a saved one hides
  a built-in one of the same name; the same name in two languages is `AMBIGUOUS` until `fileType` picks one); scope
  `project`, `project_and_libraries`, `module:`, `dir:` or the new `file:<path>` of `common/scope` (also accepted by
  `mc_find_usages` and `mc_find_implementations`); `constraints` per variable (text regex and its inversion, type
  names separated by `|`, within hierarchy, inverted type, min/max count with -1 for no limit, target);
  `caseSensitive`; `limit` (default 50) and `offset`.
- Checks before the IDE sees the template: one of pattern and template, pattern length, variable names, duplicates,
  counts, text regexes (`VariableConstraints`), constrained variables present in the template (`TemplateVariables`).
- The template is compiled by `Matcher.validate` in a smart read action; its message is passed on (`INVALID_ARGUMENT`,
  "Identifier expected." / "应为标识符。"). Some compile errors carry no message (the IDE's dialog then says "The
  specified template is malformed"); the answer then says that the IDE gave no detail. `NoMatchFoundException` ("does
  not match anything in scope", the IDE's word-index shortcut, also for an empty module scope) is an empty answer, not
  an error.
- `findMatches` runs on a pooled thread under the shared `common/progress` indicator: cancelling the call cancels
  the search, and the collector stops the matching process and cancels the indicator after 10,001 matches, so more
  than 10,000 matches are refused like every other paged search (`StableResults`). The matcher reads the code in its
  own read actions. Nothing is opened, shown or focused (screenshot `after-searches.png`).
- Each match: the place through the shared `PsiCodeSites` (path as `read_file` takes it, line, column, line text,
  enclosing declaration, origin), end line, text (cut at 400 characters) and the variables inside the match (the whole
  result tree, so variables nested in another variable's match are found; names the matcher makes up, `__catch_18`,
  `__System`, the context variable and the line markers are dropped; at most 20 values of 200 characters). A target
  variable makes its element the match (e.g. the method name), and variables outside it are not reported.
- `templates`: name, category, language, pattern, built-in or saved, constrained variables; `query` (part of name or
  category) and `fileType` filters; sorted by language, category, name; paged. Replace templates are left out. Names
  are in the IDE's language (98 Java templates in Chinese, e.g. "EJB 接口").

### Design

`structuralsearch/` (pure: `StructuralSearchControl` with one handler per action, `SearchStructurally`,
`ListSearchTemplates`, `VariableConstraints`, `TemplateVariables`, models and the `StructuralSearchGateway` boundary),
`structuralsearch/ssr` (the only package that imports `com.intellij.structuralsearch`: `IdeStructuralSearchGateway`,
`SearchOptions`, `SearchTemplates`, `SearchFileTypes`, `MatchCollector`, `MatchDescriptions`, factory),
`toolsets/structuralsearch`. Shared parts reused: `common/scope` (extended by `file:`), `common/paging`
(`StableResults`, `PageRequest`), `common/source` (`PsiCodeSites`, `SourceKey`), `common/paths`, `common/progress`,
`common/actions`, `common/result`. Konsist: `structuralsearch` is a feature package, `ssr` a gateway segment of an
optional plugin, `StructuralSearchLayersTest` pins the layers.

## End to end

Sandbox `runIdeForMcpE2e -PmcpE2ePort=64411 -PmcpE2eAwtToolkit=XToolkit` on a private Xvfb `:97` (1440x900,
openbox), JPS fixture with the new `src/search/Accounts.java`, Python Streamable-HTTP client
(`build/e2e-step11/mcp.py`, `batch.py`); exchanges in `build/e2e-step11/*.json`, the final round's answers in
`final-round.txt`, screenshots in `build/e2e-step11/screenshots/` (all under the worktree's `build/`, not committed).

| Call | Result |
|---|---|
| `$Instance$.equals($Arg$)` | 2 matches with lines, columns, line text, enclosing method and variable values |
| text constraint `".*"` on Instance; type `String` / `java.lang.String` on a println argument | the literal receiver only; 8 of 9 calls (the `Object` argument left out) |
| empty catch blocks (`$Body$` 0..∞) | 2, one with comments inside the catch; `Body`, `e`, `Type` values, no `__catch_*` |
| `class $C$ { $T$ $m$($PT$ $P$); }` with `m` target and text `is.*\|log` | `isAdmin`, `log` at the name, with `P`, `PT`, `T` |
| paging (limit 4, offsets 0/4/8), `file:`, `dir:../mcp-e2e-sibling`, `module:kotlinfix` | stable pages of 9; the file's 2; `../mcp-e2e-sibling/src/sib/Triangle.java`; 0 |
| Kotlin `println($Arg$)` | `kotlinfix/src/kotlinfix/Hello.kt:5` |
| `templates query="method call"`, `template="Method calls" fileType=Java` with a constraint | 6 templates; the `parseInt` call only |
| no match anywhere, `project_and_libraries` on a library class without sources | 0, not an error |
| malformed template, `$T$ $m$($P$);`, `fileType=Cobol`, unknown or ambiguous template, unknown variable, `file:` on a directory, bad regex, `templates` with `pattern`, `limit=0` | `INVALID_ARGUMENT` (no detail / "Identifier expected."), `UNSUPPORTED` with the languages, `NOT_FOUND`, `AMBIGUOUS` (Java, Kotlin), `INVALID_ARGUMENT`, `NOT_FOUND`, `INVALID_ARGUMENT` x3 |
| `mc_find_usages scope=file:...` | the field's one usage |
| `tools/list` | 72 tools, 17 `mc_` tools; `mc_structural_search` read-only, not destructive, closed world |
| Chinese IDE | template names and the IDE's compile message in Chinese |

Settings page with the new row: `settings-en-1440x900.png`, `settings-zh-1440x900.png` (17 rows, no scroll bar, no
cut text; the row limit of 20 stays). The Chinese round switched the sandbox's own `options/ide.general.xml` and
removed it afterwards.

## Limits and what is not proven

- Cancelling a running search was not triggered end to end (the shared `runUnderIndicator` path is the one step 9 and
  10 tested); the 10,000-match cut was tested with fakes only.
- `project_and_libraries` found nothing in a library without sources (JUnit); searching attached library sources was
  not tried. An XML template over `.iml` files found nothing; other languages than Java and Kotlin were not run.
- Template names and the IDE's messages follow the IDE's language; a template name from one language setting does not
  find the template in another.
- Spring and HTTP Client: not built (above). Verifier for them not run, since no code uses them.
- Not run: Windows, macOS, other IntelliJ-based IDEs.

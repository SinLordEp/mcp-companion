# Step 13: a licensed sandbox, `mc_spring_beans`, and the HTTP Client probe

Date: 2026-11-19. IntelliJ IDEA 2026.3 (IU-263.7307.200), IntelliJ Platform Gradle Plugin 2.20.1. Step 11 could not
test Spring and the HTTP Client because both are paid features (`docs/notes-step11.md`, "The licence gate"). With a
licence for the sandboxes this step builds the Spring bean view and probes the HTTP Client once. The version stays
0.3.0.

## A. Optional sandbox licence

- The Gradle property `mcpCompanion.sandboxSubscriptionKey` (in the developer's own `~/.gradle/gradle.properties`,
  never in the repository) names an `idea.key` by absolute path. `build.gradle.kts` sets the IntelliJ Platform Gradle
  Plugin's `intellijPlatform.subscriptionKey` to `layout.file(providers.gradleProperty(...).map(...))`: the extension
  property is the convention of every `PrepareSandboxTask` the plugin registers (`prepareSandbox`,
  `prepareTestSandbox`, `prepareTestIdePerformanceSandbox`, `prepareSandbox_runIde*`, `prepareSandbox_runIdeForMcpE2e`),
  so all of them copy the key into their config directory as `idea.key`. `buildSrc` `SandboxSubscription.keyFile`
  accepts only an absolute path of an existing file; an empty value, a relative path or a missing file mean no key, and
  nothing is logged (the path would leak into build logs). Configuration-cache compatible (a provider of a Gradle
  property; the file check runs inside the provider).
- Where the copies go: the sandboxes live in `.intellijPlatform/sandbox/` (ignored by `.gitignore`, like `build/`),
  not under `build/`. The key file itself was never opened by this step; only its presence in the sandbox was checked.
- Without the property, or with `-PmcpCompanion.sandboxSubscriptionKey=` (an empty value, as used for the second
  check run), the build behaves as before: no key, no warning.

What was observed:

| Sandbox | `disabled_plugins.txt` | idea.log | `tools/list` |
|---|---|---|---|
| with the key | empty | `com.intellij.modules.ultimate` marked enabled; no core Spring module excluded (only modules that need plugins absent from the sandbox, e.g. `intellij.spring.jsf`, `intellij.spring.mvc.freemarker`) | 110-111 tools, the database tools among them; Spring gutter icons in the fixture (`screenshots/a-spring-gutter-licensed.png`) |
| without (key copy removed, empty property) | `com.intellij.modules.ultimate` | "JetBrains Ultimate ... is marked disabled"; `intellij.spring`, `intellij.spring.boot` and 13 more Spring modules excluded | 74 tools; no `mc_spring_beans` ("Tool mc_spring_beans not found"), `mc_status` without `spring_beans` |

Correction to step 11: the plugin writes `com.intellij.modules.ultimate` into `disabled_plugins.txt` only when the
sandbox has no licence; a sandbox that already holds an `idea.key` copy from an earlier run keeps Ultimate enabled even
after the property is removed (seen: an empty `disabled_plugins.txt` after `-PmcpCompanion.sandboxSubscriptionKey=`
until the copy was deleted). So the no-licence round deleted the copy of the e2e sandbox first. The unit tests load
the paid features without any licence (`testBundledPlugins("com.intellij.modules.ultimate")`), with or without the
property.

## B. `mc_spring_beans`

### Step 0: what the bundled server offers

With the licence the sandbox served 110 tools; none of them is about Spring beans (the served list has no `spring_*`
tool at all for the fixture). The tools the IDE's descriptors declare (`SpringBeansContractTest`, all `mcpToolset`
registrations read by reflection in the test setup with the paid features): `spring_web_controllers`,
`spring_web_endpoints`, `spring_web_endpoint_info`, `spring_webflux_functional_routes` (Spring MVC, `intellij.spring.
mvc.ai`) and `get_configuration_files`, `get_configuration_properties`, `get_configuration_values` (Spring Boot
configuration). No bean list, no injection view: nothing to step aside for, no catalog entry. Endpoints are left to the
official tools; the description and the guide point there.

### API, member by member

All in the Spring plugin's content module `intellij.spring` (`visibility="public"`, depends on
`com.intellij.modules.ultimate`); checked with `javap -v` (no `@ApiStatus` on any class or member used; the 15
classes of `intellij.spring.jar` that mention `ApiStatus` are none of them) and by the Plugin Verifier:

- `SpringManager.getInstance`, `getAllModels(Module)`, `getCombinedModel(Module)`;
- `CommonSpringModel.getAllCommonBeans()`;
- `SpringBeanPointer`: `getName`, `getAliases`, `getBeanClass`, `getSpringBean`, `getPsiElement`, `isValid`,
  `isReferenceTo`;
- `CommonSpringBean`: `getSpringScope`, `getProfile`, `isPrimary`, `isFallback`, `getSpringQualifiers`, `getBeanType`;
  `SpringQualifier.getQualifierValue`; `SpringProfile.getExpressions`, `DEFAULT_PROFILE_NAMES`;
  `SpringBeanScope.getValue`, `SINGLETON_SCOPE`;
- the kinds: `JamPsiClassSpringBean` (components, `getPsiElement`), `JamPsiMethodSpringBean` (`@Bean` methods),
  `DomSpringBean` (XML, `getXmlTag` from `CommonModelElement` in `intellij.java.jam`);
- `SpringModelSearchParameters.byClass/byType`, `BeanClass.withInheritors/effectiveBeanTypes/canSearch`,
  `SpringModelSearchers.findBeans(model, BeanClass)`;
- `SpringAutowireUtils.getInstance`, `getAutowireAnnotationProperties(bean, model)` (annotated fields and methods with
  the IDE's candidates), `isAutowiredByAnnotation`.

Not used: `SpringAutowireUtils.constructorInjectorHandler()` (its type is in `com.intellij.microservices.jvm`), the
internal `intellij.spring.core.shared` and the analysis modules. Java PSI (`PsiClass`, `PsiField`, `AnnotationUtil`)
is used in the same gateway: the Spring plugin depends on the Java plugin.

### Registration

`plugin.xml`: `<depends optional="true" config-file="mcp-companion-spring.xml">com.intellij.spring</depends>`;
that file holds one more optional dependency, on `com.intellij.modules.ultimate`, whose config file
`mcp-companion-spring-ultimate.xml` registers the toolset. The second level is needed because the Spring plugin's
main descriptor does not depend on `com.intellij.modules.ultimate`: without a licence the plugin is there while its
content module `intellij.spring` (with `SpringManager`) is excluded, so a toolset registered directly would fail with
missing classes. Seen in the unlicensed sandbox: the tool is absent and `mc_status` does not list it (the settings
page reads the same list of registered toolsets; not photographed there).
`build.gradle.kts` declares `bundledPlugin("com.intellij.spring")`.

### Behaviour

- `status`: `ready`, or `indexing` (the IDE is in dumb mode; answered at once, not waited for) or `no_spring_model`
  (the module, or no module, has a Spring model: `SpringManager.getAllModels` is empty), each with a `message`; never
  an empty list in place of these. An unknown `module` is `NOT_FOUND` also while indexing.
- `list` (default): the beans of the combined models of `module` (default: every module), each bean once (identity:
  file, offset of the definition, name). Name, aliases, type (fully qualified: the bean class, else the bean type),
  kind (`component`, `bean_method`, `xml`, `other`), scope (default `singleton`), profiles (expressions, default
  profile left out), `primary`, module, and the place of the definition (the class or method name, the XML tag), as
  `read_file` paths with `origin`. Filters: `name` (part of a name or alias, any case) or `namePattern` (regex), `type`
  (a class and its subtypes, through `findBeans(byClass(...).withInheritors().effectiveBeanTypes())`; `NOT_FOUND` for
  an unknown class). Only beans defined in project content unless `includeLibraries=true`; `hiddenLibraryBeans` counts
  the rest (73 Spring Boot auto-configuration beans in the fixture). Sorted by name (case-insensitive), then by
  definition; `limit`/`offset` (default 100); the page is cut from cheap facts and only the page is described.
- `dependencies`: one bean by `bean` (name or alias), `qualifiedName` (its exact class) or `path`+`line` (the
  innermost definition that spans the line); none is `NOT_FOUND`, several `AMBIGUOUS` with the candidates. Answer:
  - `injections`: annotated fields and methods of the bean class and its superclasses (`isAutowiredByAnnotation`), with
    the IDE's candidates where `getAutowireAnnotationProperties` has the member (`source=ide`; seen for fields and
    setters with candidates), else the beans of the declared type (`source=type`; seen for the `@Autowired(required =
    false)` field without candidates, which the IDE's map leaves out); the parameters of the constructor Spring calls
    for a component (the `@Autowired` one, else the only one) and of a `@Bean` method (`source=type`). A one-parameter
    method is one `setter`/`method` point; others one point per parameter. `@Value` parameters are left out.
  - Each point gets a `status` from Spring's rules (`AutowireRules`): a qualifier (`@Qualifier`, `@Named`) keeps the
    beans of that name, alias or qualifier value; a collection, array or `Map<String, T>` takes all (`all`); else
    fallback beans give way, a single primary bean wins, then a bean named like the field or parameter (`resolved`),
    otherwise `ambiguous` with the `candidates`, or `none`. `required=false` for `@Autowired(required = false)`,
    `Optional`, `ObjectProvider`, `ObjectFactory`, `Provider`.
  - `injectedInto`: the injection points of the project's beans (at most 2000 scanned; `scannedBeans`, `complete`)
    whose candidates include the bean and that receive it (`resolved`, `all`) or may receive it (`ambiguous`).
  - `notes`: injections written in XML (`<property ref>`, `<constructor-arg>`, `autowire`) are not read; said for an
    XML bean and whenever XML beans were scanned.

### Design

`springbeans/` (pure: `SpringBeansControl` with `ListBeans` and `ShowBeanDependencies`, `AutowireRules`, models and
the `SpringBeansGateway` boundary), `springbeans/spring` (the only package that imports `com.intellij.spring`:
`IdeSpringBeansGateway`, `SpringModels`, `BeanSelection`, `BeanDescriptions`, `InjectionPoints`, `InjectedType`,
factory), `toolsets/springbeans`. Shared parts: `common/paging` (`StableResults`, `PageRequest`), `common/source`
(`PsiCodeSites`), `common/paths`, `common/result`, `common/actions`. Konsist: `springbeans` is a feature package,
`spring` a gateway segment of an optional plugin that may use Java PSI (`LayeringTest`, `RefactoringLayersTest`),
`SpringBeansLayersTest` pins the layers and keeps `com.intellij.spring.core` and `com.intellij.microservices` out.

### Tests

- `SpringBeansControlTest`, `AutowireRulesTest`: validation, filters, paging, library hiding, the autowiring decision,
  `injectedInto` selection, over fakes.
- `SpringBeansToolsetTest`: the real gateway. The light test project gets the real Spring 6.2 / Boot 3.5 jars as a
  library (Gradle configuration `springFixtureLibrary`, paths passed as a system property, never on the test
  classpath); the Spring model works there without a licence: beans, profiles, places, type filter, paging, field and
  constructor injections, ambiguous and missing candidates (also an optional setter without candidates, which the
  IDE's map leaves out and which stays a `setter`), `injectedInto`, `path`+`line`, and `no_spring_model`,
  `NOT_FOUND`, `INVALID_ARGUMENT`.
- `SpringBeansContractTest`: the official Spring tool names, the hints, the public API used.

### End to end

Licensed sandbox (`runIdeForMcpE2e -PmcpE2ePort=64413 -PmcpE2eAwtToolkit=XToolkit` on a private Xvfb `:113`, 1440x900,
openbox), JPS fixture with the new module `springbeans` (`ShopApplication`, two `PaymentGateway` components,
`OrderService` with constructor injection and a qualifier, `Notifier` with field and setter injection, an ambiguous
field and one without candidates, `ShopConfig` with `@Bean` methods, a profile, an alias, `@Primary`, `@Scope`, and
`legacy-context.xml`). Spring jars resolved by Gradle into `lib/spring`. Exchanges in `build/e2e-step13/*.json`,
summaries in `final-round.txt` and `indexing-round.txt`:

| Call | Result |
|---|---|
| right after start | `status=indexing` for list and dependencies; unknown module `NOT_FOUND` |
| `list` | 12 project beans, `hiddenLibraryBeans=73`; `reportFormatter` with alias `formatter`, `prototype`, primary; `devGreeting` profile `dev`; `legacyPrinter` `xml` at the `<bean>` tag |
| `includeLibraries`, `name=GATEWAY`, `name=formatter`, `namePattern=^(order\|report)`, `type=shop.PaymentGateway`, `type=java.time.Clock` | 85 with Boot's auto-configuration (paths into the jar); 2; 1 (alias); 3; 2; 1 |
| pages of 5 | 5 + 5 + 2, `nextOffset` 5, 10, none |
| `module=quickfix` | `no_spring_model` (list and dependencies) |
| `dependencies bean=notifier` | `auditLog` resolved, `gateway` ambiguous (card, cash), `refunds` none and `required=false`, setter `clock` resolved |
| `qualifiedName=shop.OrderService` | constructor parameters `repository` and `gateway` (qualifier `cardGateway`) resolved |
| `bean=formatter` | the `@Bean` method parameter `clock` resolved |
| `path=ShopConfig.java line=16` | `clock`; injected into `notifier` (setter) and `reportFormatter` (`@Bean` parameter) |
| `bean=cardGateway` | injected into `orderService` (resolved) and `notifier` (ambiguous) |
| XML bean by line | no injections, two notes |
| errors | unknown bean, class, type, module: `NOT_FOUND`; class that is no bean, line without a bean: `NOT_FOUND`; name and pattern, bad regex, two selectors, `bean` for `list`: `INVALID_ARGUMENT` |
| `tools/list` | 111 tools, 20 `mc_`; `mc_spring_beans` read-only, not destructive, closed world |

Settings page at 1440x900, English and Chinese (`settings-en-1440x900.png`, `settings-zh-1440x900.png`, the Chinese
round with the sandbox's own `options/ide.general.xml`, removed afterwards): 20 rows ("Spring Beans"/"Spring Bean"),
no scroll bar, no cut text; the limit of 20 visible rows is now reached. The screenshots come from the round before
the last code change (a setter without candidates is now reported as `setter`, not `method`), which does not touch
the page; the JSON round was repeated after it.

Licence data in the evidence: screenshots were taken of the editor and of Settings > Tools > MCP Companion only (no
About, licence or account dialogs; the title bar and status bar show no account), and the saved JSON and text files
were searched for licence and account terms (see the final report of the step).

## C. HTTP Client: one probe, nothing built

With the licence, `mc_run start_at` on the request line of an `.http` file (`GET http://127.0.0.1:18433/hello`,
a test server started for it and stopped afterwards) creates the temporary configuration "probe | hello" of type
`HttpClient.HttpRequestRunConfigurationType`, runs it (the server logged the request) and ends with exit code 0. But
`mc_run output` is empty and `mc_run tests` has no results: the response (status line, headers, body) goes to the
HTTP Client's own console in the Services tool window (`screenshots/c-http-request-run.png`), not to the process
output that `mc_run` captures. The IDE also saves the body as `.idea/httpRequests/<time>.<status>.json` (here
`2026-11-19T150200.200.json`), readable with `read_file`, but that is a side effect of the HTTP Client's settings,
not an API, and carries no headers. The execution API stays `@Experimental`/`@Internal` (step 11). So the response
cannot be read through `mc_run`: no tool and no guide text.

## Limits and what is not proven

- Constructor and `@Bean` parameter candidates are found by type, not by the IDE's own analysis; generic type
  arguments are left to `findBeans`; custom qualifier annotations (meta-annotated with `@Qualifier`) are not read on
  the injection point (they count only through the IDE's candidates of annotated members).
- XML injections are not read; Kotlin Spring beans were not run (the PSI used is Java PSI through light classes).
- `injectedInto` scans at most 2000 project beans; library beans are not scanned.
- A Spring facet configuration (instead of Spring Boot detection) was not run.
- Not run: Windows, macOS, other IntelliJ-based IDEs.

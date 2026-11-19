# MCP Companion 实现计划

状态：草案，等待确认。日期：2026-11-16。第 5 步完成于 2026-11-17。

## 1. 目标

做一个 IntelliJ 平台插件，接在 IDE 自带的 MCP Server 上，只补它缺的能力：

- AI 能查找引用、查找实现、看类型层级、安全删除。
- AI 能完整控制 Run Configuration：启动、停止、重启、读输出、等待就绪、热替换。
- AI 能看模块依赖图、查循环依赖、按模块编译、重新导入 Maven/Gradle。
- 用户能在 IDE 设置里逐个开关这些工具（用官方 MCP 的工具开关页）。
- 官方 MCP 以后提供了同样的能力，本插件对应的工具自动让开。
- 最终发布到 JetBrains Marketplace。

不做的事：官方已有的读写文件、文本搜索、重命名、格式化、调试、git、数据库、终端。

## 2. 已核实的事实

在本机的 IDEA 2026.1、2026.2、2026.3 上反编译自带 MCP Server 插件，结论如下。

| 接口 | 2026.1 | 2026.2 | 2026.3 | 结论 |
|---|---|---|---|---|
| 扩展点 `com.intellij.mcpServer.mcpToolset`，接口 `McpToolset` | 有 | 有 | 有 | 稳定，用它注册工具 |
| `McpToolset.isEnabled()` | 有 | 有 | 有 | 稳定，用它做开关和"让开" |
| `McpToolset.displayName()`、`isUserConfigurable()` | 无 | 无 | 有 | 只在 2026.3 可用，不依赖 |
| 自带的"工具过滤"设置页 | 无 | 有 | 有 | 2026.2 起用户能在官方设置里逐个开关工具 |
| 扩展点 `mcpToolFilterProvider` | `getFilters` | `applyFilters` | `applyFilters` | 签名每个版本都在变，参数里有 `impl` 包的类，**不用** |

另外：本机现在装的 idea-mcp-ext 是 0.2.1，不是最新的 0.3.0，所以没有热替换工具。

## 3. 关键设计决定

1. **只支持最新的 IDE 大版本，现在是 2026.3（build 263）**。规则：
   - `since-build` 设为当前最新大版本，例如 `263`；不设 `until-build`，默认兼容以后的版本。
   - IDE 出新的大版本后，先对它跑 Plugin Verifier 和测试。接口没变就不用动；接口变了就发新版插件，并把 `since-build` 提到新版本。
   - 旧版 IDE 的用户由 Marketplace 自动处理：它只给每个 IDE 提供与之兼容的最后一个插件版本，更高的版本会提示"需要 IDE xx 版本"。旧版本不再修问题。
   - 不为旧版本写兼容代码，不做反射适配。
2. **只用 `McpToolset`、`@McpTool`、`@McpDescription` 这类公开接口**，包括 2026.3 新增的 `displayName()`、`isUserConfigurable()`。不碰 `impl` 包，不碰 `mcpToolFilterProvider`（它的签名带 `impl` 包的类，每个版本都在变）。这样 Marketplace 的 Plugin Verifier 不会报内部接口。
3. **每个工具组是一个独立的 `McpToolset` 类**，它的 `isEnabled()` 返回"用户开着，并且官方没有提供同样的能力"。
4. **"让开"的实现**：插件内置一张对照表（我们的工具组 → 官方工具名）。检查官方工具是否存在时，读取 `mcpToolset` 扩展点上所有不属于本插件、并且由 JetBrains 提供的实现（IDE 本身、IDE 自带的插件、JetBrains 开发的插件），用反射找带 `@McpTool` 注解的方法名。第三方插件的工具即使同名也不算官方，不会让我们让开。对照表随插件版本更新，不从网络下载。
5. **设置页**：状态页加跳转按钮，见下面"已确定"一节；唯一可改的一项是"MCP 客户端启动运行配置前是否询问"，即官方 MCP Server 的 Brave 模式（2026-11-17，见 `docs/notes-consent.md`）。
6. **工具按功能分组，一组一个 MCP 工具，用 `action` 参数区分动作**，总数控制在 6 个左右，省上下文。
7. **语言 Kotlin，构建 Gradle Kotlin DSL + IntelliJ Platform Gradle Plugin 2.x**（模板已带）。

### 第 0 步的结论（2026-11-16，已由测试和反编译证实，详见 `docs/spike-step0.md`）

1. **`isEnabled()` 不是动态生效的。** 服务器把结果缓存在整个应用范围内，只有两种情况会重新判断：IDE 重启；`mcpToolset` 扩展点有增减（装、卸、更新任何带 MCP 工具的插件）。重启 MCP Server 或新开会话都不会重新判断。
   - 对"让开"没有影响：官方新增一个工具，只可能来自 IDE 升级或插件加载，这两种情况都会触发重新判断。
   - 对"用户开关"有影响：用我们自己的开关驱动 `isEnabled()`，改完要重启 IDE 才生效。
2. **官方自带的逐个工具开关是立刻生效的**，而且对我们的工具同样有效（测试 `testOfficialDisallowListHidesToolImmediately`）。
3. **读取"官方有哪些工具"用扩展点加反射**：遍历 `mcpToolset` 扩展点，读带 `@McpTool` 的方法名。结果和服务器自己的列表完全一致，也能看到被关闭的工具。不能在 `isEnabled()` 里调用 `McpToolsProvider.getTools()`，会递归。
4. **区分"我们的工具组"用标记接口**（例如 `CompanionToolset`）。按类加载器区分在测试里不成立，按插件描述符查找在 2026.3 是内部接口。
5. **其他**：
   - `isExperimental()` 默认是 true，我们的工具组都要覆盖成 false。
   - 插件自己的版本号通过公开的 `PluginAware` 接口拿。
   - 工具描述（`@McpDescription`）必须是编译期常量，不能放资源文件；它是给 AI 看的文字，不算界面文字。
   - 2026.3 需要 Java 25 工具链。
   - MCP 客户端调用工具时要传 `projectPath`（或提供 roots），否则服务器不知道针对哪个项目。
   - 端到端检查用 `./gradlew runIdeForMcpE2e`，它只打开 `build/` 下的临时项目。

### 已确定：用户开关用官方的工具开关页（Sin，2026-11-16）

- 不做自己的开关，不保存自己的开关状态。用户在官方的"工具过滤"页面里开关我们的工具，立刻生效。
- 我们的设置页（设置 → Tools → MCP Companion）列出每个工具组和它的状态（启用、已由官方 `xxx` 提供），并提供一个按钮跳到官方的开关页面。2026-11-17 起另有一个复选框：MCP 客户端启动运行配置前不再询问。它读写的是官方 MCP Server 的 Brave 模式（公开接口 `McpServerSettings`），不是我们自己保存的状态。
- `isEnabled()` 只用来"让开"。规则：只要官方存在对应的工具就让开，不管用户有没有把官方那个关掉。
- 不使用 `McpToolDisallowListSettings`（没有文档的官方类）。因此设置页不显示"用户是否在官方页面里关掉了某个工具"。

### 已确定：位置表示和多项目（Sin，2026-11-16）

背景：一个项目常有位于项目根目录之外的内容根目录（例如 Gradle 子项目 `../castorretail`），符号也常在依赖的 jar 里。实测 IDEA 2026.3 的官方工具已把所有内容根目录算作"项目内"，但文本搜索的路径过滤不能指向旁边的根目录，列目录和列模块也不会告诉 AI 旁边有哪些根目录。

- **位置表示和官方工具一致**：任何内容根目录下的文件用相对项目根的路径，允许 `..`（`../castorretail/...`）；jar 里的文件用 `/绝对路径/x.jar!/包/Foo.java`；其他文件用规范化的绝对路径。每个位置带来源（项目、库源码、库类文件、SDK），结果带全限定名。我们输出的位置必须能原样传给官方的 `read_file`。
- **搜索范围参数**：查找引用和查找实现默认"项目"（包含所有内容根目录），可选"项目加依赖库"。被查的目标可以在库里。类型层级总是显示库里的父类型。安全删除拒绝项目内容之外的目标。
- **不做自己的读文件工具**，官方 `read_file` 已能读 jar 源码和反编译的类。
- **`mc_modules` 要列出每个模块的内容根目录和路径**，让 AI 知道旁边有哪些项目（第 4 步）。
- **不做"把另一个项目链接进来"的工具**：那会改项目的持久配置并触发重新同步和索引，由用户自己设置一次；插件只在文档里说明做法。

## 4. 工具清单

工具名统一加前缀 `mc_`，避免和官方以及其他插件重名。

| 工具 | action | 说明 | 主要用到的平台接口 |
|---|---|---|---|
| `mc_find_usages` | — | 查找符号的全部引用，可按范围、读/写过滤，分页 | `ReferencesSearch` |
| `mc_find_implementations` | — | 接口或抽象方法的实现、子类、重写方法、父方法 | `DefinitionsScopedSearch`、`ClassInheritorsSearch`、`OverridingMethodsSearch` |
| `mc_type_hierarchy` | — | 一个类的父类链和子类树，限制深度 | `ClassInheritorsSearch`、`PsiClass.getSupers` |
| `mc_safe_delete` | `preview`、`apply` | 先报告还有哪些引用，没有才删除 | `SafeDeleteProcessor` |
| `mc_move` | `preview`、`apply` | 把顶层 Java 类移到另一个包（可选另一个源码根目录），更新 Java 和 Kotlin 里的全部引用；冲突先返回，有冲突不执行 | `JavaRefactoringFactory`、`MoveClassesOrPackagesProcessor` |
| `mc_change_signature` | `preview`、`apply` | 改 Java 方法或构造器的参数、返回类型、可见性，更新全部调用处和重写方法；重写了别的方法的方法算冲突（改最顶层的） | `ChangeSignatureProcessor`、`ChangeSignatureUsageProcessor` |
| `mc_run` | `processes`、`start`、`start_at`、`stop`、`restart`、`status`、`output`、`wait_ready`、`hotswap`、`tests` | Run Configuration 全生命周期。`start_at`：不需要事先存在运行配置，按位置（`path`+`line` 或 `qualifiedName`，也可以是目录）运行或调试测试方法、测试类、目录下的测试或 main，相当于行号旁的运行图标；由 IDE 的 `RunConfigurationProducer` 决定（JUnit、Gradle 测试任务、Maven、Application、Kotlin），已有匹配配置就复用，否则建 IDE 的临时配置；多个候选返回 `AMBIGUOUS` 并列出，不弹窗；抽象类里的测试（IDE 会问运行哪个子类）返回 `UNSUPPORTED`。`output` 支持末尾 N 行、正则过滤和游标。热替换走 IDE 自己的构建路径（`ProjectTaskManager`），所以委托给 Gradle、Maven 的项目也能用。`tests`：测试运行的结论、计数和失败的测试（位置、消息、期望/实际值、截断的堆栈），运行中也能读（标为部分结果），可等待结束；`processes`、`status` 对测试运行附带摘要。Maven 运行在 263 没有结构化结果，`tests` 说明原因并指向 `output`。`start`、`restart` 和会重启的 `hotswap` 先在 IDE 里请用户确认（允许/拒绝），和官方 `execute_run_configuration` 一样由 MCP Server 的 Brave 模式决定是否询问；拒绝或 120 秒内未答返回 `DENIED` | `ExecutionListener`、`ProgramRunner`、`ProcessHandler`、`ProjectTaskManager`、`HotSwapManager`、`SMTRunnerEventsListener.TEST_STATUS`、`SMTestProxy` |
| `mc_modules` | `list`、`graph`、`cycles` | 只读。模块及其内容根目录和文件夹（项目目录之外的根会标出）、带作用域的依赖图（JSON，或 `format=mermaid` 输出 Mermaid 流程图，有边数和节点数上限）、循环依赖。按模块构建不放在这里，而是单独的 `mc_build`（构建会改输出目录，而 `mc_modules` 标为只读） | `ModuleManager`、`ModuleRootManager` |
| `mc_sync` | `status`、`sync` | 重新导入 Gradle（及其他 external system）和 Maven 项目，等待并返回结果；同一项目不会同时跑两次同步；Maven 只能整体同步 | `ExternalSystemUtil.refreshProject`、`MavenProjectsManager`、`MavenSyncListener` |
| `mc_diagnostics` | — | 只读。不构建就列出一个模块（可加上它依赖的或依赖它的模块）、一个目录或一组文件的问题：IDE 编辑器里看到的分析（语言自身的错误检查和项目启用的检查），文件不必打开；默认只列错误，可加警告；按文件分组，带行列号，按严重程度和模块汇总；每次最多分析 500 个文件，有时间上限，超时如实返回 `partial` 和继续的位置；等导入和索引完成后再分析。看不到只有真正编译才发现的问题（编译器限制、注解处理器、构建时生成的代码、构建脚本） | `CodeSmellDetector`（平台 VCS 模块，可选依赖）、`ProblemHighlightFilter`、`Observation.awaitConfiguration` |
| `mc_quick_fix` | `list`、`apply` | 一个项目文件里 IDE 报的问题及其快速修复（Alt+Enter 针对该问题的修复），不打开文件；每个修复有在同一文件内容下可复现的 id，标明能否自动应用或需要用户的原因；`apply` 只应用一个修复，作为一个可撤销的全局命令，返回改动的文件；文件改过后旧 id 返回 `CONFLICT`。只自动应用 `ModCommand` 形式的修复（先算出改动再执行，能识别选择、模板、对话框）；老式只能在编辑器里执行的修复列出但拒绝（`UNSUPPORTED`）。没有一键全部修复 | `MainPassesRunner`、`HighlightInfo.findRegisteredQuickFix`、`ModCommandAction.perform`、`ModCommandExecutor.executeInBatch`、`ImportClassFix`（Java） |
| `mc_file_structure` | — | 只读。文件的大纲（IDE 结构视图）：类、方法、字段、构造器、嵌套类型（其他语言为 XML 标签、JSON 键、Markdown 标题等），每项带树形 id、深度、名称、种类、签名、修饰（JVM 语言）和行号；可限深度、按种类过滤、分页；不打开编辑器 | `LanguageStructureViewBuilder`、`TreeBasedStructureViewBuilder.createStructureViewModel(null)`、UAST |
| `mc_local_history` | `label`、`labels`、`diff`、`preview`、`revert` | IDE 本地历史，只用公开接口：打标签、列出本工具打的标签、文件或目录与标签或时间点的差异、恢复（文件为一个可撤销命令，目录用 IDE 自己的恢复到标签）。修订列表在平台内部实现里，不提供 | `LocalHistory.putSystemLabel`、`isLabelValid`、`getByteContent`、`revertToLabel`、`Label.getByteContent` |
| `mc_vcs_history` | `log`、`blame`、`diff` | 只读。经 IDE 的 VCS 抽象：文件或目录的提交历史（或某些行最后一次修改的提交）、逐行追溯、单个文件两个修订之间或对工作区的差异；有上限和分页 | `VcsHistoryProvider.reportAppendableHistory`、`AnnotationProvider.annotate`、`VcsFileRevision.loadContent` |
| `mc_structural_search` | `search`、`templates` | 只读。IDE 的结构化搜索：按语法结构（带 `$变量$` 的模板）而不是文本找代码，变量可加文本正则、表达式类型、数量、目标约束；也可按名字运行 IDE 内置或用户保存的模板（`templates` 列出）。范围为项目、项目加库、模块、目录或文件；匹配带路径、行列、结束行、该行文本、所在声明、匹配文本和变量的值，排序后分页，最多 10000 个；模板编译失败返回 IDE 的信息。不做替换 | `Matcher.validate`、`Matcher.findMatches`、`MatchOptions`、`MatchVariableConstraint`、`StructuralSearchUtil.getSuitableFileTypes`、`ConfigurationManager.getAllConfigurations` |
| `mc_build` | `build`、`status` | 只构建一个模块及其依赖（可加上依赖它的模块），走 IDE 自己的构建路径，委托给 Gradle、Maven 的项目同样适用；返回结果、包含的模块、耗时、带文件和行列号的错误（JPS 来自编译器消息，Gradle/Maven 来自构建工具控制台输出的解析）；超时后构建继续，`status` 查看结果；同一项目同时只允许一次构建，第二个请求被拒绝；不受信任的项目拒绝；不弹对话框，调试会话不会被问"是否重新加载类" | `ProjectTaskManager.createModulesBuildTask`、`CompilerTopics`、`ExecutionManager.EXECUTION_TOPIC`、`HotSwapUI` 否决监听 |

符号定位方式：统一接受"文件路径 + 行号 + 列号"或"全限定名"两种。

通用规则：
- 代码分析全部在 read action 里做，等索引完成后再执行，支持取消。
- 所有列表结果分页，默认每页 100 条，返回里带"是否还有更多"。平台的搜索是并行的，每次顺序不同，所以先收集全部结果的轻量排序键（上限一万条，超过就报错并提示缩小范围），排序后切出当页，只给当页的条目补全详细内容。
- 每个工具是独立的工具组，这样官方补上其中一个时，只有那一个让开。
- `mc_safe_delete` 不使用平台的重构处理器，因为它会弹对话框；IDE 会询问用户的情况都有明确规则，见 `docs/notes-step2.md`。
- 路径一律返回相对项目根目录的路径。
- 出错时返回明确的原因和下一步建议，不抛原始堆栈。

## 5. 代码结构

```
src/main/kotlin/com/github/sinlordep/mcpcompanion/
  toolsets/        每个工具组一个 McpToolset 类
  navigation/      引用、实现、层级、安全删除的实现
  move/            移动类（mc_move）
  changesignature/ 修改签名（mc_change_signature）
  run/             运行控制、输出缓冲、就绪检测、热替换
  modules/         模块列表、依赖图（JSON、Mermaid）、环检测
  sync/            Maven、Gradle 重新导入
  diagnostics/     不构建的代码问题（mc_diagnostics）
  build/           按模块构建（mc_build）
  quickfix/        快速修复（mc_quick_fix）
  structure/       文件大纲（mc_file_structure）
  localhistory/    本地历史（mc_local_history）
  vcshistory/      VCS 历史、追溯、差异（mc_vcs_history）
  structuralsearch/ 结构化搜索（mc_structural_search），平台实现在 ssr/（可选依赖结构化搜索插件）
  yield/           官方工具对照表和检测
  settings/        设置状态和设置页
  common/          符号定位、分页、结果模型、错误模型；共用的模块图（modulegraph）、构建网关（build）、重构执行（refactoring）、可撤销的改动命令（edits）和无编辑器分析时暂停外部注解器（analysis）
src/main/resources/META-INF/
  plugin.xml       核心
  mcp-companion-java.xml    依赖 Java 插件的部分（可选依赖）
  mcp-companion-maven.xml   依赖 Maven 插件的部分（可选依赖）
  mcp-companion-gradle.xml  依赖 Gradle 插件的部分（可选依赖）
src/test/          单元测试和 fixture 项目
```

Java、Maven、Gradle 都做成可选依赖，这样插件在 PyCharm、WebStorm 等其他 IDE 上也能装，只是相应的工具不出现。

idea-mcp-ext（MIT）里运行控制和模块依赖的代码可以参考或搬用，搬用时在 `NOTICE` 里注明出处并保留版权声明。

## 5.1 架构和设计规则

这些规则是硬性要求，审查时逐条对照。

### 分层

代码分三层，依赖只能从上往下：

```
MCP 适配层 (toolsets/)        只做参数解析、调用用例、把结果转成输出模型
        ↓
用例层 (navigation/ run/ modules/ sync/ 里的 service)   一个用例一个类，编排领域逻辑
        ↓
平台网关层 (各包里的 gateway)   封装对 IntelliJ 接口的调用
```

- **MCP 适配层**里不出现 `Psi*`、`ExecutionManager` 这类平台类型，也不写业务判断。一个 `McpToolset` 类只是薄薄一层。
- **用例层**依赖网关的接口，不依赖实现。这样用例可以用假网关做纯单元测试，不用启动 IDE。
- **平台网关层**是唯一直接调用 IntelliJ 接口的地方。官方接口变了，只改这一层。
- `common/` 不依赖任何功能包；功能包之间互不依赖。`yield/` 和 `settings/` 只被适配层使用。

### 用到的设计模式，以及用在哪里

| 模式 | 用在哪里 | 解决什么问题 |
|---|---|---|
| 命令（每个动作一个处理器） | `mc_run`、`mc_modules`、`mc_safe_delete` 的每个 `action` 对应一个 `ActionHandler` 实现，由注册表按名字分发 | 避免一个大 `when` 把八个动作堆在一个类里；加动作只加类，不改已有代码 |
| 策略 | 符号定位（按位置、按全限定名）；就绪检测（正则、端口、日志标志）；依赖图输出（JSON、Mermaid）；构建同步（Maven、Gradle） | 同一件事有多种做法，各自独立，互相替换 |
| 适配器、网关 | 平台网关层 | 把不稳定的平台接口隔离在一处 |
| 观察者 | 进程输出：`ProcessListener` 写入每个进程自己的环形缓冲区，读输出和等待就绪都订阅它 | 读输出、等待就绪不用各自去抓控制台 |
| 规约 | "让开"判断：`用户开着 且 官方没有同样能力`，每个条件一个小对象，可组合 | 判断条件以后会增加，不写成一长串 if |
| 结果类型 | 所有用例返回密封类 `ToolResult<T>`（成功、失败带错误码和建议） | 不用异常传递业务失败；适配层统一转成 MCP 输出 |

不为了用模式而用模式：只有一种做法的地方不抽接口。

### 类和函数的规则

- 一个类只有一个职责。类超过约 200 行、函数超过约 40 行，就要考虑拆。
- 不写 `Utils`、`Helper`、`Manager` 这类什么都装的类。
- 依赖通过构造函数传入。IntelliJ 的 service 只在最外层（toolset 和 `plugin.xml`）获取，不在业务代码里到处 `getService`。
- 不用全局可变状态。运行中进程的状态放在项目级 service 里，随项目关闭释放。
- 输入、输出模型用不可变的 `data class`，和内部领域模型分开；序列化注解只出现在输入输出模型上。
- 线程规则集中在网关层：读操作在 read action，写操作在 write command，耗时操作可取消。上层不关心线程。
- 所有协程挂在 service 自带的作用域上，不用 `GlobalScope`。
- 界面文字放资源文件，不写死在代码里。

### 可扩展性

- 加一个新工具：新建一个 toolset 类、一个用例、需要时一个网关，在 `plugin.xml` 和对照表里各加一行。不需要改已有的类。
- 加一个新动作：新建一个 `ActionHandler` 并注册。
- 官方新出了某个能力：只在对照表里加一行。

### 自动检查

- 代码风格和复杂度：detekt 和 ktlint，放进 `./gradlew check`，CI 里不通过就失败。
- 分层规则：用 Konsist 写架构测试，例如"`toolsets` 包不得引用 `com.intellij.psi`""功能包之间不得互相引用"。规则写成测试，违反了 CI 就失败，不靠人盯。

## 6. 里程碑

| 步骤 | 内容 | 完成标准 |
|---|---|---|
| 0（已完成） | 技术验证：一个最小的 toolset，验证第 3 节末尾的两点；确定版本范围和构建配置 | 沙箱 IDE 里能从 MCP 客户端调到工具；两点有明确结论并写回本文件 |
| 1（已完成） | 公共部分：符号定位、分页、错误模型、设置状态、设置页、让开机制；接入 detekt、ktlint 和 Konsist 架构测试 | 单元测试通过；设置页显示每个工具组的状态并能跳到官方开关页；架构测试在 CI 里生效 |
| 2（已完成） | 代码导航四个工具 | fixture 项目上每个工具的正常和异常用例都通过 |
| 3（已完成） | `mc_run` | 在普通 Java fixture 和 Gradle 委托的 fixture 上跑通启动、等待就绪、读输出、热替换、停止，全程不弹对话框（之后加了启动确认，见 4b）。细节和已知限制见 `docs/notes-step3.md` |
| 3b（已完成） | `mc_run` 的测试结果：测试类运行配置返回通过/失败数和失败堆栈 | 依赖声明用可选的 `<depends optional="true" config-file="mcp-companion-test-runner.xml">com.intellij.platform.testRunner</depends>`，Plugin Verifier 无警告（`TestStateInfo.Magnitude` 是内部接口，不用；因此异常和断言失败不区分）。在 JPS 和 Gradle fixture 上实测：运行中、结束后、中途停止、JVM 起不来、编译失败、同名测试、非测试进程、句柄释放；Maven 运行没有结构化结果，如实说明。细节和限制见 `docs/notes-step3b.md` |
| 4（已完成） | `mc_modules`、`mc_sync` | 在 JPS、Gradle（多项目加 included build）和 Maven 三个 fixture 上实测：依赖图和环检测正确；改构建文件后同步生效，写坏时返回失败原因。细节和限制见 `docs/notes-step4.md` |
| 4b（已完成） | `mc_run` 启动确认 | 第 3 步说官方 `execute_run_configuration` 不询问，是错的。`start`、`restart`、会重启的 `hotswap` 现在先问用户（允许/拒绝，"无需确认"复选框），由官方 Brave 模式控制，设置页可改；拒绝或超时返回 `DENIED`，取消调用会关闭对话框。沙箱里中英文实测，细节和限制见 `docs/notes-consent.md` |
| 5（已完成） | 发布准备：README、插件描述、图标、CHANGELOG、Plugin Verifier 通过、签名 | 版本 0.1.0；`check buildPlugin verifyPlugin` 全绿，Verifier 对 IU-263.7307.200 为 Compatible（下一版 EAP 尚未发布，出现后自动加入）；用一次性自签名证书验证了 `signPlugin` 和 `verifyPluginSignature`；构建好的 ZIP 从磁盘装进干净的 IDEA 2026.3（独立的配置目录，私有 Xvfb），无需重启即加载，`idea.log` 无错误，插件页、设置页、MCP 调用和启动确认在中英文下实测。`runIdeForMcpE2e` 的配置缓存问题已修（逻辑移到 `buildSrc`）。CI 只做了静态检查，推送后才能证明。细节见 `docs/notes-step5.md`，第 6 步的清单见 `docs/RELEASING.md` |
| 6 | 发布到 Marketplace | 需要用户提供 Marketplace 令牌和签名证书，并明确同意发布 |
| 7（已完成，待审查） | `mc_diagnostics`、`mc_build`、`mc_modules graph` 的 Mermaid 输出（替代 idea-mcp-ext 的 `get_aggregated_diagnostics` 和 `build_module_with_dependencies`），版本 0.2.0 | 第 0 步核实：官方 `build_project` 不能按模块构建，读取委托构建位置的 Build 窗口监听接口是 `@ApiStatus.Experimental`；官方 `lint_files` 用的是 `impl` 包。改用公开的 `CodeSmellDetector` 和构建工具控制台输出。构建代码（原 `run/java/ProjectBuild`）和模块图移到 `common/build`、`common/modulegraph`，热替换也因此能给出委托构建的错误位置。JPS、Gradle（多项目加 included build，另生成 20 个子项目测耗时）、Maven（IDE 构建和委托 Maven 构建）三个 fixture 实测，细节和限制见 `docs/notes-step7.md` |
| 8（已完成，待审查） | `mc_move`、`mc_change_signature`：走 IDE 真重构的移动类和修改签名，版本 0.3.0 | 第 0 步核实：官方 263 只有 `rename_refactoring`；Java 用公开的 `JavaRefactoringFactory`、`Refactoring.findUsages/doRefactoring`、`MoveClassesOrPackagesProcessor.detectConflicts`、`ChangeSignatureProcessorBase.findUsages`、各语言 `ChangeSignatureUsageProcessor.findConflicts`、`BaseRefactoringProcessor.executeEx`，不走会弹窗的 `run()`/`preprocessUsages`，冲突在执行前返回；Kotlin 的 K2 重构处理器在 `visibility="internal"` 的内容模块里，所以 Kotlin 声明返回 `UNSUPPORTED`，Kotlin 里对 Java 声明的引用照常更新。公共的预览/应用/冲突/改动文件记录放在 `common/refactoring`，两个工具只提供各自的网关和语言扩展点（`classMove`、`signatureChange`）。Verifier 无新增警告；沙箱实测见 `docs/notes-step8.md` |
| 9（已完成，待审查） | `mc_run start_at`（不需要运行配置，按代码位置运行或调试）、`mc_quick_fix`（列出并应用 IDE 的快速修复），版本仍为 0.3.0 | 第 0 步核实：官方 `execute_run_configuration`、`xdebug_start_debugger_session` 能按 `filePath`+`line` 建临时配置运行，但不返回句柄，不能等待、读测试结果、停止，不接受目录或全限定名，因此 `start_at` 不重复；官方 `get_file_problems`、`lint_files` 只报问题不带修复，没有应用修复的工具。运行用公开的 `ConfigurationContext(PsiElement).getConfigurationsFromContext()`，跳过会弹窗的 `onFirstRun` 和选择弹窗；快速修复用 `MainPassesRunner`（`impl` 包、未标注）得到带修复的 `HighlightInfo`，只执行 `ModCommand` 形式的修复。JPS、Gradle（委托测试）、Maven（IDE 运行与委托 Maven）沙箱实测，中英文确认对话框和设置页截图。共用的可撤销命令下沉到 `common/edits`，外部注解器暂停下沉到 `common/analysis`。细节和限制见 `docs/notes-step9.md` |
| 10（已完成，待审查） | `mc_file_structure`（文件大纲）、`mc_local_history`（本地历史标签、差异、恢复）、`mc_vcs_history`（提交历史、追溯、差异）；修复 `mc_run stop` 对忽略 SIGINT 的进程（宽限期后强杀）和构建内存（`org.gradle.jvmargs`），版本仍为 0.3.0 | 第 0 步核实：官方 263 没有文件大纲、本地历史或提交历史/追溯/差异工具（只有 `get_symbol_info`、`search_symbol`、`generate_psi_tree`、`git_status`、`get_repositories` 和变更列表工具）。大纲用公开的结构视图模型，语言无关，JVM 的种类和修饰走 UAST 扩展点；本地历史只用公开的 `LocalHistory`/`Label`：修订列表在可选产品模块 `intellij.platform.lvcs.impl` 里（入口 `LocalHistoryImpl` 为 `@Internal`，不在插件类路径上），所以不做；VCS 用平台 VCS 抽象，不用 `DiffProvider.createFileContent`（Git 的实现对未知修订弹模态错误框）。差异渲染共用 `common/diff`，后台可取消执行共用 `common/progress`。沙箱实测（临时 Git 仓库副本、虚构作者）、中英文设置页和撤销提示截图。细节和限制见 `docs/notes-step10.md` |
| 11（已完成，待审查） | `mc_structural_search`（结构化搜索）；Spring 视图和 HTTP Client 核实后未做，版本仍为 0.3.0 | 第 0 步核实：官方 263 只有按文本、正则、符号名、文件名的搜索，没有按语法结构的搜索；结构化搜索用公开的 `intellij.platform.structuralSearch`（可见性 public，用到的成员都未标注），模板编译用 `Matcher.validate`（失败返回 IDE 的信息，`NoMatchFoundException` 视为没有匹配），搜索在自己的进度指示器下运行，可取消，不弹窗。`common/scope` 加了 `file:<path>`。Spring 与 HTTP Client 都属于 IDE 的付费 Ultimate 功能：没有订阅的沙箱 IDE（IntelliJ Platform Gradle Plugin 在没有 `subscriptionKey` 时禁用 `com.intellij.modules.ultimate`）里 Spring 模型和 HTTP Client 都不工作，无法在沙箱里实测，因此不做；另外有订阅时官方已有 `spring_web_endpoints` 等端点工具，HTTP Client 的执行接口是 `@Internal`/`@Experimental`，`start_at` 指向 `.http` 请求需要许可证。沙箱实测、细节和限制见 `docs/notes-step11.md` |

每一步一个分支、一个 PR。

## 7. 测试和检查

- 单元测试：`BasePlatformTestCase` 加小型 fixture 项目，覆盖每个工具的正常、空结果、找不到符号、索引未完成几种情况。
- 端到端：`runIde` 起沙箱 IDE，只打开 fixture 项目，用 MCP 客户端真实调用每个工具。
- 每次提交前跑：`./gradlew check verifyPlugin`。
- CI：模板自带的 GitHub Actions（构建、测试、Plugin Verifier 对当前最新大版本和已有的下一版 EAP）。下一版 EAP 由 `build.gradle.kts` 里 `pluginVerification` 的 `latest { channels = EAP; sinceBuild = 当前大版本 + 1 }` 自动找到，没有时不加（第 5 步）。
- 界面（设置页）在 1440x900 下对中文和英文各截图检查。界面文字放在资源文件里，提供中英两种。

## 8. 风险

| 风险 | 应对 |
|---|---|
| 官方 MCP 接口还在变 | 只支持最新大版本，只用公开接口；CI 对下一版 EAP 跑 Plugin Verifier，提前发现变化；接口变了就发新版并提高 `since-build` |
| `isEnabled()` 不是动态生效（已证实） | 只用它做"让开"；用户开关用官方页面 |
| 热替换的边界情况多（改签名、加字段不能热替换） | 识别失败原因，明确告诉 AI，并按参数决定是否自动重启 |
| Marketplace 审核对名字和描述有要求 | 名字用 "MCP Companion"，不用 IntelliJ、JetBrains 开头；描述里写清和官方 MCP Server 的关系 |
| 让 AI 能启停进程、删除代码，有误操作风险 | `mc_safe_delete` 必须先 `preview`；`mc_run` 只操作已有的 Run Configuration，不执行任意命令；启动和重启前请用户确认，开关与官方工具共用（MCP Server 的 Brave 模式） |
| 启动确认绕过了官方的同意机制（第 3 步误判官方不询问，已更正） | 自己的确认对话框，由公开接口 `McpServerSettings.enableBraveMode` 控制，和官方工具同一个开关；会话级的询问模式在 `impl` 包里读不到，见 `docs/notes-consent.md` |

## 9. 工作方式

规划和审查由主会话做，实现交给子代理在独立的 git worktree 里做。对子代理的固定要求：

- 改代码时同步更新 fixture 和端到端测试。
- 设置页有改动时，在 1440x900 下对中文和英文截图自检。
- 沙箱 IDE 只打开 fixture 项目，不连任何线上实例。
- 只停止自己启动的进程，按 PID 停，不用 `pkill -f`。
- 不推送到共享分支。推送前要用户同意。

# MCP Companion 实现计划

状态：草案，等待确认。日期：2026-11-16。

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
4. **"让开"的实现**：插件内置一张对照表（我们的工具组 → 官方工具名）。检查官方工具是否存在时，读取 `mcpToolset` 扩展点上所有不属于本插件的实现，用反射找带 `@McpTool` 注解的方法名。对照表随插件版本更新，不从网络下载。
5. **设置页**：只读的状态页加跳转按钮，见下面"已确定"一节。
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
- 我们的设置页（设置 → Tools → MCP Companion）只读：列出每个工具组和它的状态（启用、已由官方 `xxx` 提供），并提供一个按钮跳到官方的开关页面。
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
| `mc_run` | `list`、`start`、`stop`、`restart`、`status`、`output`、`wait_ready`、`hotswap` | Run Configuration 全生命周期。`output` 支持末尾 N 行和正则过滤，测试运行时带通过/失败数和失败堆栈 | `ExecutionManager`、`RunContentManager`、`ProcessHandler`、`SMTestProxy`、`HotSwapUI` |
| `mc_modules` | `graph`、`cycles`、`build` | 模块依赖图（JSON 或 Mermaid）、循环依赖、按模块编译并返回错误 | `ModuleRootManager`、`CompilerManager` |
| `mc_sync` | — | 重新导入 Maven 或 Gradle 项目，返回结果 | `MavenProjectsManager`、`ExternalSystemUtil` |

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
  run/             运行控制、输出缓冲、就绪检测、热替换
  modules/         依赖图、环检测、模块编译
  sync/            Maven、Gradle 重新导入
  yield/           官方工具对照表和检测
  settings/        设置状态和设置页
  common/          符号定位、分页、结果模型、错误模型
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
| 3 | `mc_run` | 在 Maven + Spring Boot fixture 上跑通启动、等待就绪、读输出、热替换、停止 |
| 4 | `mc_modules`、`mc_sync` | 多模块 fixture 上依赖图和环检测结果正确；改 pom 后同步生效 |
| 5 | 发布准备：README、插件描述、图标、CHANGELOG、Plugin Verifier 通过、签名 | CI 全绿；本地从磁盘安装到 IDEA 2026.3 可用 |
| 6 | 发布到 Marketplace | 需要用户提供 Marketplace 令牌和签名证书，并明确同意发布 |

每一步一个分支、一个 PR。

## 7. 测试和检查

- 单元测试：`BasePlatformTestCase` 加小型 fixture 项目，覆盖每个工具的正常、空结果、找不到符号、索引未完成几种情况。
- 端到端：`runIde` 起沙箱 IDE，只打开 fixture 项目，用 MCP 客户端真实调用每个工具。
- 每次提交前跑：`./gradlew check verifyPlugin`。
- CI：模板自带的 GitHub Actions（构建、测试、Plugin Verifier 对当前最新大版本和已有的下一版 EAP）。
- 界面（设置页）在 1440x900 下对中文和英文各截图检查。界面文字放在资源文件里，提供中英两种。

## 8. 风险

| 风险 | 应对 |
|---|---|
| 官方 MCP 接口还在变 | 只支持最新大版本，只用公开接口；CI 对下一版 EAP 跑 Plugin Verifier，提前发现变化；接口变了就发新版并提高 `since-build` |
| `isEnabled()` 不是动态生效（已证实） | 只用它做"让开"；用户开关用官方页面 |
| 热替换的边界情况多（改签名、加字段不能热替换） | 识别失败原因，明确告诉 AI，并按参数决定是否自动重启 |
| Marketplace 审核对名字和描述有要求 | 名字用 "MCP Companion"，不用 IntelliJ、JetBrains 开头；描述里写清和官方 MCP Server 的关系 |
| 让 AI 能启停进程、删除代码，有误操作风险 | `mc_safe_delete` 必须先 `preview`；`mc_run` 只操作已有的 Run Configuration，不执行任意命令 |

## 9. 工作方式

规划和审查由主会话做，实现交给子代理在独立的 git worktree 里做。对子代理的固定要求：

- 改代码时同步更新 fixture 和端到端测试。
- 设置页有改动时，在 1440x900 下对中文和英文截图自检。
- 沙箱 IDE 只打开 fixture 项目，不连任何线上实例。
- 只停止自己启动的进程，按 PID 停，不用 `pkill -f`。
- 不推送到共享分支。推送前要用户同意。

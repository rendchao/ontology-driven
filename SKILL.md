---
name: ontology-driven-dev
description: Use when the user wants to build a complete, runnable ontology-driven BS (browser front-end + back-end) business system from business requirements, or explicitly asks for 需求探索 / 本体建模 / 七模型 / code-paas / AI原生应用 / 业务系统开发, including "帮我开发一个 XX 管理系统" and "把这段需求做成本体驱动开发". Also use when the user supplies only a requirements specification (仅建模) or only seven-model YAML files (仅构建).
---

# 本体驱动系统开发技能（Ontology-Driven Dev）

将"业务需求 → 软件需求规格 → 七模型本体 YAML → 可运行 BS 系统"的完整方法论打包为可复用技能。
技术底座为内置的 `code-paas`（Flask + SQLite + React/TS 单体应用，含系统管理、流程引擎、工作台、本体注册表），
输出严格受四份规范约束。

> **路径约定（跨工具通用）**：本技能内所有相对路径（如 `references/`、`reference-example/`、`techbase/`、`code-app-example/`）均以「**本 SKILL.md 所在文件夹**」为根目录。
> - WorkBuddy / Claude Code / Codex 等工具在加载技能时会自动解析该根目录；
> - 若某工具未自动解析，请将下文 `<本技能目录>` / `<技能根目录>` 占位符替换为本 SKILL.md 的**绝对路径**后执行（常见安装位置：`<用户主目录>/.agents/skills/ontology-driven-dev`、`<用户主目录>/.claude/skills/ontology-driven-dev`、`<用户主目录>/.workbuddy/skills/ontology-driven-dev`）。

## 一、适用场景与触发

- 用户给出一段业务需求（一句话或一段描述），希望产出一套可运行业务系统。
- 用户明确要求"需求探索确认 / 本体建模 / 七模型 / 基于 code-paas 构建 / AI 原生应用"。
- 自然语言示例：「帮我开发一个 XX 管理系统」「把这段需求做成本体驱动开发」「基于这份需求规格说明书生成系统」。

## 二、三阶段管线 + 人工门禁（强顺序）

阶段一与阶段二、阶段三之间强顺序；阶段一内部各阶段也强顺序，且每一阶段都必须**人工确认**后才可推进。

### 阶段一：需求探索 → 软件需求规格说明书

- **唯一依据**：`references/AI需求探索与确认提示词V1.md`（本阶段格式 / 编号 / 图表 / 自检的唯一基准）。
- **严格按该提示词规定的阶段推进**（阶段零 总体理解确认 → 业务对象 → 业务功能与规则 → 跨对象联动识别 →
  端到端协同流与审批流 → 查询统计与固定报表 → 角色权限 → UI 原型（可选，须先确认是否需要）→ 事件与集成 → 收敛自检）：
- **人工确认门禁（强制，不可跳过）**：每个阶段结束、进入下一阶段前，必须按提示词统一的"问题 N + AI建议 + 建议理由 + 其他选项 + 快捷回复"格式提问，并**硬性暂停等待用户明确确认**（"按AI建议" / 选项字母 / 修改意见）后才可推进。
  - A 类（行业通用）内容：AI 自动补全，标注 `[AI自动补全]`。
  - B 类（企业专属、猜错会有真实业务风险）内容：必须带 AI 建议提问，标注 `[待确认]` / `[已确认]`。
  - 绝不在用户未确认时私自进入下一阶段；附录 B 存在 `[待确认]` 则文档不得标记"完整"。
- **终态**：对照规范第十二章 47 项自检全通过、且附录 B 无 `[待确认]` 后，文档标记为「完整（可进入本体建模阶段）」。
- **输出**：`<业务域>-需求规格说明书-V9.md`（置于当前工作项目根目录）。
- **对照范例**：`reference-example/合同管理需求规格说明书-V9.md`（销售合同执行管理跑通实物）。

### 阶段二：本体建模 → 七模型 YAML

- **唯一依据**：`references/ontology_modeling_framework_v9.md`（七模型元文件规范 + 各模型 YAML 模板）。
- **输入**：阶段一需求文档，尤其其**附录 C 七模型建模输入基线**（是后续 YAML 的确定性输入，不得再做大范围业务拆分）。
- **产物**：M1 对象 / M2 行为 / M3 规则 / M5 主体 / M6 流程 / M7 查询报表 / MU UI 共七个 YAML + `manifest.json`，输出到当前项目的 `yaml/` 目录。
- **建模顺序建议**（见指导书 §4 步骤 1）：M1 → M5 角色 → M3 规则 → M2 行为 → M7 查询 → M5 权限 → M6 流程 → MU 界面。
- **一致性门禁（强制）**：建模后核对——
  - 可追溯门禁：M2 `triggerType=USER_ACTION` 行为须被至少一个 MU 操作功能点引用；MU 引用行为须存在。
  - M7 `behaviorRef` ↔ M2 `queryReportRef` 严格一对一。
  - M6 的 `roleRef` / `behaviorRef` / `subFlowRef` / `ruleRef` 引用均须存在；`SUB_FLOW_CALL` 调用图无环。
  - 每个正式查询报表与唯一 M2 QUERY 行为双向一对一；联动描述中规则条件与结论不混写。
- **输出**：`yaml/m1-object-model.yaml` … `yaml/mu-ui-model.yaml` + `yaml/manifest.json`。
- **对照范例**：`reference-example/` 下 7 个 yaml（字段语义、命名、syncTriggers / node_graph / ASCII 布局的直接参考）。

### 阶段三：应用构建 → 可运行 BS 系统

- **依据（严格按序参考）**：
  1. `references/本体模型业务功能开发指导书.md`（核心：步骤 1-10、模型→实现映射总表、审批端到端、AI 对话、检查清单）
  2. `references/AI原生应用技术架构设计文档V1.md`（技术栈 / 分层 / 语义注册表 / AI 编排 / SSE / 只读 SQL 安全边界）
  3. `references/UI-UE界面设计规范.md`（配色 token / 9pt / 标签右对齐 / 三类界面布局 / 完整 CSS 库）

- **技术底座**：本技能内置 `techbase/`（即 code-paas 干净源码，已剔除 node_modules / __pycache__ / dist / 运行时 DB）。
  **第一步**：将 `techbase/` 整体复制为当前项目根目录下的 `code-app/`，随后安装依赖：
  ```bash
  # 复制底座（保留目录结构）
  cp -r <本技能目录>/techbase/. <当前项目>/code-app/
  # （<本技能目录> = 本 SKILL.md 所在文件夹；各工具会自动解析，否则请替换为绝对路径）
  cd <当前项目>/code-app/frontend && npm install      # 还原前端依赖
  cd <当前项目>/code-app/backend  && pip install -r requirements.txt
  ```
  > 底座是只读基线，扩展只在 `code-app/` 内进行；techbase 自带的"客户申请/查询"示例模型（models/ 下 m1/m2/m5/m6/mu，共 5 份，缺 M3 规则与 M7 报表）按指导书复制改造或删除，用阶段二 `yaml/` 七模型取而代之并登记 manifest.json。
  >
  > **若目标项目已有同栈底座（如 Java + MySQL + Spring Boot 的现有系统）**：不要另建底座，
  > 应把本技能的**方法论约束**（模型是唯一语义来源、5 默认字段、可追溯门禁、只读 SQL 边界、
  > 人工确认门禁）落到既有项目上，并按"从 code-paas 到 code-app"的思路做**分层隔离**——
  > 即把只读基线与业务变更面分开，使升级与回退有明确回归点。

- **🟢 Java 底座：`jjy-ontology-base/`（推荐用于 Java 栈）**
  本技能内置 `jjy-ontology-base/`——**Java 8 + Spring Boot 2.7 + MyBatis-Plus + MySQL 8 + Vue 2** 的
  通用底座，包名 `com.jjy`，artifactId `jjy-ontology-base`，**十一模型体系**（M1–M7 + ME/MI/MM/MU）。
  相对 Python 底座，它多提供九样**已在项目上验证过**的能力：
  | 能力 | 说明 |
  |---|---|
  | **构建期代码生成** | `codegen-maven-plugin` 从 M1 + MM 生成 PO 与目标态 DDL，物理层不手写 |
  | **漂移守门员（四道防线）** | ① 构建期（codegen 内置 M1↔MM 闭合校验，不通过即构建失败）② 运行期（`validateReferences()` 10 项，不通过即启动失败）③ CI（`.github/workflows/model-drift-guard.yml`）④ 本机 git 钩子（`scripts/install-hooks.ps1`）。脚本层 **11 项对账**：M1↔PO / MU↔前端路由 / M2↔Handler / M1↔MM / M3↔M2（含复用双向索引）/ M2↔M5 权限 / M4·M6·M7·ME·MI 跨模型引用 / **声明→代码消费点**（模型声明了但运行期不消费）/ **M1 生命周期↔M2 状态迁移闭合** / **行为形态三方对账**（模板生成↔生成物↔手写实现）/ **声明消费点（规则表自动推导，100 项）** |
  | **守门员自验证（反向实验）** | 每类漂移都做过「故意改坏 → 确认拦截 → 还原」的注入实验。未被验证过的防线等于没有防线——首轮 6 项注入只有 1 项被拦下，据此补齐了 3 处盲区 |
  | **状态机模型化** | M1 声明 `lifecycle`（合法状态集）+ `transitions`（迁移表），M2 声明 `postconditions`（行为完成后状态变成什么），`LifecycleGuard` 在运行期强制校验；代码里不出现任何硬编码状态字符串 |
  | **属性级授权（ABAC）** | M5 的 `abacCondition` 由 `AbacEngine` 真正求值（Aviator 语法，变量 `actor.*` / `row.*` / `ctx.*`），接在 RBAC 之后逐条判定；求值失败按拒绝处理 |
  | **界面模型驱动** | MU 的 `elements[]`（类型/必填/字典/列宽）与 `actions[]`（对应 M2 行为）经 `/meta/screens` 交出，前端 `ModelScreen.vue` 按模型渲染表单、表格与按钮——前端文件里没有字段名/按钮名/行为名的硬编码 |
  | **行为层模板生成** | M2 声明 `behaviorForm=TEMPLATE` + `templateKind`（DELETE / UPDATE / BATCH_DELETE / QUERY_PAGE）→ codegen 生成可运行的 Handler 骨架（落在 `src/`，进版本库，开发者可直接改）。生成物遵守「不硬编码业务语义」：表名列名问 MM、主键字段问 M1、状态问 M2。内容指纹保护开发者的修改不被覆盖 |
  | **事件可靠性设施** | outbox 同事务写入、幂等日志、5 次重试、指数退避、死信表、启动恢复 |
  | **物理映射解析** | `PhysicalMappingResolver` 从 MM 模型解析「别名 → 表名 → 主键列 → 列名」，代码里不硬编码映射表 |

  使用方式（克隆后改名、替换模型、跑守门员）：
  ```powershell
  Copy-Item -Path "<本技能目录>\jjy-ontology-base" -Destination ".\my-app" -Recurse
  cd .\my-app
  .\scripts\rename-package.ps1 -OldPkg "com.jjy" -NewPkg "com.mycompany.app"   # 批量改包名
  # 替换 models/ 下 11 份 YAML → 写 @BehaviorHandler → 同步 DDL
  .\scripts\regenerate-manifest.ps1        # 重算 11 个模型的 sha256
  .\scripts\check-model-drift.ps1          # 必须全绿
  .\scripts\install-hooks.ps1              # 之后自动守门
  mvn -o clean install -DskipTests
  ```
  > **路线一致性**：Java 底座走**十一模型（事件驱动）**路线，与本技能 v9.0 规范的七模型裁剪方向不同。
  > 开发前先确认本工程用哪套模型体系（见指导书 §0.6），**全程保持一致，勿混用**。

- **⚠ Python 底座优先复用 `code-app-example/`（强烈建议，节省大量工作量）**：
  本技能内置 `code-app-example/`——销售合同执行管理的**完整可运行实现**，它已经把阶段三第 8 步「AI 对话框」最难的部分全部写好。开发新业务时**不要从零手写**，按下表直接复制改造：

  > **路线定位与对照**：`code-app-example/` 是 **Python 路线（七模型）**的完整样例，用于复用 Flask + SQLite + React/TS 技术栈下的 AI 对话、只读 SQL、权限与流程等实现；`jjy-ontology-base/` 是 **Java 路线（十一模型）**的独立底座，并不是对 `code-app-example/` 的替换或升级。开发前应先确定项目技术栈：选择 Python 路线时优先复用本目录，选择 Java 路线时使用 `jjy-ontology-base/`；两套路线的底座、模型体系和实现方式不得混用。

  | 需要的能力 | 直接取自 `code-app-example/` | 说明 |
  |---|---|---|
  | 右侧 AI 对话（后端） | `backend/ai/`（chat.py / llm.py / prompt.py / tools.py / config.py） | SSE 全协议已实现：`message_start → delta → tool_call → tool_result → render_payload → message_end` |
  | 右侧 AI 对话（前端） | `frontend/src/components/AIChat.tsx` + `frontend/src/api/ai.ts` | 底座 `techbase/` **没有**这些文件，必须补齐 |
  | 只读 SQL 安全边界 | `backend/sql_readonly/query.py` | 动态 SQL 白名单 + 审计，底座同样没有 |
  | AI 接口与配置弹窗 | `backend/api/ai.py`、`frontend/src/components/LlmConfigModal.tsx` | 模型服务配置入口（API Key 走界面配置，仓库内已清空） |
  | 七模型 → 代码的完整落法 | `code-app-example/models/`（7 份 YAML + manifest） | 对照字段语义与命名 |
  | 端到端自测脚本 | `code-app-example/backend/smoke_test.py` | 覆盖审批、联动、报表、AI 对话全链路 |

  > 复制后必须把 `code-app-example/` 中的合同域业务（contract / invoice / receipt 及其 services、api、前端页面）替换为你的业务域，其余基础设施（ai/、sql_readonly/、ontology/、engine/、utils/）可原样保留。
  > 契约对照：`code-app-example/README.md` 记录了样例的账号、功能范围与模型映射关系。
- **开发顺序（指导书 §1.4 标准流水线 10 步）**：
  1. 写七模型 YAML 到 `code-app/models/` 并登记 `manifest.json`；
  2. M1 → 数据库表 DDL（聚合根主表 / 子实体从表，含 5 默认字段；编号"三位前缀+四位流水号"自动生成）；
  3. 数据字典由注册表自动注册；
  4. M2 行为 + M3 规则 → `services/*.py`（事务内；前置校验→规则校验→状态变更→`syncTriggers` 联动；规则引擎 `simpleeval`，违反给中文提示）；
  5. M5 → 系统管理种子（角色 / 权限 / 资源 / 用户；接口加 `@require_permission`）；
  6. M6 → 流程引擎（M6 activities+branches 转 `node_graph`；审批角色须配置且有用户绑定）；
  7. MU → 菜单 + 页面 + 路由（单表 2 列 / 主从 3 列+从表表格 / 查询 3 列+结果表格分页；`AggregateRootRef` 跳选框、`Enum`/`DictionaryRef` 下拉框；带审批功能"保存草稿/提交"双按钮）；
  8. **强制实现右侧 AI 对话框**（指导书第 7 章 + 架构文档第 9 章）：底座默认 `ai.enabled=false` 且 backend 无 `ai/`、`sse/` 模块，本步必须补齐——system prompt 注入本体注册表、工具注册（导航/查询/行为/只读 SQL）、SSE 流式（`message_start→delta→tool_call→tool_result→render_payload→message_end`）、`text/table/chart/action` 渲染协议、动态 SQL 严格只读白名单 + 审计。**实现方式：从 `code-app-example/` 复制 `backend/ai/`、`backend/sql_readonly/`、`backend/api/ai.py`、`frontend/src/components/AIChat.tsx`、`frontend/src/components/LlmConfigModal.tsx`、`frontend/src/api/ai.ts` 后改造，勿从零手写**；
  9. 联调：登录 → 录入 → 暂存/提交 → 多级审批（通过/驳回/退回/撤回）→ 查询，全链路跑通；
  10. 验收：对照指导书附录 A 检查清单；规则违反前端有中文提示；界面符合 UI-UE 规范。
- **输出**：`code-app/`（可运行系统）。默认账号见 `techbase/README.md`（admin/admin123 等）。

## 三、单阶段入口（用户可指定只跑某段）

- **仅建模**：用户已提供需求规格说明书 → 直接从阶段二开始，产出 `yaml/` 七模型。
- **仅构建**：用户已提供七模型 YAML → 直接从阶段三开始（复制 techbase → code-app 并实现）。
- **重确认需求**：已产出需求文档但有修订 → 回到对应阶段补确认。

## 四、固定输出约定（技能级，仅「业务域」为参数）

| 产物 | 路径 / 命名 |
|---|---|
| 需求文档 | `<业务域>-需求规格说明书-V9.md`（项目根） |
| 本体模型 | `yaml/`（7 yaml + manifest.json） |
| 业务系统 | `code-app/` |
| 技术底座来源 | 技能内置 `techbase/`（运行时复制到 code-app） |

> 若用户显式要求其他路径/命名，以用户指定为准；否则一律采用上表。

## 五、关键纪律（不可违反）

1. **人工确认不可替代**：阶段一每个阶段必须硬暂停等人确认；附录 B 有 `[待确认]` 则文档不得标记完整。
2. **模型是唯一语义来源**：代码 / 表 / 接口 / 菜单 / 权限 / 流程 / 规则全部可回溯到某个本体模型元素，禁止"模型一套、代码一套"。
3. **底座不动**：扩展只在 `code-app/` 内，techbase/code-paas 是只读基线，不就地改。
4. **严格对齐四份规范**：开发全程遵守《本体模型业务功能开发指导书》《AI 原生应用技术架构设计文档》《UI-UE 界面设计规范》《ontology_modeling_framework_v9》的强制条款（5 默认字段、跳选框、双按钮、AI 只读、逻辑删除 `flag=0`、状态机）。
5. **AI 对话强制**：阶段三必须实现右侧 AI 对话框（含只读安全边界）。
6. **事务与联动边界**：一个行为只改一个聚合（聚合内主从同事务）；跨聚合靠 `syncTriggers` 或流程编排。

## 六、参考资源索引

- 方法论文档（5 份）：`references/`
  - `AI需求探索与确认提示词V1.md`（需求探索阶段的唯一提示词；阶段零～阶段九十阶段）
  - `ontology_modeling_framework_v9.md`（七模型元文件规范 + YAML 模板；§10.1 建模顺序推荐）
  - `本体模型业务功能开发指导书.md`（**v2.1**：核心思想 §0.5、模型体系七/十一模型澄清 §0.6、达成判据 L1–L4 §0.7、漂移守门员 §0.8、Java 底座的模型驱动实现 §0.9、模型→实现映射总表、步骤 1-10、检查清单）
  - `AI原生应用技术架构设计文档V1.md`（技术栈 / 分层 / 语义注册表 / AI 编排 / SSE / 只读 SQL 安全边界）
  - `UI-UE界面设计规范.md`（配色 token / 9pt / 标签右对齐 / 三类界面布局 / 完整 CSS 库）
- 黄金范例（仅文档 + yaml）：`reference-example/`（销售合同执行管理跑通实物，对照参考）
  - `合同管理需求规格说明书-V9.md` + 7 份七模型 YAML + `manifest.json`
  - `界面调用链/`：基于七模型生成 UI 调用链追踪工作台的提示词、生成脚本与成品 HTML（用于建模完成后的可追溯性自检）
- 完整应用样例：`code-app-example/`（销售合同执行管理的可运行完整系统，含 AI 对话与只读 SQL，**Python 路线阶段三优先复用**）
- **技术底座（二选一）**：
  - `techbase/`：Python 栈 code-paas 干净源码（Flask + SQLite + React/TS），**七模型**；`requirements.txt` 在 `techbase/backend/`，前端依赖需 `npm install` 还原
  - `jjy-ontology-base/`：**Java 栈通用底座**（Java 8 + Spring Boot 2.7 + MyBatis-Plus + MySQL 8 + Vue 2），包名 `com.jjy`，**十一模型**；含构建期 codegen、漂移守门员、事件可靠性设施、物理映射解析；详见其 `README.md`

## 七、运行说明（给用户）

**Python 路线**
- 后端：`cd code-app/backend && pip install -r requirements.txt && python app.py`（默认 http://localhost:5000，首次启动自动建库+种子）
- 前端开发：`cd code-app/frontend && npm install && npm run dev`（http://localhost:5173）
- 前端生产：`npm run build` 后由 Flask 统一托管，访问 http://localhost:5000
- 自测：样例应用可用 `python code-app/backend/smoke_test.py` 验证全链路
- 默认账号：admin/admin123（管理员，全部权限）；详见 `techbase/README.md`

**Java 路线**
- 后端：`cd jjy-boot && mvn -o clean install -DskipTests && mvn -o -pl jjy-boot spring-boot:run`（默认 http://localhost:8080/api/v1）
- 前端：`cd web && npm install && npm run serve`（http://localhost:8081）
- 守门员：`.\scripts\check-model-drift.ps1`（**提交前必跑，必须全绿**）
- 自测：`mvn -o test`
- 默认账号：admin/admin123（**演示认证器，生产必须替换**，见其 `README.md` 必做替换项清单）

# ontology-driven-dev

> 一套「需求探索 → 本体建模 → 应用构建」三步法的**本体驱动业务系统开发技能**。
> 把一段业务需求变成一套**可运行的管理系统**，并用机器手段保证「模型即唯一语义来源」——杜绝"模型一套、代码一套"。

[English version → README_EN.md](./README_EN.md)

---

## 一、这个技能能做什么

| 能力 | 说明 |
|---|---|
| **需求可追溯** | 每个功能都能回溯到需求文档的某个条目 |
| **模型即语义来源** | 表结构、接口、菜单、权限、流程、规则、界面全部由模型 YAML 决定 |
| **人工门禁不可跳过** | 需求探索的 8 个阶段，每阶段**暂停等人确认**后才推进 |
| **双栈底座** | Python 栈（`code-app-example/`）与 Java 栈（`jjy-ontology-base/`）可选 |
| **漂移机器阻断** | Java 底座内置 **11 项对账器** + **守门员自验证**，改坏模型立刻被拦 |
| **强制 AI 对话框** | 生成的应用自带 AI 对话（本体注册表注入 + 工具调用 + SSE 流式 + 只读 SQL 边界） |

---

## 二、包含内容

```
ontology-driven-dev/
├── SKILL.md                      # 技能核心指令（方法论 + 三阶段管线 + 纪律）
├── references/                   # 方法论文档（强制规范）
│   ├── AI需求探索与确认提示词V9.0.md       # 含《软件需求编写规范 V9.0》全文
│   ├── ontology_modeling_framework_v9.md   # 七模型元规范 + YAML 模板
│   ├── 本体模型业务功能开发指导书.md         # ★ v2.0：核心思想 / 模型体系 / L1-L4 判据 / 漂移守门员 / 行为形态
│   ├── AI 原生应用技术架构设计文档.md        # 技术栈 / 语义注册表 / AI 编排 / SSE
│   └── UI-UE界面设计规范.md               # 配色 token / 布局 / 完整 CSS 库
├── reference-example/            # 黄金范例（销售合同执行管理跑通实物）
│   ├── 合同管理需求规格说明书-V9.md
│   ├── m1-object-model.yaml … m7-report-model.yaml + mu-ui-model.yaml
│   ├── manifest.json
│   └── 界面调用链/                # 基于七模型生成 UI 调用链追踪工作台的提示词 / 脚本 / 成品 HTML
├── code-app-example/             # ★ 七模型完整应用样例（Python 栈，Flask + SQLite + React/TS）
│   ├── models/                   # 合同领域七模型 YAML + manifest
│   ├── backend/                  # 业务服务、流程、报表、AI 助手（ai/ + sql_readonly/）及测试数据库
│   ├── frontend/                 # 合同、开票、收款、审批及报表页面 + AI 对话组件
│   └── README.md                 # 样例功能、账号、启动与自测说明
├── techbase/                     # 技术底座①：Python 栈干净源码（七模型）
│   ├── backend/                  # Flask + SQLite 后端（流程引擎 / 本体注册表 / 服务层）
│   │   └── requirements.txt      # 后端依赖（注意在 backend/ 下，非 techbase 根目录）
│   ├── frontend/                 # React + TypeScript 前端（Vite）
│   ├── models/                   # 示例模型 YAML（M1/M2/M5/M6/MU 共 5 份）
│   └── README.md                 # 底座运行说明
└── jjy-ontology-base/            # ★ 技术底座②：Java 栈通用底座（十一模型，Java 项目推荐用）
    ├── models/                   # 11 个本体模型 YAML + manifest.json（含 sha256）
    ├── codegen-maven-plugin/     # 构建期代码生成 + 11 个对账器（漂移守门员）
    ├── jjy-domain/               # 领域层：Behavior/Rule/Event/Flow POJO + ModelRegistry
    ├── jjy-infrastructure/       # 基础设施：事件机制 / 规则引擎 / ABAC / 审计 / AI 基础设施
    ├── jjy-application/          # 应用层：BehaviorDispatcher / ABAC 引擎 / 报表引擎 / AI 工具
    ├── jjy-interfaces/           # 接口层：REST Controller（auth/behavior/meta/flow/event）
    ├── jjy-boot/                 # 启动模块 + Flyway 迁移 + 健康检查
    ├── web/                      # 前端骨架（Vue 2.7 + Element UI，模型驱动渲染）
    └── scripts/                  # 漂移守门员 / 自验证 / manifest 重算 / git 钩子 / 批量改包名
```

### 两套底座怎么选

| | `code-app-example/`（Python） | `jjy-ontology-base/`（Java） |
|---|---|---|
| 技术栈 | Python 3.10 + Flask + SQLite + React/TS | Java 8 + Spring Boot 2.7 + MyBatis-Plus + MySQL 8 + Vue 2 |
| 模型体系 | **七模型**（M1/M2/M3/M5/M6/M7/MU） | **十一模型**（+M4/ME/MI/MM，事件驱动） |
| 持久层 | 手写 DDL | **M1+MM 构建期生成 PO 与 DDL** |
| 行为实现 | 手写 | **可声明式模板生成**（`behaviorForm=TEMPLATE`） |
| 防漂移 | 脚本对账 | **11 项对账器 + 4 层防线 + 守门员自验证** |
| 事件机制 | 无（同步 `syncTriggers`） | outbox / 幂等 / 重试 / 死信 / 启动恢复 |
| 适用 | 快速原型 / Python 栈团队 | **Java 栈生产项目（推荐）** |

> **务必先确定用哪套模型体系，全程保持一致，不要在同一工程混用**（详见指导书 §0.6）。

### Java 底座的漂移防线（核心工程机制）

11 项对账器分四层，越往下越不可绕过：

| 层 | 时机 | 能否绕过 |
|---|---|---|
| ① 构建期 | `mvn install` | 绕不过（构建直接失败） |
| ② 运行期 | 应用启动 | 绕不过（启动直接失败） |
| ③ CI | 每次 push / PR | 绕不过（流水线红） |
| ④ 本机 git 钩子 | commit / push 前 | 可 `--no-verify` 绕过 |

11 项检查覆盖：PO 字段漂移 / 菜单路由不一致 / 行为无实现 / 字段无列映射 / 规则引用与复用索引双向不一致 / 权限码不存在 / 跨模型引用悬空 / **模型声明了却无人消费**（规则表自动推导）/ **行为形态三方对账** / **状态机自相矛盾** / **声明消费点（自动推导 92 项）**。

> **守门员本身也要验**：底座提供 `scripts/verify-guards.ps1`——故意注入 6 类漂移，确认守门员真能拦住。**未被验证过的防线等于没有防线**。

### 汇报材料

若需要对外讲解这套方法（团队汇报、方案评审、跨团队宣讲），用现成的汇报文档，不要用 README 临场拼凑：

- **[本体建模实践汇报](./jjy-ontology-base/docs/ontology-report/ontology-report.html)** — 7 章完整汇报稿：问题与实测故障、本体建模是什么（含与 DDD / MDA 的关系）、两条投影链路、交付物盘点、四层防线与反向实验、当前状态与已知不足、下一步建议。

另有两份配套材料：

- **[目标与达成判据审查](./jjy-ontology-base/docs/ontology-goal-review/ontology-goal-review.html)** — 目标分解（8 层）、达成判据矩阵（L1–L4）、执行机制（11 项对账器 + 4 层防线）、路线、标准、验收方式、当前状态审查。
- **[本体建模待办清单](./jjy-ontology-base/docs/ontology-todo-list/ontology-todo-list.html)** — 目标理解、判据矩阵、目标支撑核查、各工作项进度（18 项已完成 / 6 项待办）、关键结论。

README 回答的是「这东西怎么用」，汇报文档回答的是「为什么值得做、不做会怎样」。两者用途不同，不要互相替代。

---

## 三、安装（主流工具）

> 本技能**不依赖任何专有机制**，可完整运行在 Claude Code、Codex、Cursor 等工具上。
> 唯一适配点：技能内相对路径以「本 SKILL.md 所在文件夹」为根。

### 1. WorkBuddy

```bash
cp -r ontology-driven-dev ~/.workbuddy/skills/ontology-driven-dev     # 用户级
cp -r ontology-driven-dev <你的项目>/.workbuddy/skills/ontology-driven-dev  # 项目级
```

### 2. Claude Code

Claude Code 的 Skills 格式与本技能 frontmatter（`name` / `description`）完全一致，基本**直接复制**即可：

```bash
cp -r ontology-driven-dev ~/.claude/skills/ontology-driven-dev
```

### 3. Codex

```bash
mkdir -p .codex/skills && cp -r ontology-driven-dev .codex/skills/
```

然后在仓库的 `codex.md`（或 `AGENTS.md`）中加入一句：

> 当用户要做「本体驱动开发 / 需求探索 / 本体建模 / 七模型 / AI 原生应用」时，
> 加载 `.codex/skills/ontology-driven-dev/SKILL.md` 并严格按其「三阶段 + 人工确认门禁」流程执行。

### 4. Cursor / Aider / Cline / 其他通用 Agent

把 `SKILL.md` 当作「方法论指令文件」注入项目上下文即可（Cursor 放 `.cursorrules`，其他工具粘贴全文或引用路径）。

---

## 四、使用流程

技能分**强顺序三阶段**，每阶段之间及阶段一内部都必须人工确认。

### 阶段一：需求探索 → 软件需求规格说明书

- **依据**：`references/AI需求探索与确认提示词V9.0.md`（含《软件需求编写规范 V9.0》全文）
- **推进**：「阶段零 ∼ 阶段七」八阶段——总体理解 → 业务对象 → 业务功能与规则 → 跨对象联动 → 端到端协同/审批流 → 查询统计与报表 → 角色权限 → UI 原型（可选）
- **门禁**：每阶段结束按「问题 + AI 建议 + 理由 + 其他选项 + 快捷回复」提问并**硬暂停等人确认**
- **产出**：`<业务域>-需求规格说明书-V9.md`（含附录 C 建模输入基线）

### 阶段二：本体建模 → 模型 YAML

- **依据**：`references/ontology_modeling_framework_v9.md`（七模型）或指导书 §0.6（十一模型）
- **输入**：阶段一需求文档的附录 C 基线
- **产物**：M1 / M2 / M3 / M5 / M6 / M7 / MU（七模型）或再加 M4 / ME / MI / MM（十一模型）+ `manifest.json`
- **一致性门禁**：可追溯性、M7↔M2 一对一、M6 引用无环等强制核对

### 阶段三：应用构建 → 可运行 BS 系统

**路线 A：Python 栈（快速原型）**

```bash
cp -r <技能根目录>/code-app-example/. <当前项目>/code-app/
cd code-app/backend  && pip install -r requirements.txt && python app.py   # :5000
cd code-app/frontend && npm install && npm run dev                       # :5173
```

**路线 B：Java 栈（生产项目，推荐）**

```bash
cp -r <技能根目录>/jjy-ontology-base <当前项目>/my-app
cd my-app
.\scripts\rename-package.ps1 -OldPkg "com.jjy" -NewPkg "com.mycompany.app"
# 替换 models/ 下 11 份 YAML → 写 @BehaviorHandler → 同步 DDL
.\scripts\regenerate-manifest.ps1        # 重算模型 sha256
.\scripts\check-model-drift.ps1          # 11 项对账，必须全绿
.\scripts\verify-guards.ps1              # 守门员自验证，必须全过
.\scripts\install-hooks.ps1              # 之后自动守门
mvn -o clean install
cd jjy-boot && java -jar target/jjy-boot-1.0.0.jar   # :8080
cd ../web && npm install && npm run serve             # :8081（代理 /api → :8080）
```

- **默认账号**：`admin / admin123`
- **Java 底座自测**：`jjy-application` 的 5 个单测覆盖模型装载与跨模型引用校验
- **⚠ AI 对话框**：Python 路线请复用 `code-app-example/backend/ai/`、`backend/sql_readonly/`、`backend/api/ai.py`、`frontend/src/components/AIChat.tsx`——SSE 全协议、本体注册表注入、只读 SQL 白名单都已实现，**勿从零手写**。Java 路线对应 `jjy-application/ai/`。

---

## 五、触发语

| 意图 | 示例 |
|---|---|
| 整体开发 | 「帮我开发一个 XX 管理系统」「把这段需求做成本体驱动开发」 |
| 仅建模 | 「基于这份需求规格说明书，做本体建模」 |
| 仅构建 | 「基于这几份模型 YAML，生成系统」 |
| 关键字 | 本体驱动、需求探索、本体建模、七模型、十一模型、AI 原生应用、业务系统开发 |

---

## 六、运行环境要求

**Python 路线**：Python 3.10+（Flask + SQLite）、Node.js 18+（React + Vite）

**Java 路线**：JDK 8、Maven 3.5+、MySQL 8、Node.js 16+（Vue CLI 4；Node 17+ 需 `--openssl-legacy-provider`，底座已在 `vue.config.js` 内置兜底）

---

## 七、许可

本项目以 **MIT License** 发布，可自由使用、修改与再分发，详见 [LICENSE](./LICENSE)。

# jjy-ontology-base

> 本体驱动业务系统开发底座 · Java 8 + Spring Boot 2.7 + MyBatis-Plus + MySQL 8
> 包名 `com.jjy` · groupId `com.jjy` · artifactId `jjy-ontology-base`
> 模型体系：**十一模型**（M1–M7 + ME + MI + MM + MU）

一个**不含任何业务域代码**的通用底座。业务工程克隆它、替换模型、写 Handler，即得到一套
可运行且可维护的业务系统——代码是模型的投影，不是模型的二次翻译。

---

## 一、核心思想

**把「业务是什么」从代码里抽出来，写成一份可执行的模型文件；代码只是这份模型文件的投影。**

```
              ┌──────────────────────────────────────┐
              │  本体模型 YAML（唯一语义源）           │
              │  11 个模型 + manifest.json            │
              └───────────────┬──────────────────────┘
                              │
        ┌─────────────────────┴─────────────────────┐
        │ 编译期投影（构建期，一次）                  │ 运行期投影（启动 + 每次调用）
        ▼                                           ▼
  codegen 插件                                   ModelRegistry
  M1 + MM → PO / 目标态 DDL                      启动期加载 YAML + sha256 校验
        │                                           │
        │            ┌──────────────────────────────┼──────────────┐
        │            │                              │              │
        ▼            ▼                              ▼              ▼
     PO 类       目标态 DDL              行为分发（权限→规则→Handler→事件）  AI 工具 schema
                                                   │                          │
                                                   ▼                          ▼
                                             流程 / 场景引擎            LLM 的接口说明书
```

三条由此推出的硬约束：

1. **模型是唯一语义源**——表、接口、菜单、权限、流程、规则全部能回溯到某个模型元素；
2. **漂移靠机制发现，不靠人盯**——对账工具 + git 钩子，发现漂移即**拒绝提交**；
3. **跨聚合禁止直接调用，一律经事件**——outbox 同事务写入 → 派发 → 幂等 → 重试 → 死信。

> 详见《本体模型业务功能开发指导书》v2.0 §0.5 核心思想、§0.6 模型体系、§0.7 达成判据 L1–L4、§0.8 漂移守门员。

---

## 二、快速开始

### 2.1 环境要求

| 依赖      | 版本                         |
| ------- | -------------------------- |
| JDK     | 1.8（`JAVA_HOME` 须指向 JDK 8） |
| Maven   | 3.5+                       |
| MySQL   | 8.0                        |
| Node.js | 18+（仅前端工程需要）               |

### 2.2 克隆为业务工程

```powershell
Copy-Item -Path "<技能根目录>\jjy-ontology-base" -Destination ".\my-business-app" -Recurse
cd .\my-business-app

# 1) 改包名（一次性脚本，见 README「附录 A」）
powershell -NoProfile -File .\scripts\rename-package.ps1 -OldPkg "com.jjy" -NewPkg "com.mycompany.app"

# 2) 编译（会按模型生成 PO 与目标态 DDL）
mvn -o clean install -DskipTests   # 首次必须 install，见下方「构建命令说明」

# 3) 准备数据库（建库 + 建用户；表由 Flyway 自动迁移）
mysql -uroot -p < scripts/init-db.sql

# 4) 启动（Flyway 自动建表 + 播种管理员）
mvn -o -pl jjy-boot spring-boot:run
# → http://localhost:8080/api/v1
# → 默认账号 admin / admin123（生产必须改）
```

### 2.3 前端构建（可选）

```powershell
cd web
npm install
npm run serve      # 开发模式，/api 代理到 :8080
npm run build      # 产物在 web/dist
```

### 2.4 第一次该做什么

**顺序很重要，不要跳步：**

| #    | 动作                                       | 说明                        |
| ---- | ---------------------------------------- | ------------------------- |
| 1    | 替换 `models/` 下 11 个 YAML                 | 用你的业务模型替换示例的「商品」模型        |
| 2    | `scripts\regenerate-manifest.ps1`        | 重算 11 个模型的 sha256         |
| 3    | `mvn -o clean install -DskipTests`       | codegen 按新模型生成 PO 与 DDL   |
| 4    | 同步 `db/migration/V1__platform_schema.sql` | 业务表 DDL（codegen 只产出目标态参考） |
| 5    | 写 `@BehaviorHandler` 实现                  | 每个 M2 行为一个方法              |
| 6    | `scripts\check-model-drift.ps1`          | **必须全绿**，否则模型与代码不一致       |
| 7    | `scripts\install-hooks.ps1`              | 装 git 钩子，之后自动守门           |

---

## 三、模块结构

```
jjy-ontology-base/
├── models/                        # ★ 11 个本体模型 YAML + manifest.json（唯一语义源）
├── codegen-maven-plugin/          # 构建期代码生成 + 11 个对账器（漂移守门员）
├── jjy-domain/                    # 领域层：Behavior/Rule/DomainEvent/Flow POJO + ModelRegistry
├── jjy-infrastructure/            # 基础设施：YAML 加载、事件机制、规则引擎、RBAC、审计、AI 基础设施
├── jjy-application/               # 应用层：BehaviorDispatcher、流程引擎、场景执行器、AI 工具、业务 Handler
├── jjy-interfaces/                # 接口层：REST Controller（auth / behavior / meta / flow / event）
├── jjy-boot/                      # 启动模块：Application + Flyway 迁移 + 模型资源同步 + 健康检查
├── web/                           # 前端骨架（Vue 2.7 + Element UI），路由为 MU 对账的代码端
│   ├── src/router/index.js        # ★ 与 MU 模型对账的代码端
│   ├── src/views/                 # 页面骨架：Login / MyTodos / ProductMaintain / ProductQuery
│   └── src/utils/                 # http 封装（统一响应拆解 + 401 处理）、令牌存储
├── scripts/                       # 漂移守门员、manifest 重算、git 钩子、批量改包名、init-db.sql
└── docs/                          # 方法论文档（见「文档索引」）
```

### 前端骨架说明

`web/` 是**最小可运行骨架**，不是完整产品——它演示「前端不调 Service，只调 M2 行为 ID」这一核心模式：

```js
// ProductMaintain.vue —— 行为驱动的前端写法
await this.$http.post('/behavior/Product_SaveAsDraft', { dto: this.form })
await this.$http.post('/behavior/Product_Submit',      { dto: { productId } })
await this.$http.post('/behavior/Product_Query',        { dto: this.query })
```

| 约定                             | 说明                                       |
| ------------------------------ | ---------------------------------------- |
| 路由标题必须与 MU 菜单名一致               | 漂移守门员按**标题**互查（不是 path），不一致即阻断提交         |
| `meta.permission` 对应 M5 权限码    | 控制菜单与按钮可见性                               |
| 带审批的对象用双按钮                     | 「暂存 / 提交」分别对应 M2 的两个行为（MU §8.3）          |
| 字典下拉框数据来自 `/meta/dictionaries` | 选项由 M1 的 `dataDictionaries` 声明，**不在前端硬编码** |
| 编号字段只读                         | 编号由系统生成（`BizNoGenerator` 读 M1 的 `noRule`） |

> 业务工程替换模型后，需同步更新 `web/src/router/index.js` 与页面，
> 并跑 `.\scripts\check-model-drift.ps1` 确认与 MU 模型一致。

### 依赖方向

```
jjy-boot → jjy-interfaces → jjy-application → jjy-infrastructure → jjy-domain
                                    ↓
                            codegen-maven-plugin（构建期，不参与运行期依赖）
```

`jjy-domain` 零第三方依赖（仅 Lombok），是纯语义层。

---

## 四、十一模型

| 模型     | 文件                        | 职责                      | 运行期落点                                  |
| ------ | ------------------------- | ----------------------- | -------------------------------------- |
| **M1** | `m1-object-model.yaml`    | 对象：聚合、属性、字典、生命周期        | PO 生成、字典注册、`aggregateAliases`          |
| **M2** | `m2-behavior-model.yaml`  | 行为：命令/查询、权限、规则、事件       | `BehaviorDispatcher` 驱动                |
| **M3** | `m3-rule-model.yaml`      | 规则：校验/计算/推导             | Aviator + SpEL 双引擎沙箱                   |
| **M4** | `m4-scenario-model.yaml`  | 场景：长事务编排（4 类步骤）         | `ScenarioExecutor`                     |
| **M5** | `m5-actor-model.yaml`     | 主体：角色、权限、外部实体           | RBAC + 数据权限                            |
| **M6** | `m6-flow-model.yaml`      | 流程：审批流 / 协同流（nodeGraph） | `FlowEngine`                           |
| **M7** | `m7-report-model.yaml`    | 查询报表：列表/详情/统计/报表        | 查询服务                                   |
| **ME** | `me-event-model.yaml`     | 事件：领域事件、payload、订阅关系    | outbox → 派发 → 幂等 → 死信                  |
| **MI** | `mi-interface-model.yaml` | 接口：外部系统契约               | 入站 webhook / 出站发布                      |
| **MM** | `mm-mapping-model.yaml`   | 映射：物理表列契约（唯一真源）         | codegen 输入 + `PhysicalMappingResolver` |
| **MU** | `mu-ui-model.yaml`        | 界面：菜单、屏幕、操作功能点          | 菜单 + 前端路由对账                            |

> **十一模型 vs 七模型**：本底座走事件驱动路线（十一模型）。若目标系统是纯单体、无外部集成、
> 不需要构建期生成持久层，可裁剪为七模型（去掉 M4/ME/MI/MM，M2 改用 `syncTriggers`）。
> 详见指导书 §0.6。

---

## 五、核心机制

### 5.1 行为驱动执行链

前端**不直接调用业务 Service**，而是调用 M2 行为 ID：

```
POST /behavior/{behaviorId}
  → BehaviorDispatcher.execute(ctx)
     ① 权限校验   读 M2.requiredPermissions
     ② 规则求值   读 M2.appliedRules（Aviator/SpEL），违反即抛中文提示
     ③ 反射调用   @BehaviorHandler("Xxx_Yyy")
     ④ 同事务写 outbox  读 M2.producedEvents
```

**新增业务功能的成本 = 模型加一条 YAML + 写一个 Handler 方法，不需要改动架构或新增接口。**

### 5.2 事件可靠性

```
行为执行 → 同事务写 outbox → 调度器派发 → 订阅者匹配 → 幂等校验 → 消费
                                              ↓ 失败
                              5 次重试 → 指数退避 → 死信表 → 启动恢复
```

### 5.3 模型驱动实现要点

底座不只「用模型生成 PO」，而是在各个层面把**语义决定权收归模型**。

| 层面 | 模型声明 | 代码怎么用 | 改模型的后果 |
|---|---|---|---|
| 物理层 | M1 `attributes[]` + MM `columnMappings` | codegen 生成 PO 与 DDL | 重新构建即生效 |
| 状态机 | M1 `lifecycle` + `transitions`，M2 `postconditions` | `LifecycleGuard` 校验迁移合法性 | 状态流转规则跟着变 |
| 查询口径 | M7 `resultColumns` / `matchType` / `orderBy` | `QueryReportExecutor` 生成 SQL | 列与匹配方式跟着变 |
| 行为实现 | M2 `behaviorForm` + `templateKind` | codegen 生成 Handler 骨架 | 新形态自动生成 |
| 属性授权 | M5 `abacCondition` | `AbacEngine` 求值 | 授权条件跟着变 |
| 界面 | MU `elements[]` / `actions[]` | `/meta/screens` 交出声明，前端按模型渲染 | 表单/表格/按钮跟着变 |
| 编号规则 | M1 `noRule` | `BizNoGenerator` 读模型 | 编号格式跟着变 |
| 接口契约 | MI `requestFields` / `responseFields` | `/meta/interfaces` 归一化输出 | 契约跟着变 |

**判定「模型是唯一语义」的可检验标准**：把 YAML 改一个字，看行为是否跟着变，
Java 与前端代码零改动。底座已实测通过：

| 改动 | 结果 |
|---|---|
| M7 `matchType: LIKE → EQ` | 片段查询从命中 5 行变为 0 行 |
| M2 `postconditions.setValue` | 行为目标状态跟着变 |
| M1 删一行 `transitions` | 该状态迁移被运行期拒绝 |
| M7 `resultColumns` 删两列 | 返回列从 6 列变 4 列 |

### 5.4 漂移守门员

**这是本底座最核心的工程机制**——把「模型与代码一致」从纪律变成机器阻断。

#### 5.4.1 四道防线

漂移检测不是单点，而是四层重叠。**越往下越不可绕过**：

| 层           | 时机              | 触发方式                                     | 能否绕过               |
| ----------- | --------------- | ---------------------------------------- | ------------------ |
| ① 构建期       | `mvn compile`   | codegen 插件内置 M1↔MM 闭合校验                  | 绕不过（构建直接失败）        |
| ② 运行期       | 应用启动            | `validateReferences()` 10 项校验            | 绕不过（启动直接失败）        |
| ③ CI        | 每次 push / PR    | `.github/workflows/model-drift-guard.yml` | 绕不过（流水线红）          |
| ④ 本机 git 钩子 | commit / push 前 | `install-hooks.ps1` 装 pre-commit、pre-push | 可 `--no-verify` 绕过 |

> **为什么要有四层**：git 钩子只在本机生效，且能被 `--no-verify` 绕过；
> 构建期和运行期是**代码里的强制关卡**，装不装钩子都生效；
> CI 保证别人的提交也进不了你的主干。

#### 5.4.2 第 ① 层：构建期（最不可绕过）

codegen 插件在 `generate-sources` 阶段先校验 **M1 ↔ MM 映射闭合**：

```
[ERROR] Product.remark : M1 声明了该字段但 MM 未映射（DDL 会漏此列）
[INFO] BUILD FAILURE
```

这类漂移若放过，症状是「建表成功、运行时报 `Unknown column`」——极难排查。
现在它在构建期就被拦下，**不依赖是否装了 git 钩子**。

#### 5.4.3 第 ② 层：运行期

模型装载完立即校验 10 项跨模型引用，不通过则**启动直接失败**：

```
[validate] Flow FLOW-PRODUCT-SHELF-001 trigger 引用不存在的 Behavior Product_NotExist
[validate] 跨模型引用校验: FAIL
[ModelLoader] 加载失败 → 应用启动失败
```

#### 5.4.4 第 ③④ 层：脚本对账 + git 钩子 + CI

```powershell
.\scripts\check-model-drift.ps1          # 11 项全查
.\scripts\check-model-drift.ps1 -Quick   # 只查 PO 字段（pre-commit 同款）
.\scripts\verify-guards.ps1              # 守门员自验证（反向实验，见 §5.3.4.1）
.\scripts\install-hooks.ps1              # 装 git 钩子，之后自动守门
.\scripts\regenerate-manifest.ps1 -Check # manifest 哈希一致性（CI 用）
```

| #    | 检查                   | 模型端                      | 代码端                              | 查什么漂移                  |
| ---- | -------------------- | ------------------------ | -------------------------------- | ---------------------- |
| 1    | M1 ↔ Java PO         | `m1-object-model.yaml`   | `target/generated-sources/po/**` | 字段增删未重新生成              |
| 2    | MU ↔ 前端路由            | `mu-ui-model.yaml`       | `web/src/router/index.js`        | 菜单标题与路由不对应             |
| 3    | M2 ↔ Handler         | `m2-behavior-model.yaml` | `jjy-application/**/handler/*`   | 行为无实现 / 实现未声明          |
| 4    | M1 ↔ MM              | `m1-object-model.yaml`   | `mm-mapping-model.yaml`          | 字段无列映射（DDL 漏列）         |
| 5    | M3 ↔ M2              | `m3-rule-model.yaml`     | `m2-behavior-model.yaml`         | 规则引用漂移 + **复用双向索引不一致** |
| 6    | M2 ↔ M5 权限           | `m2-behavior-model.yaml` | `m5-actor-model.yaml`            | 权限码不存在 / 角色多授          |
| 7    | M4/M6/M7/ME/MI 跨模型引用 | `models/*.yaml`          | 各被引用模型                           | 引用指向不存在的声明             |
| 8    | 声明 → 代码消费点           | `models/*.yaml`          | `jjy-*/src/main/java/**`         | **模型声明了但运行期不消费**       |
| 9    | M1 生命周期 ↔ M2 状态迁移    | `m1-object-model.yaml`   | `m2-behavior-model.yaml`         | **状态机声明自相矛盾**          |
| 10   | 行为形态三方对账          | `m2-behavior-model.yaml` | `jjy-application/**`             | **模板生成 ↔ 生成物 ↔ 手写实现** |
| 11   | 声明消费点（规则自动推导）    | `models/*.yaml`          | 业务源码 + codegen 插件           | **模型声明了却无人消费**        |

> **首次运行就报出漂移是正常的**——它证明模型驱动此前并未闭环。
> 本底座自带示例模型已通过全部 11 项检查（0 漂移），可作为参照基线。

#### 第 8 项：模型声明了，但代码根本不消费

前 7 项对账的是「模型 ↔ 代码结构」（字段有没有生成 PO、行为有没有注册 Handler），
管不了另一类问题：**模型声明了某项语义，运行期却从来不读它**。

| 模型声明                                  | 若无消费点，后果                    |
| ------------------------------------- | --------------------------- |
| M2 `syncTriggers`                     | 跨聚合联动只能绕道事件，声明形同虚设          |
| ME `subscribers[].handlerRef`         | 事件产出后无人消费，Jackson 还会静默丢弃该字段 |
| M7 `resultColumns`                    | 查询口径在代码里手写，M7 沦为摆设          |
| M5 `dataScope`                        | 只能按权限码约定猜数据范围               |
| M1 `lifecycle` / `transitions`        | 状态可被写成任意字符串，状态机不存在          |
| M2 `preconditions` / `postconditions` | 前置条件/状态迁移只写在纸上              |

> **本项自身也踩过假阳性**：`postconditions` 曾命中 `BehaviorTool`——它把 M2 元数据
> 原样透传给 LLM 做描述，字面上出现了字段名，但**不产生任何业务语义**。
> 若把它算作消费点，守门员就会给假绿灯：模型改坏了，代码照常跑，永远没人发现。
> 修正后的判据是：**读出来影响行为的才算消费点，读出来给人看的不算**；
> 关键词也从裸字段名收紧为调用形式（`getPostconditions()`）与字符串字面量（`"postconditions"`），
> 以免命中领域 POJO 的字段声明行。

#### 第 9 项：状态机声明的自相矛盾

状态机分散在两个模型里：**M1 声明合法状态集合与迁移表，M2 声明行为完成后状态变成什么**。
各自校验就会漏掉「互相矛盾」：

- M2 的 `setValue` 不在 M1 的 `lifecycle` 内 → 行为会写入一个非法状态；
- M2 的 `setValue` 与 M1 迁移表的 `to` 不一致 → 迁移表形同虚设。

第 9 项专门对账这两者，运行期则由 `LifecycleGuard` 兜底强制拒绝。

#### 第 10 项：行为形态三方对账

M2 的 `behaviorForm` 声明**一个行为由谁实现**，这是「行为层能否自动化」的唯一判据：

| 形态            | 含义                                      |
| ------------- | --------------------------------------- |
| `TEMPLATE`    | 模板生成——codegen 生成 Handler 骨架（CRUD 类标准动作） |
| `HANDWRITTEN` | 手写实现（默认；不写该字段即为此值）                      |
| `SCRIPT`      | 脚本实现（预留，Java 8 无 Nashorn 暂不支持）          |
| `DECLARATIVE` | 纯声明——无本地代码，由引擎按 M7/规则/事件通用执行            |

第 10 项对账 **M2 声明 ↔ 生成物 ↔ 手写实现** 三者：

| 漂移                | 症状                                       |
| ----------------- | ---------------------------------------- |
| 声明 TEMPLATE 但无生成物 | 运行期才抛「没有 @BehaviorHandler 实现」            |
| 声明 TEMPLATE 但混入手写 | 同一 behaviorId 两个实现，谁生效取决于扫描顺序（**不确定的静默行为**） |
| 生成物被开发者改过         | 模型变更不会自动同步进来，需要人工核对                      |

**生成物落在 `src/` 而非 `target/`**：生成物进版本库，开发者可直接改；
用**内容指纹**保护——改过的文件不会被覆盖，构建期只告警，由第 10 项报出不一致。

> 配套改动：`BehaviorHandlerRegistry` 从「覆盖语义」改为「重复即报错」。
> 否则模板生成物与手写实现共存时，「谁生效」取决于 Spring 的 bean 扫描顺序——
> 一个不确定且无任何提示的行为。

#### 第 11 项：声明消费点（规则表自动推导）

第 8 项管的是「模型声明了但没人消费」，但它的**规则表是手工维护的**——
新增一个模型语义字段时忘了加规则，它就退化成「无人消费也不报」。

**守门员自己的盲区，是所有盲区里最危险的一个**：它让人**以为**防线在。

第 11 项的做法是**扫描全部模型文件，自动推导检查项**：

1. 收集每个 YAML 里出现过的所有键名；
2. 排除非语义字段（`id` / `name` / `version` / `type`…）与技术字段（`flag` / `created_at`…）；
3. 为剩余字段自动生成检查项，关键词按字段名派生（`getXxx()` 与 `"xxx"` 两种形式）。

实测自动推导 **100 个检查项**，首次运行即发现 **10 处真实缺口**（全部已修复）。

> **扫描范围必须包含 codegen 插件**——生成器本身就是模型的消费者
> （它把模型变成 PO / DDL / Handler）。不扫它会把「已被生成器消费」的字段
> （如 M1 的子实体声明）误报成真缺口。

**第 6 项检查什么**：`M2.behaviors[].requiredPermissions` 必须引用 **M5 声明的 `permissionId`**
（方法论 §3.2.1），而不是自造字符串。这类漂移最隐蔽——模型里写了个不存在的权限码，
运行期因为 admin 通配权限而「看起来正常」，直到业务方按 M5 实际授予权限时才暴露为 403。

**第 7 项检查什么**：M4 场景、M6 流程、M7 报表、ME 事件、MI 接口这五个模型，
其对其他模型的引用（行为 / 角色 / 权限 / 聚合根 / 事件）必须指向真实存在的声明。

> 这五者此前既无守门员、也未被运行期校验覆盖——实测把 M6 的 `behaviorRef` 改成不存在的行为、
> `roleRefs` 改成不存在的角色、ME 的 `aggregateRef` 改成不存在的聚合根，
> 守门员报 PASS、运行期也报 PASS，三个坏引用全部漏过。
> 这类漂移叫**静音失败**：模型装载一切正常，直到流程真的走到那个节点才崩。

#### 5.4.4.1 守门员本身也要验：用「反向实验」证明它真拦得住

一个从不报错的检查器比没有检查器更危险——它让人**以为**防线在。
所以本底座对每类漂移都做了注入实验：故意改坏模型 → 确认守门员报错 → 还原。

| 注入的漂移                                  | 拦截者   | 结果   |
| -------------------------------------- | ----- | ---- |
| `M3.reusedBy` 与 `M2.appliedRules` 不一致  | 第 5 项 | 拦截 ✓ |
| `M2.preconditions` 引用不存在的规则            | 第 5 项 | 拦截 ✓ |
| `M2.postconditions` 目标状态非法             | 第 9 项 | 拦截 ✓ |
| `M1.transitions[].via` 指向不存在的行为        | 第 7 项 | 拦截 ✓ |
| `M1.transitions[].to` 不在 lifecycle 内   | 第 7 项 | 拦截 ✓ |
| `ME.subscribers[].handlerRef` 指向不存在的行为 | 第 7 项 | 拦截 ✓ |

> 首轮实验只有 **1/6** 被拦下——第 5、7、9 项各有盲区。
> 这正是做反向实验的意义：**没被验证过的防线等于没有防线**。

#### 5.4.5 实测：git 钩子真的能拦住

在一个真实 git 仓库里验证（改模型加 `remark` 字段但没重新生成 PO）：

```
$ git commit -m '加了 remark 字段'
[1/1] M1 Object Model <-> Java PO
  - 字段漂移 : 1
  Product :
      仅 yaml 有（手写 PO 缺） : [remark]
  FAILED → 提交被阻断
```

重新生成 PO 后再提交 → 放行。

> 实测还发现并修掉了一个误伤：**全新克隆的仓库还没编译，PO 尚未生成**，
> 会被误判成「5 个 PO 缺失」而拦住第一次提交。
> 现在检查器会识别「尚未构建」并放行，提示先跑 `mvn install`。

### 5.5 物理映射解析

底座**不在代码里硬编码**「别名 → 表名 → 主键列 → 列名」的映射，而是从 MM 模型解析：

```java
// 业务代码只需问模型
String table = physicalMapping.tableName("Customer");     // → t_customer
String pk    = physicalMapping.pkColumn("t_customer");     // → customer_id
String col   = physicalMapping.column("t_customer", "customerName"); // → customer_name
```

MM 有声明则以模型为准；无声明时按命名规范兜底推导，保证开箱即用。
**业务 Handler 的 SQL 里不应出现列名字面量**——列名已在 MM 的 `columnMappings` 里声明过，
代码里再写一遍就是下一次漂移的起点。

---

## 六、⚠ 必做替换项清单

**底座开箱即用，但以下几处必须由业务工程替换，否则等于带着脚手架上线。**

| #    | 位置                                       | 现状                                       | 必须改为                                     |
| ---- | ---------------------------------------- | ---------------------------------------- | ---------------------------------------- |
| 1    | `jjy-interfaces/.../auth/AuthConfig.java` | 数据库认证器：读 `t_user` + BCrypt 验口令，权限按 M5 授予 | 有独立用户中心时实现 `UserAuthenticator` 覆盖；否则至少改掉种子口令 |
| 2    | `models/*.yaml`                          | 「商品」示例模型                                 | 你的业务模型                                   |
| 3    | `jjy-boot/src/main/resources/application.yml` | `jwt.secret` 为默认值                        | 环境变量注入强随机密钥                              |
| 4    | `jjy-boot/src/main/resources/application.yml` | `DB_PASSWORD:root123`                    | 环境变量注入                                   |
| 5    | `application.yml` → `jjy.ai.api-key`     | `sk-replace-me`                          | 环境变量注入（不启用 AI 可留空）                       |
| 6    | `application.yml` → `jjy.ai.system-name` | 空                                        | 你的系统名（写入 AI 自我介绍）                        |
| 7    | `db/migration/V2__platform_seed.sql`     | 演示角色/权限/管理员账号                            | 你的系统种子数据，**并改掉 admin 口令**                |
| 8    | `web/src/router/index.js` + `web/src/views/` | 4 条示例路由与页面                               | 按 MU 模型补齐                                |
| 9    | `pom.xml` → `<artifactId>`               | `jjy-ontology-base`                      | 你的工程名                                    |
| 10   | `jjy-application/.../handler/ProductHandler.java` | 商品示例行为实现（已模型驱动）                          | 你的业务行为实现（或删除）                            |

> **第 1 项注意**：默认认证器读 `t_user` 表并按 M5 模型授予权限，权限码不硬编码；
> 但它<b>不做</b>登录失败锁定、租户切换、独立用户中心对接——这些要业务工程自己实现。
> **第 7 项最要紧**：种子里的 `admin/admin123` 必须改掉。

### 6.1 底座里「不再硬编码」的清单（供替换时对照）

改造成本低，是因为底座已经把「该问模型的事」都改成问模型了：

| 原本硬编码在哪                             | 现在改成                                     |
| ----------------------------------- | ---------------------------------------- |
| `BehaviorDispatcher` 里的别名→表名 switch | `PhysicalMappingResolver.tableName()`（读 MM） |
| `BehaviorDispatcher` 里的主键列名清单       | `PhysicalMappingResolver.pkColumn()`（读 MM） |
| `BehaviorDispatcher` 里的 ID 字段名清单    | `candidateIdKeys()`（读 M1）                |
| `BizNoGenerator` 里的编号前缀/表/列         | `BizNoGenerator.nextFor(alias)`（读 M1 `noRule`） |
| `WebhookEventService` 里的事件映射 switch | 读 MI 的 `mappedInternalEvent`             |
| `SystemPromptBuilder` 里的业务名词        | 读 M1 聚合根名 + `jjy.ai.system-name`         |
| `QueryTool` 里的元数据 SQL               | 读 `ModelRegistry`（M1/M2/M5/MU）           |
| `SqlSafetyValidator` 里的白名单常量        | 读 MM（配置显式指定时以配置为准）                       |
| `AuthConfig` 里的权限码                  | 读 M5 角色→权限                               |
| `ProductHandler` 里的表名/列名/编号前缀       | 读 MM + M1                                |

---

## 七、常用命令

```powershell
# 编译（含 codegen 生成 PO 与 DDL）
mvn -o clean install -DskipTests

# 只编译某个模块及其依赖
mvn -o -pl jjy-application -am compile


# 跑测试
mvn -o test

# 漂移守门员
.\scripts\check-model-drift.ps1
.\scripts\check-model-drift.ps1 -Fix

# 重算 manifest sha256
.\scripts\regenerate-manifest.ps1
.\scripts\regenerate-manifest.ps1 -Check    # 只检查，可接 CI

# 安装 / 卸载 git 钩子
.\scripts\install-hooks.ps1
.\scripts\install-hooks.ps1 -Uninstall

# 启动
mvn -o -pl jjy-boot spring-boot:run
```

---

## 七之二、构建命令说明

### 各个 Maven 阶段实际做了什么

codegen 插件绑在 **`generate-sources`** 阶段——它比 `compile` 还早：

```
validate → initialize → generate-sources ← codegen 在这里跑
        → process-sources → ... → compile → test → package → verify → install → deploy
```

所以**漂移校验（构建期 M1↔MM 闭合）在 `compile` 阶段就已经生效**：

| 命令               | codegen | 漂移校验   | 产物            | 什么时候用    |
| ---------------- | ------- | ------ | ------------- | -------- |
| `mvn -o compile` | ✅       | ✅ 失败即拦 | PO 源码 + class | 开发期最快反馈  |
| `mvn -o package` | ✅       | ✅      | + 各模块 jar     | 要拿 jar 时 |
| `mvn -o install` | ✅       | ✅      | + jar 装进本地仓库  | **首次必做** |
| `mvn -o test`    | ✅       | ✅      | + 跑单元测试       | 提交前      |

### 为什么首次必须 install

本底座是**多模块工程，且 codegen 插件自身也是一个模块**（排在 reactor 第一位）。
`jjy-infrastructure` 在 `generate-sources` 阶段要调用它，此时 Maven 优先用
reactor 里的 `codegen-maven-plugin/target/classes`。

首次构建时这个目录还不存在 → Maven 找不到插件描述符：

```
Failed to parse plugin descriptor for com.jjy:codegen-maven-plugin:1.0.0
  ... No plugin descriptor found at META-INF/maven/plugin.xml
```

解法：**先单独装一次插件**，之后就能随便用 `compile`：

```powershell
mvn -o -pl codegen-maven-plugin install -DskipTests   # 只需一次
mvn -o clean compile                                  # 之后都能用
```

> **本插件模块已配置跳过 `clean`**（`maven-clean-plugin` 的 `skip=true`）。
> 因为 `clean` 会删掉插件自己的 `target/`，而 reactor 内其他模块正引用着它——
> 这是「reactor 内自举插件」的经典冲突。插件自身的编译产物交给 `compile` 覆盖即可。
> 需要彻底清理时，手动删 `codegen-maven-plugin/target` 目录。

### install 相比 package 多的那一步

`install` = `package` + 把 6 个 jar 装进本地 Maven 仓库：

```
com/jjy/jjy-domain-1.0.0.jar
com/jjy/jjy-infrastructure-1.0.0.jar
com/jjy/jjy-application-1.0.0.jar
com/jjy/jjy-interfaces-1.0.0.jar
com/jjy/jjy-boot-1.0.0.jar
com/jjy/codegen-maven-plugin-1.0.0.jar
```

**这正是「底座可独立复用」的关键**：装进本地仓库后，其他业务工程可以直接依赖它：

```xml
<dependency>
    <groupId>com.jjy</groupId>
    <artifactId>jjy-infrastructure</artifactId>
    <version>1.0.0</version>
</dependency>
```

不 install 的话，别的工程找不到这个底座。

---

## 八、对外端点

| 端点                                    | 说明                                      |
| ------------------------------------- | --------------------------------------- |
| `POST /auth/login`                    | 登录，返回 accessToken / refreshToken / 用户档案 |
| `POST /behavior/{behaviorId}`         | **执行 M2 行为**（模型驱动的核心入口）                 |
| `GET /meta/dictionaries`              | 数据字典（M1）                                |
| `GET /meta/menus`                     | 菜单树（MU）                                 |
| `GET /meta/behaviors`                 | 行为清单（M2）                                |
| `GET /meta/actors`                    | 角色与权限（M5）                               |
| `GET /meta/model-summary`             | 模型装载摘要（自检用）                             |
| `GET /flow/definitions`               | 流程定义（M6）                                |
| `GET /flow/instances/active`          | 运行中流程实例                                 |
| `POST /flow/instances`                | 启动流程                                    |
| `GET /event/definitions`              | 事件清单（ME）                                |
| `GET /event/closure-report`           | **事件闭环对账**（产出方 ↔ 消费方覆盖率）                |
| `GET /event/outbox/pending`           | outbox 积压                               |
| `GET /event/dead-letters`             | 死信列表                                    |
| `POST /event/dead-letters/replay`     | 重放死信                                    |
| `POST /ai/chat`                       | AI 对话（SSE 流式，需 `jjy.ai.enabled=true`）   |
| `GET /health`                         | 健康聚合（DB / outbox / 死信 / 场景）             |
| `GET /health/ping` \| `/health/ready` | 存活 / 就绪探针                               |

> 统一响应：`{ code, message, data, traceId }`，`code=2000` 为成功。

---

## 九、达成判据 L1–L4

**不要用「代码写完了」或「测试通过了」代替验收。**

| 层级         | 判定条件              | 核实方式                                |
| ---------- | ----------------- | ----------------------------------- |
| **L1** 已声明 | 模型有该声明，跨模型引用完整    | 读 YAML + 启动期 `validateReferences()` |
| **L2** 已实现 | 代码有对应实体且有**实质逻辑** | 静态比对（Handler 含 DB 写或状态变更）           |
| **L3** 已接通 | 链路两端真连上，无静默失败     | 运行验证（触发 → 产出 → 消费 → 落库）             |
| **L4** 可验收 | L3 成立且有自动化测试证据    | 测试断言（真实行为 ID 被引用）                   |

**L2 有极强伪装性**——行为都有实现、代码读起来该有的都有，但缺陷藏在接线的接缝处。
**L3/L4 的缺口不允许用「L2 已达成」抵扣。**

---

## 十、文档索引

| 文档                 | 位置                             | 定位                   |
| ------------------ | ------------------------------ | -------------------- |
| 本体模型业务功能开发指导书 v2.1 | 技能 `references/`               | **实现规范**：模型怎么落地      |
| 本体驱动软件建模方案 v9.0    | 技能 `references/`               | **建模规范**：模型怎么写       |
| AI 原生应用技术架构设计文档    | 技能 `references/`               | AI 编排层设计             |
| UI-UE 界面设计规范       | 技能 `references/`               | 界面视觉与交互              |
| 模型驱动代码生成           | `codegen-maven-plugin/`  | M1+MM → PO/DDL 的构建链路（含 Handler 模板生成） |
| 漂移守门员              | `scripts/check-model-drift.ps1` | 11 项对账 + `verify-guards.ps1` 反向实验自验证 |
| **本体建模实践汇报**      | `docs/ontology-report/ontology-report.html` | **汇报稿**：对外讲解这套方法（7 章） |
| **目标与达成判据审查**      | `docs/ontology-goal-review/ontology-goal-review.html` | **审查稿**：目标分解、判据矩阵、执行机制、路线、标准、验收方式、当前状态（7 章） |
| **本体建模待办清单**      | `docs/ontology-todo-list/ontology-todo-list.html` | **清单稿**：目标理解、判据、支撑核查、工作项进度、关键结论（5 章） |

---

## 附录 A：批量改包名

克隆底座后，用脚本一次性把 `com.jjy` 改成你的包名：

```powershell
.\scripts\rename-package.ps1 -OldPkg "com.jjy" -NewPkg "com.mycompany.app"
```

脚本会改写：Java 文件的 `package` / `import`、目录结构、pom 的 `groupId` / `artifactId` /
`<po.root.pkg>`、YAML 与资源文件中的包引用，并校验残留。

改完务必执行：

```powershell
mvn -o clean install -DskipTests
.\scripts\check-model-drift.ps1
```

---

## 附录 B：环境变量

| 变量                                       | 默认值                                      | 说明                  |
| ---------------------------------------- | ---------------------------------------- | ------------------- |
| `DB_URL`                                 | `jdbc:mysql://127.0.0.1:3306/jjy_ontology_base` | 数据源                 |
| `DB_USERNAME`                            | `root`                                   | 数据库用户               |
| `DB_PASSWORD`                            | `root123`                                | 数据库密码               |
| `JWT_SECRET`                             | 内置默认值                                    | **必须覆盖**，长度 ≥ 32 字符 |
| `SERVER_PORT`                            | `8080`                                   | 服务端口                |
| `SERVER_CONTEXT_PATH`                    | `/api/v1`                                | 上下文路径               |
| `AI_ENABLED`                             | `false`                                  | 是否启用 AI 对话          |
| `AI_BASE_URL` / `AI_API_KEY` / `AI_MODEL` | —                                        | LLM 配置              |
| `AI_SYSTEM_NAME`                         | 空                                        | 写入 AI 自我介绍的系统名      |

---

## 许可

MIT License

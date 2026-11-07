<#
.SYNOPSIS
    模型 ↔ 代码 漂移守门员（Model-vs-Code Drift Guard）。

.DESCRIPTION
    把「改 M1 模型必须同步改 PO / 改 MU 模型必须同步改路由 / 改 M2 行为必须注册 Handler」
    这条铁律变成**机器阻断**，而不是依赖人记得。

    三项检查（任一发现漂移即 exit 1）：
      1. M1 对象模型  ↔ Java PO          （PoFieldDiffReporter）
      2. MU UI 模型   ↔ 前端路由         （MenuRouteChecker）
      3. M2 行为模型  ↔ Handler 注册     （BehaviorRegistryChecker）

    附加检查：
      4. M1 ↔ MM 映射契约               （MappingDiffReporter）
      5. M3 规则 ↔ M2 行为引用闭合       （RuleRegistryChecker）
      6. M2 权限 ↔ M5 主体引用闭合       （PermissionRegistryChecker）
      7. M4/M6/M7/ME/MI 跨模型引用闭合   （CrossModelRefChecker）
      8. 模型声明 → 代码消费点           （DeclarationUsageChecker）
      9. M1 生命周期 ↔ M2 状态迁移闭合    （LifecycleClosureChecker）
      10. 行为形态三方对账                 （HandlerFormChecker）
      11. 声明消费点（规则自动推导）      （AutoDeclarationChecker）

.PARAMETER Quick
    仅执行第 1 项（PO 字段漂移）。用于 pre-commit —— 最快，且覆盖最高频的漂移。

.PARAMETER Fix
    打印修复建议。

.EXAMPLE
    .\scripts\check-model-drift.ps1 -Quick      # 同 pre-commit
    .\scripts\check-model-drift.ps1             # 同 pre-push（三项全查）
    .\scripts\check-model-drift.ps1 -Fix        # 带修复建议
#>
param(
    [switch]$Quick,
    [switch]$Fix
)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent $PSScriptRoot
$pluginDir = Join-Path $repoRoot 'codegen-maven-plugin'

Write-Host "============================================================"
Write-Host "  Model-vs-Code Drift Guard"
Write-Host "  repo: $repoRoot"
Write-Host "  mode: $(if ($Quick) { 'Quick (PoFieldDiff only)' } else { 'Full (11 checks)' })"
Write-Host "============================================================"

# ---------- 准备检查器 classpath ----------
$cpFile = Join-Path $pluginDir 'target\classes'
$depDir = Join-Path $pluginDir 'target\dependency'
$snakeYaml = Get-ChildItem -Path "$env:USERPROFILE\.m2\repository\org\yaml" -Recurse -Filter 'snakeyaml-*.jar' -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName

if (-not (Test-Path $cpFile)) {
    Write-Host "[0/4] 编译检查器..."
    Push-Location $pluginDir
    mvn -o -q compile 2>&1 | Out-Null
    Pop-Location
}

$cp = $cpFile
if ($snakeYaml) { $cp = "$cp;$snakeYaml" }

if (-not (Test-Path $cpFile)) {
    Write-Host "X 无法编译 codegen-maven-plugin，请先执行：mvn -pl codegen-maven-plugin compile"
    exit 1
}

$javaExe = $env:JAVA_HOME
if ($javaExe -and (Test-Path (Join-Path $javaExe 'bin\java.exe'))) {
    $javaExe = Join-Path $javaExe 'bin\java.exe'
} else {
    $javaExe = 'java'
}

$total = if ($Quick) { 1 } else { 11 }
$failed = @()

function Invoke-Checker {
    param(
        [string]$Title,
        [string]$Class,
        [string[]]$CheckerArgs,
        [int]$Index,
        [int]$Total
    )
    Write-Host ""
    Write-Host "[$Index/$Total] $Title"
    Write-Host "------------------------------------------------------------"
    $out = & $javaExe -cp $cp $Class @CheckerArgs 2>&1
    $code = $LASTEXITCODE
    $out | ForEach-Object { Write-Host $_ }
    if ($code -ne 0) {
        $script:failed += $Title
    }
    if ($Fix -and $code -ne 0) {
        Write-Host ""
        Write-Host "  修复建议："
        Write-Host "    · 模型改了但代码没改 → 重新编译让 codegen 重新生成（mvn clean install -DskipTests）"
        Write-Host "    · 代码改了但模型没改 → 在 models/*.yaml 补上对应声明"
        Write-Host "    · 故意保留差异       → 在 PO 字段上标 @Deprecated，或本项标为豁免"
    }
}

# ---------- 1. M1 ↔ PO ----------
# PoFieldDiffReporter <m1.yaml> <po-source-root> [package-prefix]
# 检查器按 root + package-prefix 定位 PO，故 root 传「包路径的父目录」：
#   · 生成：jjy-infrastructure/target/generated-sources/po
#     → 实际文件 .../po/com/jjy/infrastructure/persistence/po/ProductPO.java
#   · 手写：jjy-infrastructure/src/main/java/com/jjy/infrastructure
#     → 实际文件 .../infrastructure/persistence/po/ProductPO.java
#
# 两个根都要查：生成的 PO 在 target 下，手写的 PO 在 src 下。
# 只查一个会漏——生成目录不存在时回退到手写目录，
# 手写目录里恰好一个聚合根 PO 都没有 → 误判「全部缺失」。
$genPoBase = Join-Path $repoRoot 'jjy-infrastructure\target\generated-sources\po'
$handPoBase = Join-Path $repoRoot 'jjy-infrastructure\src\main\java\com\jjy\infrastructure'
$poRoots = @($handPoBase)
if (Test-Path -LiteralPath $genPoBase) {
    $poRoots = @($genPoBase, $handPoBase)
}
Invoke-Checker -Title 'M1 Object Model <-> Java PO (PoFieldDiffReporter)' `
    -Class 'com.jjy.codegen.PoFieldDiffReporter' `
    -CheckerArgs @((Join-Path $repoRoot 'models\m1-object-model.yaml'), ($poRoots -join ';'), 'com.jjy.infrastructure.persistence.po') `
    -Index 1 -Total $total

if (-not $Quick) {
    # ---------- 2. MU ↔ 前端路由 ----------
    # MenuRouteChecker <mu.yaml> <router.js>
    $routerPath = Join-Path $repoRoot '..\web\src\router\index.js'
    if (-not (Test-Path $routerPath)) {
        $routerPath = Join-Path $repoRoot 'web\src\router\index.js'
    }
    Invoke-Checker -Title 'MU UI Model <-> Frontend Router (MenuRouteChecker)' `
        -Class 'com.jjy.codegen.MenuRouteChecker' `
        -CheckerArgs @((Join-Path $repoRoot 'models\mu-ui-model.yaml'), $routerPath) `
        -Index 2 -Total $total

    # ---------- 3. M2 ↔ Handler ----------
    # BehaviorRegistryChecker <m2.yaml> <java-source-root>
    $handlerDir = Join-Path $repoRoot 'jjy-application\src\main\java'
    Invoke-Checker -Title 'M2 Behavior Model <-> Handler Registration (BehaviorRegistryChecker)' `
        -Class 'com.jjy.codegen.BehaviorRegistryChecker' `
        -CheckerArgs @((Join-Path $repoRoot 'models\m2-behavior-model.yaml'), $handlerDir) `
        -Index 3 -Total $total

    # ---------- 4. M1 ↔ MM ----------
    # MappingDiffReporter <m1.yaml> <mm.yaml>
    Invoke-Checker -Title 'M1 <-> MM Mapping Contract (MappingDiffReporter)' `
        -Class 'com.jjy.codegen.MappingDiffReporter' `
        -CheckerArgs @((Join-Path $repoRoot 'models\m1-object-model.yaml'), (Join-Path $repoRoot 'models\mm-mapping-model.yaml')) `
        -Index 4 -Total $total

    # ---------- 5. M3 ↔ M2 ----------
    # RuleRegistryChecker <m3.yaml> <m2.yaml> [m4.yaml]
    Invoke-Checker -Title 'M3 Rule Model <-> M2 Behavior Model (RuleRegistryChecker)' `
        -Class 'com.jjy.codegen.RuleRegistryChecker' `
        -CheckerArgs @((Join-Path $repoRoot 'models\m3-rule-model.yaml'), (Join-Path $repoRoot 'models\m2-behavior-model.yaml'), (Join-Path $repoRoot 'models\m4-scenario-model.yaml')) `
        -Index 5 -Total $total

    # ---------- 6. M2 ↔ M5 ----------
    # PermissionRegistryChecker <m2.yaml> <m5.yaml>
    # M2.requiredPermissions 必须引用 M5 声明的 permissionId（方法论 §3.2.1）
    Invoke-Checker -Title 'M2 Behavior Model <-> M5 Actor Model (PermissionRegistryChecker)' `
        -Class 'com.jjy.codegen.PermissionRegistryChecker' `
        -CheckerArgs @((Join-Path $repoRoot 'models\m2-behavior-model.yaml'), (Join-Path $repoRoot 'models\m5-actor-model.yaml')) `
        -Index 6 -Total $total

    # ---------- 7. M4/M6/M7/ME/MI 跨模型引用 ----------
    # CrossModelRefChecker <models-dir>
    # 这几个模型既无专门守门员、也未被运行期 validateReferences 覆盖，
    # 引用指向不存在的声明时属于「静音失败」——模型装载照常成功，
    # 直到流程/事件真的走到那个节点才抛异常。本项专门兜住这类漂移。
    Invoke-Checker -Title 'M4/M6/M7/ME/MI Cross-Model Refs (CrossModelRefChecker)' `
        -Class 'com.jjy.codegen.CrossModelRefChecker' `
        -CheckerArgs @((Join-Path $repoRoot 'models')) `
        -Index 7 -Total $total

    # ---------- 8. 声明 → 消费点 ----------
    # DeclarationUsageChecker <models-dir> <java-source-roots>
    # 前 7 项对账的是「模型 ↔ 代码结构」；本项管的是另一类：
    # 「模型声明了，但运行期根本不消费它」——L1 有、L2 缺、L3 未接通。
    # 这类缺口最隐蔽：模型越完整、代码跑得越正常，越没人发现。
    $srcRoots = @(
        (Join-Path $repoRoot 'jjy-domain\src\main\java'),
        (Join-Path $repoRoot 'jjy-application\src\main\java'),
        (Join-Path $repoRoot 'jjy-infrastructure\src\main\java'),
        (Join-Path $repoRoot 'jjy-interfaces\src\main\java')
    ) | Where-Object { Test-Path $_ }
    Invoke-Checker -Title 'Model Declaration -> Code Consumption (DeclarationUsageChecker)' `
        -Class 'com.jjy.codegen.DeclarationUsageChecker' `
        -CheckerArgs @((Join-Path $repoRoot 'models'), ($srcRoots -join ';')) `
        -Index 8 -Total $total

    # ---------- 9. M1 生命周期 ↔ M2 状态迁移 ----------
    # LifecycleClosureChecker <models-dir>
    # 状态机分散在两个模型里：M1 声明合法状态集合与迁移表，
    # M2 声明行为完成后状态变成什么。各自校验会漏掉「互相矛盾」：
    # M2 的 setValue 不是合法状态、M2 与 M1 迁移表说的目标状态不一致。
    Invoke-Checker -Title 'M1 Lifecycle <-> M2 State Transition (LifecycleClosureChecker)' `
        -Class 'com.jjy.codegen.LifecycleClosureChecker' `
        -CheckerArgs @((Join-Path $repoRoot 'models')) `
        -Index 9 -Total $total

    # ---------- 10. 行为形态三方对账 ----------
    # HandlerFormChecker <m2.yaml> <java-source-root>
    # M2 声明 behaviorForm ↔ 生成物 ↔ 手写实现，三者必须自洽：
    #   · 声明 TEMPLATE 但无实现 → 运行期才会抛「没有实现」
    #   · 声明 TEMPLATE 但混入手写 → 谁生效取决于扫描顺序（不确定的静默行为）
    #   · 生成物被开发者改过   → 模型变更不会自动同步，需要人工核对
    Invoke-Checker -Title 'Behavior Form 3-way Closure (HandlerFormChecker)' `
        -Class 'com.jjy.codegen.HandlerFormChecker' `
        -CheckerArgs @((Join-Path $repoRoot 'models\m2-behavior-model.yaml'), (Join-Path $repoRoot 'jjy-application\src\main\java')) `
        -Index 10 -Total $total

    # ---------- 11. 声明消费点（规则表自动推导）----------
    # AutoDeclarationChecker <models-dir> <java-source-roots> [codegen-src-root]
    # 扫描全部模型文件，自动推导每个语义字段的检查项——
    # 新增模型字段时检查项自动跟上，不再依赖人记得手工维护规则表。
    # 扫描范围含 codegen 插件：生成器本身就是模型的消费者。
    $autoRoots = @(
        (Join-Path $repoRoot 'jjy-domain\src\main\java'),
        (Join-Path $repoRoot 'jjy-application\src\main\java'),
        (Join-Path $repoRoot 'jjy-infrastructure\src\main\java'),
        (Join-Path $repoRoot 'jjy-interfaces\src\main\java'),
        (Join-Path $repoRoot 'codegen-maven-plugin\src\main\java')
    ) | Where-Object { Test-Path $_ }
    Invoke-Checker -Title 'Auto-derived Declaration Consumption (AutoDeclarationChecker)' `
        -Class 'com.jjy.codegen.AutoDeclarationChecker' `
        -CheckerArgs @((Join-Path $repoRoot 'models'), ($autoRoots -join ';')) `
        -Index 11 -Total $total
}

# ---------- 汇总 ----------
Write-Host ""
Write-Host "============================================================"
if ($failed.Count -eq 0) {
    Write-Host "  PASS  全部检查通过，模型与代码一致"
    Write-Host "============================================================"
    exit 0
}
else {
    Write-Host "  FAILED $($failed.Count) check(s):"
    $failed | ForEach-Object { Write-Host "    - $_" }
    Write-Host "============================================================"
    Write-Host ""
    Write-Host "  提交已被阻断。修复漂移后重试，或 git commit --no-verify（慎用）。"
    exit 1
}

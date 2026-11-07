<#
.SYNOPSIS
    守门员自验证（反向实验）——确认漂移守门员真能拦住，而不是永远 PASS。

.DESCRIPTION
    一个从不报错的检查器比没有检查器更危险：它让人**以为**防线在。

    本脚本对每类典型漂移做「注入实验」：
        1. 备份模型文件；
        2. 故意注入一处漂移；
        3. 跑对应检查器，要求它**必须报错**（exit != 0）；
        4. 还原模型文件。

    任一注入未被拦截 → 脚本 exit 1，说明该类漂移的防线已退化。

    首次运行时曾有 6 项注入只有 1 项被拦下，据此补齐了
    第 5 项（复用双向索引）、第 7 项（transitions / handlerRef）、
    第 9 项（状态迁移闭合）的三处盲区。

.PARAMETER KeepModels
    注入失败时不还原模型（用于人工排查）。默认自动还原。

.EXAMPLE
    .\scripts\verify-guards.ps1
    .\scripts\verify-guards.ps1 -KeepModels
#>
param(
    [switch]$KeepModels
)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent $PSScriptRoot
$pluginDir = Join-Path $repoRoot 'codegen-maven-plugin'
$modelsDir = Join-Path $repoRoot 'models'

Write-Host "============================================================"
Write-Host "  Guard Self-Verification (negative test)"
Write-Host "  repo: $repoRoot"
Write-Host "============================================================"

# ---------- 准备检查器 classpath ----------
$cpFile = Join-Path $pluginDir 'target\classes'
if (-not (Test-Path $cpFile)) {
    Write-Host "[0/1] compiling checkers..."
    Push-Location $pluginDir
    mvn -o -q compile 2>&1 | Out-Null
    Pop-Location
}
if (-not (Test-Path $cpFile)) {
    Write-Host "X cannot compile codegen-maven-plugin"
    exit 1
}
$cp = $cpFile
$snakeYaml = Get-ChildItem -Path "$env:USERPROFILE\.m2\repository\org\yaml" -Recurse -Filter 'snakeyaml-*.jar' -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if ($snakeYaml) { $cp = "$cp;$snakeYaml" }

$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $javaExe)) { $javaExe = 'java' }

# ---------- 注入用例 ----------
# 每行一个用例，字段用 | 分隔：
#   标题 | 模型文件 | 锚点行（该行会被替换） | 注入行 | 检查器类 | 检查器参数（; 分隔）
$NL = "`n"
$m3Path = Join-Path $modelsDir 'm3-rule-model.yaml'
$m2Path = Join-Path $modelsDir 'm2-behavior-model.yaml'

$cases = @(
    @(
        "M3.reusedBy inconsistent with M2.appliedRules",
        "m3-rule-model.yaml",
        "- Product_SaveAsDraft",
        "- Product_Submit",
        "com.jjy.codegen.RuleRegistryChecker",
        "$m3Path;$m2Path"
    ),
    @(
        "M2.preconditions references a non-existent rule",
        "m2-behavior-model.yaml",
        "- RULE-PRODUCT-NAME-REQUIRED",
        "- RULE-NOT-EXIST",
        "com.jjy.codegen.RuleRegistryChecker",
        "$m3Path;$m2Path"
    ),
    @(
        "M2.postconditions target status not in M1 lifecycle",
        "m2-behavior-model.yaml",
        "setValue: ON_SHELF",
        "setValue: NOT_A_VALID_STATE",
        "com.jjy.codegen.LifecycleClosureChecker",
        "$modelsDir"
    ),
    @(
        "M1.transitions via points to a non-existent behavior",
        "m1-object-model.yaml",
        "via: Product_TakeOff",
        "via: Product_NotExist",
        "com.jjy.codegen.CrossModelRefChecker",
        "$modelsDir"
    ),
    @(
        "M1.transitions to not in lifecycle",
        "m1-object-model.yaml",
        "to: OFF_SHELF",
        "to: NOT_IN_LIFECYCLE",
        "com.jjy.codegen.CrossModelRefChecker",
        "$modelsDir"
    ),
    @(
        "ME.subscribers handlerRef points to a non-existent behavior",
        "me-event-model.yaml",
        "handlerRef: Product_OnShelf",
        "handlerRef: Product_Nope",
        "com.jjy.codegen.CrossModelRefChecker",
        "$modelsDir"
    )
)

# ---------- 执行 ----------
$passed = 0
$failed = 0
$total = $cases.Count

Write-Host ""
for ($i = 0; $i -lt $total; $i++) {
    $c = $cases[$i]
    $title    = [string]$c[0]
    $file     = [string]$c[1]
    $anchor   = [string]$c[2]
    $inject   = [string]$c[3]
    $cls      = [string]$c[4]
    $argStr   = [string]$c[5]

    $path = Join-Path $modelsDir $file
    $bak = "$path.guardbak"

    Copy-Item -LiteralPath $path -Destination $bak -Force
    try {
        $lines = [System.IO.File]::ReadAllLines($path)
        $hit = -1
        for ($k = 0; $k -lt $lines.Length; $k++) {
            $t = $lines[$k].Trim()
            if ($t -eq $anchor -or $t.EndsWith($anchor)) {
                $hit = $k
                break
            }
        }
        if ($hit -lt 0) {
            Write-Host "[$($i+1)/$total] $title"
            Write-Host "    INJECTION FAILED (anchor line not found)"
            $failed++
            continue
        }
        $indent = $lines[$hit].Length - $lines[$hit].TrimStart().Length
        $lines[$hit] = (' ' * $indent) + $inject
        [System.IO.File]::WriteAllLines($path, $lines, (New-Object System.Text.UTF8Encoding($false)))

        Write-Host "[$($i+1)/$total] $title"
        $cargs = $argStr -split ';'
        & $javaExe -cp $cp $cls @cargs 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "    NOT CAUGHT  -- this drift class has no working guard"
            $failed++
        } else {
            Write-Host "    CAUGHT OK"
            $passed++
        }
    } finally {
        if (Test-Path -LiteralPath $bak) {
            if ($KeepModels) {
                Write-Host "    (-KeepModels: $bak left in place)"
            } else {
                Move-Item -LiteralPath $bak -Destination $path -Force
            }
        }
    }
}

Write-Host ""
Write-Host "============================================================"
if ($failed -eq 0) {
    Write-Host "  PASS  $passed/$total injected drift classes were all caught"
    Write-Host "============================================================"
    exit 0
} else {
    Write-Host "  FAILED  $failed drift classes were not caught ($passed/$total passed)"
    Write-Host "============================================================"
    Write-Host ""
    Write-Host "  An uncaught drift class means the corresponding guard is missing or degraded."
    Write-Host "  Models have been restored (unless -KeepModels was given)."
    exit 1
}

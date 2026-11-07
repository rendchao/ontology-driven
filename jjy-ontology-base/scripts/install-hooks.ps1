<#
.SYNOPSIS
    安装模型漂移守门员 git 钩子。

.DESCRIPTION
    把「模型改了代码必须跟着改」刻进 Git：
      · pre-commit  → 跑 check-model-drift.ps1 -Quick（只查 PO 字段，最快）
      · pre-push    → 跑 check-model-drift.ps1（11 项全查）+ verify-guards.ps1（守门员自验证）

    发现漂移即拒绝提交/推送。

.PARAMETER Uninstall
    卸载已安装的钩子。

.EXAMPLE
    .\scripts\install-hooks.ps1
    .\scripts\install-hooks.ps1 -Uninstall
#>
param(
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptDir = Join-Path $repoRoot 'scripts'

# 找到 git 仓库根（钩子必须装在 .git/hooks 下）
$gitRoot = $repoRoot
while ($gitRoot -and -not (Test-Path -LiteralPath (Join-Path $gitRoot '.git'))) {
    $parent = Split-Path $gitRoot -Parent
    if ($parent -eq $gitRoot) { $gitRoot = $null; break }
    $gitRoot = $parent
}

if (-not $gitRoot) {
    Write-Host "X 未找到 git 仓库（向上级目录查找 .git 失败）"
    exit 1
}

$hooksDir = Join-Path $gitRoot '.git\hooks'
if (-not (Test-Path -LiteralPath $hooksDir)) {
    New-Item -ItemType Directory -Force -Path $hooksDir | Out-Null
}

$preCommit = Join-Path $hooksDir 'pre-commit'
$prePush = Join-Path $hooksDir 'pre-push'

if ($Uninstall) {
    foreach ($h in @($preCommit, $prePush)) {
        if (Test-Path -LiteralPath $h) {
            Remove-Item $h -Force
            Write-Host "  - removed $(Split-Path $h -Leaf)"
        }
    }
    Write-Host "Done. drift guard hooks uninstalled."
    exit 0
}

# ── pre-commit：只查 PO 字段漂移（最快，覆盖最高频）───
$preCommitBody = @"
#!/bin/sh
# Model-vs-Code Drift Guard (auto-installed)
# 拒绝提交：M1 对象模型与 Java PO 出现字段漂移
exec powershell -NoProfile -ExecutionPolicy Bypass -File "$scriptDir\check-model-drift.ps1" -Quick
"@

# ── pre-push：11 项全查 + 守门员自验证 ──
# 自验证也放在 pre-push：确认守门员本身没退化。
# 一个从不报错的检查器比没有检查器更危险——它让人以为防线在。
$prePushBody = @"
#!/bin/sh
# Model-vs-Code Drift Guard (auto-installed)
# 拒绝推送：模型与代码存在任何漂移
powershell -NoProfile -ExecutionPolicy Bypass -File "$scriptDir\check-model-drift.ps1" || exit 1
powershell -NoProfile -ExecutionPolicy Bypass -File "$scriptDir\verify-guards.ps1" || exit 1
"@

[System.IO.File]::WriteAllText($preCommit, $preCommitBody, (New-Object Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText($prePush, $prePushBody, (New-Object Text.UTF8Encoding($false)))

Write-Host "=== Installing drift guard ==="
Write-Host "  repo: $gitRoot"
Write-Host "  + installed pre-commit  (PO field drift)"
Write-Host "  + installed pre-push    (11 checks + guard self-verification)"
Write-Host "Done. Next git commit will run the drift guard automatically."
Write-Host ""
Write-Host "  守门员自验证（可单独跑）："
Write-Host "    .\scripts\verify-guards.ps1"
Write-Host ""
Write-Host "  紧急放行：git commit --no-verify"

<#
.SYNOPSIS
    重算 manifest.json 中各模型文件的 SHA-256。

.DESCRIPTION
    改了 models/*.yaml 之后运行本脚本，把新哈希写回 manifest.json。

    启动期 ModelLoader 会比对哈希，不一致时 WARN（不阻断启动）——
    因为模型被合法修改后忘了重算是常见情况，硬阻断会误伤开发。
    但保持 manifest 与模型一致，能让"模型被谁改过"可追溯。

.PARAMETER Check
    只检查不写入；发现不一致时以退出码 1 结束（可用于 CI）。

.EXAMPLE
    .\scripts\regenerate-manifest.ps1
    .\scripts\regenerate-manifest.ps1 -Check
#>
param(
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$modelsDir = Join-Path $repoRoot 'models'
$manifestPath = Join-Path $modelsDir 'manifest.json'

if (-not (Test-Path -LiteralPath $manifestPath)) {
    Write-Host "X 找不到 manifest.json: $manifestPath"
    exit 1
}

$manifest = Get-Content $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json

# 按 model_files 声明的顺序重算（未登记的新文件会提示补登记）
$entries = @()
$mismatch = $false

foreach ($mf in $manifest.model_files) {
    $file = if ($mf -is [string]) { $mf } else { $mf.file }
    $path = Join-Path $modelsDir $file
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Host "X 缺少模型文件: $file"
        $mismatch = $true
        continue
    }
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower()
    $expected = if ($mf -is [string]) { $null } else { $mf.sha256 }
    if ($expected -ne $hash) {
        $mismatch = $true
        Write-Host "  CHANGED  $file"
        Write-Host "           old: $expected"
        Write-Host "           new: $hash"
    } else {
        Write-Host "  OK       $file"
    }
    $entries += [ordered]@{ file = $file; sha256 = $hash }
}

# 检查是否有未登记的模型文件
$registered = $manifest.model_files | ForEach-Object { if ($_ -is [string]) { $_ } else { $_.file } }
$onDisk = Get-ChildItem $modelsDir -File -Filter '*.yaml' | Select-Object -ExpandProperty Name
$unregistered = $onDisk | Where-Object { $registered -notcontains $_ }
if ($unregistered) {
    Write-Host ""
    Write-Host "  以下模型文件未登记进 manifest.json，请补充："
    $unregistered | ForEach-Object { Write-Host "    - $_" }
    $mismatch = $true
}

if ($Check) {
    if ($mismatch) {
        Write-Host ""
        Write-Host "X manifest 与模型文件不一致"
        exit 1
    }
    Write-Host ""
    Write-Host "manifest.json 与模型文件一致"
    exit 0
}

if (-not $mismatch) {
    Write-Host ""
    Write-Host "manifest.json 已与模型文件一致，无需更新"
    exit 0
}

# 写回 manifest（保持字段顺序与格式）
$new = [ordered]@{
    model_type       = $manifest.model_type
    version          = $manifest.version
    domain           = $manifest.domain
    project          = $manifest.project
    source_requirements = $manifest.source_requirements
    framework        = $manifest.framework
    generatedAt      = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
    modelCount       = $entries.Count
    model_files      = $entries
    build_order      = $manifest.build_order
}

$json = $new | ConvertTo-Json -Depth 10
[System.IO.File]::WriteAllText($manifestPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "manifest.json 已更新（$($entries.Count) 个模型）"
Write-Host "  path: $manifestPath"

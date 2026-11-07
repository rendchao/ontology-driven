<#
.SYNOPSIS
    批量改包名（clone 底座为业务工程时使用）。

.DESCRIPTION
    一次性把底座的包名从旧值改为新值，处理三类内容：
      1. Java 文件的 package / import 语句
      2. 目录结构（com/jjy/... → com/mycompany/app/...）
      3. pom 的 groupId / artifactId / <po.root.pkg>，以及 YAML / 资源文件里的包引用

    改完会打印残留校验结果（应为 0）。

.PARAMETER OldPkg
    旧包名，默认 com.jjy。

.PARAMETER NewPkg
    新包名，例如 com.mycompany.app。

.EXAMPLE
    .\scripts\rename-package.ps1 -OldPkg "com.jjy" -NewPkg "com.mycompany.app"
#>
param(
    [string]$OldPkg = 'com.jjy',
    [string]$NewPkg = 'com.mycompany.app'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if ($OldPkg -eq $NewPkg) {
    Write-Host "旧包名与新包名相同，无需处理"
    exit 0
}

Write-Host "============================================================"
Write-Host "  Rename package"
Write-Host "  from: $OldPkg"
Write-Host "  to:   $NewPkg"
Write-Host "============================================================"

$oldPath = $OldPkg.Replace('.', '\')
$newPath = $NewPkg.Replace('.', '\')

# ── 1. 改写文件内容 ──
$ext = @('.java', '.xml', '.yml', '.yaml', '.sql', '.properties', '.json', '.md')
$files = Get-ChildItem -Path $repoRoot -Recurse -File |
    Where-Object { $_.FullName -notmatch '\\target\\' -and $ext -contains $_.Extension.ToLower() }

$changed = 0
foreach ($f in $files) {
    $raw = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $new = $raw.Replace($OldPkg, $NewPkg)
    if ($new -ne $raw) {
        [System.IO.File]::WriteAllText($f.FullName, $new, (New-Object System.Text.UTF8Encoding($false)))
        $changed++
    }
}
Write-Host "[1/3] 内容改写: $changed 个文件"

# ── 2. 迁移目录结构（自底向上，避免路径失效）───
$dirs = Get-ChildItem -Path $repoRoot -Recurse -Directory |
    Where-Object { $_.FullName -notmatch '\\target\\' -and $_.FullName -like "*\$oldPath*" } |
    Sort-Object { $_.FullName.Length } -Descending

$dirMoves = 0
foreach ($d in $dirs) {
    $newFull = $d.FullName.Replace("\$oldPath", "\$newPath")
    if (Test-Path -LiteralPath $newFull) {
        # 目标已存在：逐文件合并
        Get-ChildItem $d.FullName -Recurse -File | ForEach-Object {
            $rel = $_.FullName.Substring($d.FullName.Length).TrimStart('\')
            $dest = Join-Path $newFull $rel
            $destDir = Split-Path $dest -Parent
            if (-not (Test-Path -LiteralPath $destDir)) {
                New-Item -ItemType Directory -Force -Path $destDir | Out-Null
            }
            Move-Item $_.FullName $dest -Force
        }
        Remove-Item $d.FullName -Recurse -Force
    }
    else {
        $parent = Split-Path $newFull -Parent
        if (-not (Test-Path -LiteralPath $parent)) {
            New-Item -ItemType Directory -Force -Path $parent | Out-Null
        }
        Move-Item $d.FullName $newFull -Force
    }
    $dirMoves++
}
Write-Host "[2/3] 目录迁移: $dirMoves 个"

# ── 3. 清理空的旧包父目录 ──
$oldLeaf = ($OldPkg -split '\.')[-1]
Get-ChildItem -Path $repoRoot -Recurse -Directory |
    Where-Object { $_.FullName -notmatch '\\target\\' -and $_.Name -eq $oldLeaf } |
    ForEach-Object {
        if (-not (Get-ChildItem $_.FullName -Force)) {
            Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
        }
    }

# ── 4. 校验残留 ──
$left = Get-ChildItem -Path $repoRoot -Recurse -File |
    Where-Object { $_.FullName -notmatch '\\target\\' -and $ext -contains $_.Extension.ToLower() } |
    Where-Object { Select-String -Path $_.FullName -Pattern ([regex]::Escape($OldPkg)) -Quiet }

Write-Host "[3/3] 残留校验: $($left.Count) 个文件仍含旧包名"
if ($left.Count -gt 0) {
    $left | Select-Object -First 10 | ForEach-Object {
        Write-Host "    $($_.FullName.Replace($repoRoot, ''))"
    }
    Write-Host ""
    Write-Host "请手动处理上述文件。"
    exit 1
}

Write-Host ""
Write-Host "============================================================"
Write-Host "  完成。后续必做："
Write-Host "    1. mvn -o clean install -DskipTests"
Write-Host "    2. .\scripts\check-model-drift.ps1"
Write-Host "    3. 检查 pom 的 <artifactId> 是否已改为你的工程名"
Write-Host "============================================================"

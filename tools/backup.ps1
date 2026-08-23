# PastoralCraft workspace snapshot backup script
# Usage: pwsh -File tools/backup.ps1 -Title "change title"
# Output: backups/pastoralcraft_<yyyyMMdd-HHmmss>_<title>.zip
# Excludes: reference/ build/ .git/ backups/

[CmdletBinding()]
param(
    [string]$Title = "manual-backup"
)

$ErrorActionPreference = "Stop"

$Workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$BackupDir = Join-Path $Workspace 'backups'
$Exclude   = @('.git', '.gradle', 'build', 'reference', 'run', 'backups')

# Sanitize title: strip illegal filename chars, whitespace -> '-', max length 80
$safe = $Title -replace '[\\/:*?"<>|]', '' -replace '\s+', '-'
$safe = $safe.Trim('.', ' ', '-')
if (-not $safe) { $safe = 'manual-backup' }
if ($safe.Length -gt 80) { $safe = $safe.Substring(0, 80) }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$base  = "pastoralcraft_${stamp}_${safe}"

if (-not (Test-Path $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir | Out-Null }

$zipPath = Join-Path $BackupDir "$base.zip"
$n = 1
while (Test-Path $zipPath) {
    $n++
    $zipPath = Join-Path $BackupDir "$base-$n.zip"
}

$items = Get-ChildItem -Path $Workspace -Force |
    Where-Object { $Exclude -notcontains $_.Name } |
    Select-Object -ExpandProperty Name

if (-not $items) { throw "No files to back up" }

Push-Location $Workspace
try {
    Compress-Archive -Path $items -DestinationPath $zipPath -CompressionLevel Optimal
}
finally {
    Pop-Location
}

$sizeMB = '{0:N2}' -f ((Get-Item $zipPath).Length / 1MB)
Write-Output "Backup done -> $zipPath ($sizeMB MB)"

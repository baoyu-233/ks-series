# ============================================================
# KS-ItemEditor Auto Build & Deploy Script
# Usage: .\deploy.ps1 [-SkipBuild] [-NoBackup] [-Restart]
#   -SkipBuild  Skip Maven build (assume JAR already exists)
#   -NoBackup   Skip backing up old versions
#   -Restart    Restart test server after deploy (联动 server-control.ps1)
# ============================================================

param(
    [switch]$SkipBuild,
    [switch]$NoBackup,
    [switch]$Restart
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$SeriesRoot  = Join-Path $ProjectRoot ".."
$TestServerDir = Join-Path $SeriesRoot "test_1_21"
$TestServerPlugins = Join-Path $TestServerDir "plugins"
$ArchivesDir = Join-Path $TestServerDir "archives"
$ServerControl = Join-Path $SeriesRoot "server-control.ps1"
$SrcConfig   = Join-Path $ProjectRoot "src\main\resources\config.yml"
$ServerConfig = Join-Path $TestServerPlugins "KS-ItemEditor\config.yml"

[xml]$pomXml = Get-Content (Join-Path $ProjectRoot "pom.xml") -Encoding UTF8
$version = $pomXml.project.version
$jarName = "KS-ItemEditor-$version.jar"
$jarPath = Join-Path $ProjectRoot "target\$jarName"

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " KS-ItemEditor Build & Deploy v$version" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

# --- Build ---
if (-not $SkipBuild) {
    Write-Host "[Build] Running mvn clean package..." -ForegroundColor Yellow
    Push-Location $ProjectRoot
    try {
        mvn clean package -q
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
        Write-Host "[Build] OK" -ForegroundColor Green
    } finally { Pop-Location }
} else { Write-Host "[Build] Skipped (using existing JAR)" -ForegroundColor Gray }

if (-not (Test-Path $jarPath)) { Write-Error "JAR not found: $jarPath"; exit 1 }

# --- Backup ---
if (-not $NoBackup) {
    if (-not (Test-Path $ArchivesDir)) { New-Item -ItemType Directory -Path $ArchivesDir -Force | Out-Null }
    $deployedJars = Get-ChildItem $TestServerPlugins "KS-ItemEditor-*.jar" -ErrorAction SilentlyContinue
    foreach ($oldJar in $deployedJars) {
        $ts = Get-Date -Format 'yyyyMMdd-HHmmss'
        Copy-Item $oldJar.FullName (Join-Path $ArchivesDir "KS-ItemEditor-backup-$ts-$($oldJar.Name)")
        Write-Host "[Backup] $($oldJar.Name)" -ForegroundColor Gray
    }
}

# --- Deploy ---
Copy-Item $jarPath $TestServerPlugins -Force
Get-ChildItem $TestServerPlugins "KS-ItemEditor-*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.Name -ne $jarName) { Remove-Item $_.FullName -Force; Write-Host "[Deploy] Removed old: $($_.Name)" -ForegroundColor Gray }
}
# Legacy cleanup
$oldIeJars = Get-ChildItem $TestServerPlugins "ItemEditor-*.jar" -ErrorAction SilentlyContinue
foreach ($oldJar in $oldIeJars) {
    if (-not $NoBackup) { Copy-Item $oldJar.FullName (Join-Path $ArchivesDir "legacy-$($oldJar.Name)") }
    Remove-Item $oldJar.FullName -Force; Write-Host "[Deploy] Removed legacy: $($oldJar.Name)" -ForegroundColor Yellow
}
Write-Host "[Deploy] OK - $jarName" -ForegroundColor Green

# --- Config sync ---
if (Test-Path $SrcConfig) {
    Write-Host "[Config] Syncing config.yml..." -ForegroundColor Yellow
    $srcHash = (Get-FileHash $SrcConfig -Algorithm MD5).Hash
    $destHash = if (Test-Path $ServerConfig) { (Get-FileHash $ServerConfig -Algorithm MD5).Hash } else { "" }
    if ($srcHash -ne $destHash) {
        $configDir = Split-Path $ServerConfig -Parent
        if (-not (Test-Path $configDir)) { New-Item -ItemType Directory -Path $configDir -Force | Out-Null }
        if (Test-Path $ServerConfig) {
            $ts = Get-Date -Format 'yyyyMMdd-HHmmss'
            Copy-Item $ServerConfig (Join-Path $ArchivesDir "KS-ItemEditor-config-backup-$ts-config.yml")
        }
        Copy-Item $SrcConfig $ServerConfig -Force
        Write-Host "[Config] config.yml synced (source changed)" -ForegroundColor Green
        Write-Host "[Config] WARNING: Custom settings on test server may have been overwritten!" -ForegroundColor Yellow
    } else { Write-Host "[Config] Unchanged, skip." -ForegroundColor Green }
}

# --- Restart ---
if ($Restart) {
    Write-Host ""
    Write-Host ">>> Restarting test server..." -ForegroundColor Cyan
    if (Test-Path $ServerControl) {
        & $ServerControl -Action Restart -ServerDir $TestServerDir
    } else {
        Write-Host "[Server] server-control.ps1 not found!" -ForegroundColor Red
    }
} else {
    Write-Host "[Server] Use -Restart to auto-restart, or .\server-control.ps1 -Action Status" -ForegroundColor Cyan
}

Write-Host ""
Write-Host "Done! JAR: $jarName" -ForegroundColor Green

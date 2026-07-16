# ============================================================
# ks-core Auto Build & Deploy Script
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

# Read version from pom.xml
[xml]$pomXml = Get-Content (Join-Path $ProjectRoot "pom.xml") -Encoding UTF8
$version = $pomXml.project.version
$jarName = "ks-core-$version.jar"
$jarPath = Join-Path $ProjectRoot "target\$jarName"

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " ks-core Build & Deploy v$version" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

# ============================================================
# Build
# ============================================================
if (-not $SkipBuild) {
    Write-Host "[Build] Running mvn clean package..." -ForegroundColor Yellow
    Push-Location $ProjectRoot
    try {
        mvn clean package -q
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
        Write-Host "[Build] OK" -ForegroundColor Green
    } finally {
        Pop-Location
    }
} else {
    Write-Host "[Build] Skipped" -ForegroundColor Gray
}

if (-not (Test-Path $jarPath)) {
    Write-Error "JAR not found: $jarPath"
    exit 1
}

# ============================================================
# Backup old versions
# ============================================================
if (-not $NoBackup) {
    if (-not (Test-Path $ArchivesDir)) {
        New-Item -ItemType Directory -Path $ArchivesDir -Force | Out-Null
    }
    $oldJars = Get-ChildItem $TestServerPlugins "ks-core-*.jar" -ErrorAction SilentlyContinue
    foreach ($oldJar in $oldJars) {
        $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $backupName = "ks-core-backup-$timestamp-$($oldJar.Name)"
        Copy-Item $oldJar.FullName (Join-Path $ArchivesDir $backupName)
        Write-Host "[Backup] $($oldJar.Name) -> archives\$backupName" -ForegroundColor Gray
    }
}

# ============================================================
# Deploy (file replacement)
# ============================================================
Write-Host "[Deploy] Copying JAR to test server..." -ForegroundColor Yellow
Copy-Item $jarPath $TestServerPlugins -Force

$deployedJars = Get-ChildItem $TestServerPlugins "ks-core-*.jar" -ErrorAction SilentlyContinue
foreach ($jar in $deployedJars) {
    if ($jar.Name -ne $jarName) {
        Remove-Item $jar.FullName -Force
        Write-Host "[Deploy] Removed old: $($jar.Name)" -ForegroundColor Gray
    }
}
Write-Host "[Deploy] OK - $jarName" -ForegroundColor Green

# ============================================================
# Sync config.yml
# ============================================================
$ServerConfigDir  = Join-Path $TestServerPlugins "ks-core"
$ServerConfigFile = Join-Path $ServerConfigDir "config.yml"

if (Test-Path $SrcConfig) {
    Write-Host "[Config] Syncing config.yml..." -ForegroundColor Yellow

    if (Test-Path $ServerConfigFile) {
        if (-not $NoBackup) {
            $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            $configBackup = Join-Path $ArchivesDir "ks-core-config-backup-$timestamp-config.yml"
            Copy-Item $ServerConfigFile $configBackup
            Write-Host "[Config] Backed up server config.yml" -ForegroundColor Gray
        }
    }

    if (-not (Test-Path $ServerConfigDir)) {
        New-Item -ItemType Directory -Path $ServerConfigDir -Force | Out-Null
    }

    $srcHash = (Get-FileHash $SrcConfig -Algorithm MD5).Hash
    $destHash = ""
    if (Test-Path $ServerConfigFile) {
        $destHash = (Get-FileHash $ServerConfigFile -Algorithm MD5).Hash
    }

    if ($srcHash -ne $destHash) {
        Copy-Item $SrcConfig $ServerConfigFile -Force
        Write-Host "[Config] config.yml synced (source changed)" -ForegroundColor Green
    } else {
        Write-Host "[Config] config.yml unchanged, skip." -ForegroundColor Green
    }
}

# ============================================================
# Restart server (via server-control.ps1)
# ============================================================
if ($Restart) {
    Write-Host ""
    Write-Host ">>> Restarting test server..." -ForegroundColor Cyan
    if (Test-Path $ServerControl) {
        & $ServerControl -Action Restart -ServerDir $TestServerDir
    } else {
        Write-Host "[Server] server-control.ps1 not found!" -ForegroundColor Red
        Write-Host "[Server] Restart manually: .\server-control.ps1 -Action Restart" -ForegroundColor Yellow
    }
} else {
    Write-Host "[Server] Use -Restart to auto-restart after deploy." -ForegroundColor Cyan
    Write-Host "[Server] Or: .\server-control.ps1 -Action Status" -ForegroundColor Gray
}

Write-Host ""
Write-Host "Done! JAR: $jarName" -ForegroundColor Green

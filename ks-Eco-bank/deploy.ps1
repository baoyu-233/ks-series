# ============================================================
# ks-Eco-bank Auto Build & Deploy Script
# Usage: .\deploy.ps1 [-SkipBuild] [-NoBackup] [-Restart]
# ============================================================
param([switch]$SkipBuild, [switch]$NoBackup, [switch]$Restart)
$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$SeriesRoot  = Join-Path $ProjectRoot ".."
$TestServerDir = Join-Path $SeriesRoot "test_1_21"
$TestServerPlugins = Join-Path $TestServerDir "plugins"
$ArchivesDir = Join-Path $TestServerDir "archives"
$ServerControl = Join-Path $SeriesRoot "server-control.ps1"

[xml]$pomXml = Get-Content (Join-Path $ProjectRoot "pom.xml") -Encoding UTF8
$version = $pomXml.project.version
$jarName = "ks-Eco-bank-$version.jar"
$jarPath = Join-Path $ProjectRoot "target\$jarName"

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " ks-Eco-bank Build & Deploy v$version" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

if (-not $SkipBuild) {
    Write-Host "[Build] Running mvn clean package..." -ForegroundColor Yellow
    Push-Location $ProjectRoot
    try { mvn clean package -q; if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }; Write-Host "[Build] OK" -ForegroundColor Green }
    finally { Pop-Location }
} else { Write-Host "[Build] Skipped" -ForegroundColor Gray }

if (-not (Test-Path $jarPath)) { Write-Error "JAR not found: $jarPath"; exit 1 }

if (-not $NoBackup) {
    if (-not (Test-Path $ArchivesDir)) { New-Item -ItemType Directory -Path $ArchivesDir -Force | Out-Null }
    Get-ChildItem $TestServerPlugins "ks-Eco-bank-*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
        $ts = Get-Date -Format 'yyyyMMdd-HHmmss'
        Copy-Item $_.FullName (Join-Path $ArchivesDir "ks-Eco-bank-backup-$ts-$($_.Name)")
        Write-Host "[Backup] $($_.Name)" -ForegroundColor Gray
    }
}

Copy-Item $jarPath $TestServerPlugins -Force
Get-ChildItem $TestServerPlugins "ks-Eco-bank-*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.Name -ne $jarName) { Remove-Item $_.FullName -Force; Write-Host "[Deploy] Removed old: $($_.Name)" -ForegroundColor Gray }
}
Write-Host "[Deploy] OK - $jarName" -ForegroundColor Green

$SrcConfig = Join-Path $ProjectRoot "src\main\resources\config.yml"
$ServerConfigDir = Join-Path $TestServerPlugins "ks-Eco-bank"
$ServerConfigFile = Join-Path $ServerConfigDir "config.yml"
if (Test-Path $SrcConfig) {
    Write-Host "[Config] Syncing config.yml..." -ForegroundColor Yellow
    if ((Test-Path $ServerConfigFile) -and (-not $NoBackup)) {
        Copy-Item $ServerConfigFile (Join-Path $ArchivesDir "ks-Eco-bank-config-backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')-config.yml")
    }
    if (-not (Test-Path $ServerConfigDir)) { New-Item -ItemType Directory -Path $ServerConfigDir -Force | Out-Null }
    $srcHash = (Get-FileHash $SrcConfig -Algorithm MD5).Hash
    $destHash = if (Test-Path $ServerConfigFile) { (Get-FileHash $ServerConfigFile -Algorithm MD5).Hash } else { "" }
    if ($srcHash -ne $destHash) { Copy-Item $SrcConfig $ServerConfigFile -Force; Write-Host "[Config] Synced." -ForegroundColor Green }
    else { Write-Host "[Config] Unchanged." -ForegroundColor Green }
}

if ($Restart) {
    Write-Host ""; Write-Host ">>> Restarting..." -ForegroundColor Cyan
    if (Test-Path $ServerControl) { & $ServerControl -Action Restart -ServerDir $TestServerDir }
    else { Write-Host "[Server] server-control.ps1 not found!" -ForegroundColor Red }
} else { Write-Host "[Server] Use -Restart to auto-restart." -ForegroundColor Cyan }

Write-Host "Done! JAR: $jarName" -ForegroundColor Green

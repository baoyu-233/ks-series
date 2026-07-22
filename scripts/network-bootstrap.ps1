[CmdletBinding()]
param(
    [string] $Workspace = (Split-Path -Parent $PSScriptRoot),
    [string] $MinecraftVersion = '1.21.11'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workspacePath = (Resolve-Path -LiteralPath $Workspace).Path
$networkRoot = Join-Path $workspacePath 'network_1_21'
$headers = @{ 'User-Agent' = 'ks-series-network-builder/1.0 (local development)' }

function Get-StablePaperDownload {
    param([string] $Project)
    $uri = "https://fill.papermc.io/v3/projects/$Project/versions/$MinecraftVersion/builds"
    $builds = Invoke-RestMethod -Uri $uri -Headers $headers -TimeoutSec 60
    $build = $builds | Where-Object { $_.channel -eq 'STABLE' } | Select-Object -First 1
    if ($null -eq $build) { throw "No stable $Project build for $MinecraftVersion" }
    $download = $build.downloads.'server:default'
    if ($null -eq $download) { throw "Stable $Project build has no server download" }
    [pscustomobject]@{
        Project = $Project
        Build = $build.id
        Url = $download.url
        Sha256 = $download.checksums.sha256.ToUpperInvariant()
    }
}

function Save-VerifiedDownload {
    param([pscustomobject] $Download, [string] $Destination)
    Invoke-WebRequest -UseBasicParsing -Uri $Download.Url -Headers $headers -OutFile $Destination -TimeoutSec 180
    $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash
    if ($actual -ne $Download.Sha256) {
        Remove-Item -LiteralPath $Destination -Force
        throw "$($Download.Project) SHA-256 mismatch"
    }
    [pscustomobject]@{ project=$Download.Project; build=$Download.Build; file=$Destination; sha256=$actual }
}

if (-not (Test-Path -LiteralPath $networkRoot -PathType Container)) {
    throw "Network directory is missing: $networkRoot"
}

$results = @()
$results += Save-VerifiedDownload -Download (Get-StablePaperDownload 'paper') `
    -Destination (Join-Path $networkRoot 'paper-rpg\server.jar')
$results += Save-VerifiedDownload -Download (Get-StablePaperDownload 'folia') `
    -Destination (Join-Path $networkRoot 'folia-lab\server.jar')

$leavesSource = Join-Path $workspacePath 'test_1_21\server.jar'
if (-not (Test-Path -LiteralPath $leavesSource -PathType Leaf)) {
    throw "Leaves source JAR is missing: $leavesSource"
}
$leavesDestination = Join-Path $networkRoot 'leaves-survival\server.jar'
Copy-Item -LiteralPath $leavesSource -Destination $leavesDestination -Force
$results += [pscustomobject]@{
    project='leaves-local'
    build='test_1_21'
    file=$leavesDestination
    sha256=(Get-FileHash -LiteralPath $leavesDestination -Algorithm SHA256).Hash
}

$bungeeDestination = Join-Path $networkRoot 'proxy\BungeeCord.jar'
Invoke-WebRequest -UseBasicParsing `
    -Uri 'https://ci.md-5.net/job/BungeeCord/lastSuccessfulBuild/artifact/bootstrap/target/BungeeCord.jar' `
    -Headers $headers -OutFile $bungeeDestination -TimeoutSec 180
$results += [pscustomobject]@{
    project='bungeecord'
    build='lastSuccessfulBuild'
    file=$bungeeDestination
    sha256=(Get-FileHash -LiteralPath $bungeeDestination -Algorithm SHA256).Hash
}

$results | ConvertTo-Json -Depth 4

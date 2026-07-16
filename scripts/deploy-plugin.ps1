[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $Workspace,
    [Parameter(Mandatory = $true)] [string] $Module,
    [Parameter(Mandatory = $true)] [string] $Artifact,
    [Parameter(Mandatory = $true)] [string] $DeployJar,
    [Parameter(Mandatory = $true)] [string] $PluginId,
    [switch] $SkipTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-WorkspacePath {
    param([string] $Root, [string] $Value, [switch] $MustExist)

    $candidate = if ([IO.Path]::IsPathRooted($Value)) {
        [IO.Path]::GetFullPath($Value)
    } else {
        [IO.Path]::GetFullPath((Join-Path $Root $Value))
    }
    $prefix = $Root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -and
        -not $candidate.Equals($Root, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path leaves workspace: $Value"
    }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate)) {
        throw "Path does not exist: $candidate"
    }
    return $candidate
}

if ($PluginId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') {
    throw 'PluginId must contain only letters, numbers, dots, underscores, or hyphens.'
}

$workspacePath = (Resolve-Path -LiteralPath $Workspace).Path
$modulePath = Resolve-WorkspacePath -Root $workspacePath -Value $Module -MustExist
$artifactPath = Resolve-WorkspacePath -Root $workspacePath -Value $Artifact
$deployPath = Resolve-WorkspacePath -Root $workspacePath -Value $DeployJar
$deployParent = Split-Path -Parent $deployPath
if (-not (Test-Path -LiteralPath $deployParent -PathType Container)) {
    throw "Deployment directory does not exist: $deployParent"
}

$mavenArgs = @('clean', 'package')
if ($SkipTests) { $mavenArgs += '-DskipTests' }
Push-Location $modulePath
try {
    $moduleWrapper = Join-Path $modulePath 'mvnw.cmd'
    $workspaceWrapper = Join-Path $workspacePath 'mvnw.cmd'
    if (Test-Path -LiteralPath $moduleWrapper) {
        & $moduleWrapper @mavenArgs
    } elseif (Test-Path -LiteralPath $workspaceWrapper) {
        & $workspaceWrapper @mavenArgs
    } else {
        & mvn @mavenArgs
    }
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
    throw "Build artifact was not created: $artifactPath"
}

$backupId = $null
$backupPath = $null
if (Test-Path -LiteralPath $deployPath -PathType Leaf) {
    $utc = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
    $entropy = [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $backupId = "$PluginId-$utc-$entropy"
    $pluginBackupRoot = Join-Path (Join-Path $workspacePath 'backup') $PluginId
    New-Item -ItemType Directory -Path $pluginBackupRoot -Force | Out-Null
    $backupPath = Join-Path $pluginBackupRoot ($backupId + [IO.Path]::GetExtension($deployPath))
    Copy-Item -LiteralPath $deployPath -Destination $backupPath
}

Copy-Item -LiteralPath $artifactPath -Destination $deployPath -Force
$artifactHash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash
$deployedHash = (Get-FileHash -LiteralPath $deployPath -Algorithm SHA256).Hash
if ($artifactHash -ne $deployedHash) {
    throw 'Deployed JAR hash does not match the build artifact.'
}

if ($backupPath -ne $null) {
    $record = [ordered]@{
        backup_id = $backupId
        created_utc = [DateTime]::UtcNow.ToString('o')
        plugin_id = $PluginId
        source_deploy_path = $deployPath
        backup_path = $backupPath
        backup_sha256 = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash
        artifact_path = $artifactPath
        artifact_sha256 = $artifactHash
    } | ConvertTo-Json -Compress
    Add-Content -LiteralPath (Join-Path (Split-Path -Parent $backupPath) 'index.jsonl') -Value $record -Encoding utf8
}

[pscustomobject]@{
    module = $Module
    pluginId = $PluginId
    artifact = $artifactPath
    deployed = $deployPath
    backupId = $backupId
    backup = $backupPath
    sha256 = $deployedHash
    serverStarted = $false
} | ConvertTo-Json

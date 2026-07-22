[CmdletBinding()]
param(
    [string] $Workspace = (Split-Path -Parent $PSScriptRoot),
    [switch] $SkipTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath $Workspace).Path
$deploy = Join-Path $root 'scripts\deploy-plugin.ps1'
$paperPlugins = Join-Path $root 'network_1_21\paper-rpg\plugins'
$sourcePlugins = Join-Path $root 'test_1_21\plugins'
New-Item -ItemType Directory -Path $paperPlugins -Force | Out-Null

$external = @(
    'FastAsyncWorldEdit-Paper-2.15.3-SNAPSHOT-1344.jar',
    'ItemsAdder_4.0.16.jar',
    'MMOInventory-2.0 28.jar',
    'MMOItems-6.10.1-20260216.173633-48(1).jar',
    'MythicCrucible-5.12.0.jar',
    'MythicLib-dist-1.7.1-20260217.194250-84(1).jar',
    'MythicMobs.jar',
    'ModelEngine-R4.0.9.jar',
    'PlaceholderAPI-2.12.2.jar',
    'LuckPerms-Bukkit-5.5.53.jar',
    'ProtocolLib.jar',
    'VaultUnlocked-2.20.1.jar',
    '[M]MMOCore-1.13.1-51.jar',
    'packetevents-spigot-2.12.2.jar'
)

foreach ($file in $external) {
    $source = Join-Path $sourcePlugins $file
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "RPG dependency missing: $source"
    }
    Copy-Item -LiteralPath $source -Destination (Join-Path $paperPlugins $file) -Force
}

$ks = @(
    @{ Id='ks-core'; Module='ks-core'; Artifact='ks-core\target\ks-core-1.1.0.jar'; Jar='ks-core-1.1.0.jar' },
    @{ Id='ks-InstanceWorld'; Module='ks-InstanceWorld'; Artifact='ks-InstanceWorld\target\ks-InstanceWorld-0.1.0.jar'; Jar='ks-InstanceWorld-0.1.0.jar' },
    @{ Id='ks-Eco'; Module='ks-Eco'; Artifact='ks-Eco\target\ks-Eco-1.1.0.jar'; Jar='ks-Eco-1.1.0.jar' },
    @{ Id='ks-RPG'; Module='ks-RPG'; Artifact='ks-RPG\target\ks-RPG-0.1.0.jar'; Jar='ks-RPG-0.1.0.jar' },
    @{ Id='ks-RPG-Gui'; Module='ks-RPG-Gui'; Artifact='ks-RPG-Gui\target\ks-RPG-Gui-0.1.0.jar'; Jar='ks-RPG-Gui-0.1.0.jar' },
    @{ Id='ks-BossCombat'; Module='ks-BossCombat'; Artifact='ks-BossCombat\target\ks-BossCombat-1.0.0.jar'; Jar='ks-BossCombat-1.0.0.jar' },
    @{ Id='ks-Cinematic'; Module='ks-Cinematic'; Artifact='ks-Cinematic\target\ks-Cinematic-0.1.0.jar'; Jar='ks-Cinematic-0.1.0.jar' }
)

foreach ($plugin in $ks) {
    $params = @{
        Workspace = $root
        Module = $plugin.Module
        Artifact = $plugin.Artifact
        DeployJar = "network_1_21\paper-rpg\plugins\$($plugin.Jar)"
        PluginId = $plugin.Id
    }
    if ($SkipTests) { $params.SkipTests = $true }
    & $deploy @params
    if ($LASTEXITCODE -ne 0) { throw "Deployment failed: $($plugin.Id)" }
}

$installed = Get-ChildItem -LiteralPath $paperPlugins -Filter '*.jar' -File | Sort-Object Name | ForEach-Object {
    [pscustomobject]@{ file=$_.Name; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
}
$installed | ConvertTo-Json

# Folia is a distinct artifact flavor. Only modules with an audited profile
# may be installed here; never copy the Paper artifact and rename it.
& $deploy -Workspace $root -Module 'ks-core' `
    -Artifact 'ks-core\target\ks-core-1.1.0-folia.jar' `
    -DeployJar 'network_1_21\folia-lab\plugins\ks-core-1.1.0-folia.jar' `
    -PluginId 'ks-core-folia' -MavenProfiles 'folia'
if ($LASTEXITCODE -ne 0) { throw 'Deployment failed: ks-core-folia' }

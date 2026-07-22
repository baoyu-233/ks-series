[CmdletBinding()]
param([string] $Workspace = (Split-Path -Parent $PSScriptRoot))

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath $Workspace).Path
$sourceRoot = Join-Path $root 'test_1_21\plugins'
$targetRoot = Join-Path $root 'network_1_21\paper-rpg\plugins'

$directories = [ordered]@{
    ItemsAdder = @('contents','lang','lib')
    MythicMobs = @('config','dialogs','droptables','generation','Items','menus','Mobs','packs','randomspawns','Skills','spawners','FallenDefender_GIRLPOWER_V1','samus2002_RPG_CLASS_BOSS_WARRIOR_FULL')
    ModelEngine = @('blueprints','resource pack','skins')
    MMOItems = @('crafting-stations','item','language','layouts','modifiers','plugindata','tooltips')
    MMOCore = @('attributes','classes','drop-tables','exp-curves','exp-tables','gui','loot-chests','professions','quests','skill-trees','waypoints')
    MMOInventory = @('inventory')
    MythicLib = @('script','skill')
    'ks-RPG' = @('content')
    'ks-RPG-Gui' = @()
    'ks-Cinematic' = @('stories')
}

$files = [ordered]@{
    ItemsAdder = @('config.yml','ia_gui.yml')
    MythicMobs = @('material-data.json','placeholders.yml')
    ModelEngine = @('config.yml')
    MMOItems = @('config.yml','custom-stats.yml','drops.yml','gen-templates.yml','item-sets.yml','item-tiers.yml','item-types.yml','upgrade-templates.yml')
    MMOCore = @('commands.yml','conditions.yml','config.yml','exp-sources.yml','guilds.yml','items.yml','messages.yml','restrictions.yml','stats.yml')
    MMOInventory = @('config.yml','language.yml')
    MythicLib = @('commands.yml','config.yml','elements.yml','indicators.yml','mitigation_types.yml','on_hit_effects.yml','stats.yml')
    'ks-RPG' = @('config.yml')
    'ks-RPG-Gui' = @('menu.yml')
    'ks-InstanceWorld' = @('config.yml')
}

foreach ($plugin in $directories.Keys) {
    foreach ($relative in $directories[$plugin]) {
        $source = Join-Path (Join-Path $sourceRoot $plugin) $relative
        if (-not (Test-Path -LiteralPath $source -PathType Container)) { continue }
        $target = Join-Path (Join-Path $targetRoot $plugin) $relative
        New-Item -ItemType Directory -Path $target -Force | Out-Null
        & robocopy $source $target /E /NFL /NDL /NJH /NJS /NP
        if ($LASTEXITCODE -ge 8) { throw "Content sync failed: $plugin/$relative" }
    }
}

foreach ($plugin in $files.Keys) {
    foreach ($relative in $files[$plugin]) {
        $source = Join-Path (Join-Path $sourceRoot $plugin) $relative
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
        $targetDir = Join-Path $targetRoot $plugin
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination (Join-Path $targetDir $relative) -Force
    }
}

[pscustomobject]@{
    source = $sourceRoot
    target = $targetRoot
    note = 'RPG content and static configuration only; no userdata, databases, logs, secrets, or caches.'
} | ConvertTo-Json

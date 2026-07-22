[CmdletBinding()]
param(
    [string] $Workspace = (Split-Path -Parent $PSScriptRoot)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-HttpCheck {
    param(
        [string] $Name,
        [string] $Uri,
        [string] $Method = 'GET',
        [hashtable] $Headers = @{}
    )
    try {
        $response = Invoke-WebRequest -Uri $Uri -Method $Method -Headers $Headers -UseBasicParsing -TimeoutSec 30
        return [pscustomobject]@{ name = $Name; status = [int] $response.StatusCode; body = $response.Content }
    } catch {
        $status = if ($_.Exception.Response) { [int] $_.Exception.Response.StatusCode } else { 0 }
        return [pscustomobject]@{ name = $Name; status = $status; body = $_.Exception.Message }
    }
}

function Invoke-EcoSuite {
    param([string] $Node, [int] $Port)

    $base = "http://127.0.0.1:$Port/ks-Eco"
    $checks = [System.Collections.Generic.List[object]]::new()
    $checks.Add((Invoke-HttpCheck -Name 'unauth-admin-settings' -Uri "$base/api/admin/federated-assets/settings"))
    $tokenResponse = Invoke-HttpCheck -Name 'test-token' -Uri "$base/api/test-token" -Method POST
    $checks.Add($tokenResponse)
    if ($tokenResponse.status -ne 200) {
        return [pscustomobject]@{ node = $Node; port = $Port; checks = $checks; metrics = @{} }
    }

    $token = ($tokenResponse.body | ConvertFrom-Json).token
    $headers = @{ Authorization = "Bearer $token" }
    $paths = [ordered]@{
        'market-stats' = '/api/market/stats'
        'macro-data' = '/api/macro-data'
        'listings' = '/api/listings'
        'bootstrap' = '/api/eco/bootstrap'
        'extra-modules' = '/api/extra-modules'
        'bank-stats' = '/api/bank/stats'
        'bank-list' = '/api/bank/list'
        'bank-dashboard' = '/api/bank/gameplay/dashboard'
        'bank-deposit-products' = '/api/bank/deposit-products'
        'bank-loan-products' = '/api/bank/loan/products'
        'bank-policy-events' = '/api/bank/policy-events'
        'bank-rates' = '/api/bank/cb/rates'
        'bank-guidance' = '/api/bank/guidance'
        'collateral-auctions' = '/api/bank/collateral-auctions'
        'enterprise-stats' = '/api/enterprise/stats'
        'enterprise-list' = '/api/enterprise/list'
        'enterprise-projects' = '/api/enterprise/projects'
        'enterprise-procurements' = '/api/enterprise/procurements'
        'tax-stats' = '/api/tax/stats'
        'blindbox-pools' = '/api/blindbox/pools'
        'realestate-zones' = '/api/realestate/zones'
        'realestate-houses' = '/api/realestate/houses-for-sale'
        'settlement-review' = '/api/admin/settlements/review'
        'federated-settings' = '/api/admin/federated-assets/settings'
        'map-sources' = '/api/federated/snapshot-sources?kind=MAP&includeStale=true&includeOffline=true'
        'property-sources' = '/api/federated/snapshot-sources?kind=PROPERTY&includeStale=true&includeOffline=true'
        'property-aggregate' = '/api/federated/assets/aggregate?assetType=PROPERTY&includeStale=true&includeOffline=true'
    }
    foreach ($entry in $paths.GetEnumerator()) {
        $checks.Add((Invoke-HttpCheck -Name $entry.Key -Uri ($base + $entry.Value) -Headers $headers))
    }

    $metrics = [ordered]@{}
    $extra = $checks | Where-Object name -eq 'extra-modules' | Select-Object -First 1
    if ($extra.status -eq 200) { $metrics.extraModules = [int] (($extra.body | ConvertFrom-Json).count) }
    $settings = $checks | Where-Object name -eq 'federated-settings' | Select-Object -First 1
    if ($settings.status -eq 200) {
        $status = ($settings.body | ConvertFrom-Json).status
        $metrics.federatedEnabled = [bool] $status.enabled
        $metrics.federatedReady = [bool] $status.ready
        $metrics.nodeId = [string] $status.nodeId
    }
    $property = $checks | Where-Object name -eq 'property-aggregate' | Select-Object -First 1
    if ($property.status -eq 200) {
        $aggregate = ($property.body | ConvertFrom-Json).aggregate
        $metrics.propertyAssets = [int] $aggregate.distinctAssets
        $metrics.propertyQuantity = [long] $aggregate.quantity
        $metrics.propertyValueMinor = [long] $aggregate.valueMinor
    }
    $metrics.httpPass = @($checks | Where-Object { $_.status -eq 200 }).Count
    $metrics.httpExpectedUnauthorized = @($checks | Where-Object { $_.name -eq 'unauth-admin-settings' -and $_.status -eq 401 }).Count
    $metrics.httpTotal = $checks.Count
    $checkSummary = @($checks | ForEach-Object { [pscustomobject]@{ name = $_.name; status = $_.status } })
    return [pscustomobject]@{ node = $Node; port = $Port; checks = $checkSummary; metrics = $metrics }
}

$leavesBase = 'http://127.0.0.1:8123'
$token = ((Invoke-RestMethod -Uri "$leavesBase/ks-Eco/api/test-token" -Method POST -TimeoutSec 15).token)
$headers = @{ Authorization = "Bearer $token" }
$zones = Invoke-RestMethod -Uri "$leavesBase/ks-Eco/api/realestate/zones" -Headers $headers -TimeoutSec 30
$zoneList = @($zones.zones)
$city = $null
if ($zoneList.Count -gt 0) {
    $zoneId = [Uri]::EscapeDataString([string] $zoneList[0].id)
    $city = Invoke-RestMethod -Uri "$leavesBase/ks-Eco/api/realestate/city/manifest?zoneId=$zoneId" -Headers $headers -TimeoutSec 30
}

# 两个相邻图块必须保留在同一跨服 MAP bundle 中。
$null = Invoke-RestMethod -Uri "$leavesBase/kSHWP/api/tile?world=test_world&x=0&z=0&zoom=1" -TimeoutSec 60
Start-Sleep -Milliseconds 500
$null = Invoke-RestMethod -Uri "$leavesBase/kSHWP/api/tile?world=test_world&x=1&z=0&zoom=1" -TimeoutSec 60
Start-Sleep -Milliseconds 500
$null = Invoke-RestMethod -Uri "$leavesBase/kSHWP/api/tile?world=test_world&x=2&z=0&zoom=1" -TimeoutSec 60
Start-Sleep -Seconds 8

$leaves = Invoke-EcoSuite -Node 'survival' -Port 8123
$folia = Invoke-EcoSuite -Node 'folia' -Port 58578

$mapSources = Invoke-RestMethod -Uri "$leavesBase/ks-Eco/api/federated/snapshot-sources?kind=MAP&includeStale=true&includeOffline=true" -Headers $headers -TimeoutSec 30
$mapSource = @($mapSources.sources)[0].source
$mapUri = "$leavesBase/ks-Eco/api/federated/snapshot?kind=MAP&server=$([Uri]::EscapeDataString($mapSource.nodeId))&world=$([Uri]::EscapeDataString($mapSource.worldId))&dimension=$([Uri]::EscapeDataString($mapSource.dimensionId))&includeStale=true&includeOffline=true"
$mapSnapshot = Invoke-RestMethod -Uri $mapUri -Headers $headers -TimeoutSec 30

$control = [IO.File]::ReadAllText((Join-Path $Workspace 'server-control.ps1'))
$apiKey = [regex]::Match($control, '\$McsmApiKey = "([^"]+)"').Groups[1].Value
$manifest = Get-Content -Raw (Join-Path $Workspace 'network_1_21/mcsm-instances.json') | ConvertFrom-Json
$mcsmHeaders = @{ 'X-Request-API-Key' = $apiKey }
$commands = [System.Collections.Generic.List[object]]::new()
foreach ($instance in ($manifest.instances | Where-Object { $_.role -in @('survival-main-web-gateway', 'rpg', 'ks-only-folia-lab') })) {
    foreach ($command in @('kseco-admin status', 'kseco-admin reload', 'list')) {
        $uri = "http://localhost:23333/api/protected_instance/command?uuid=$($instance.uuid)&daemonId=$($manifest.daemonId)&command=$([Uri]::EscapeDataString($command))"
        try {
            $null = Invoke-RestMethod -Uri $uri -Headers $mcsmHeaders -TimeoutSec 15
            $commands.Add([pscustomobject]@{ instance = $instance.name; command = $command; sent = $true })
        } catch {
            $commands.Add([pscustomobject]@{ instance = $instance.name; command = $command; sent = $false })
        }
    }
}

$ports = [ordered]@{}
foreach ($port in @(23333, 24444, 3307, 25577, 25565, 25571, 25573, 8123, 58578)) {
    $ports[[string] $port] = [bool] (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
}

[pscustomobject]@{
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    leaves = $leaves
    folia = $folia
    city = [pscustomobject]@{
        zones = $zoneList.Count
        buildings = if ($null -eq $city) { 0 } else { @($city.buildings).Count }
        plots = if ($null -eq $city) { 0 } else { @($city.plots).Count }
        renderMode = if ($null -eq $city) { $null } else { $city.renderMode }
        async = if ($null -eq $city) { $false } else { [bool] $city.async }
        preRendered = if ($null -eq $city) { $false } else { [bool] $city.preRendered }
    }
    map = [pscustomobject]@{
        source = "$($mapSource.nodeId)/$($mapSource.worldId)/$($mapSource.dimensionId)"
        schema = $mapSnapshot.payload.schema
        tiles = @($mapSnapshot.payload.tiles).Count
        stale = [bool] $mapSnapshot.stale
        offline = [bool] $mapSnapshot.offline
    }
    commands = $commands
    ports = $ports
} | ConvertTo-Json -Depth 8

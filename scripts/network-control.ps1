[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Start','Stop','Status')]
    [string] $Action,

    [ValidateSet('proxy','paper-rpg','leaves-survival','folia-lab','all')]
    [string] $Node = 'all',

    [string] $Workspace = (Split-Path -Parent $PSScriptRoot)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workspacePath = (Resolve-Path -LiteralPath $Workspace).Path
$networkRoot = Join-Path $workspacePath 'network_1_21'
$runRoot = Join-Path $networkRoot 'run'
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null

$nodes = [ordered]@{
    'paper-rpg' = @{ Dir='paper-rpg'; Jar='server.jar'; Port=25571; Xms='1G'; Xmx='3G'; Type='backend'; RconPort=25581; RconPassword='ks-network-local-rpg-2026' }
    'leaves-survival' = @{ Dir='leaves-survival'; Jar='server.jar'; Port=25572; Xms='1G'; Xmx='3G'; Type='backend'; RconPort=25582; RconPassword='ks-network-local-survival-2026' }
    'folia-lab' = @{ Dir='folia-lab'; Jar='server.jar'; Port=25573; Xms='1G'; Xmx='3G'; Type='backend'; RconPort=25583; RconPassword='ks-network-local-folia-2026' }
    'proxy' = @{ Dir='proxy'; Jar='BungeeCord.jar'; Port=25577; Xms='256M'; Xmx='512M'; Type='proxy'; RconPort=0; RconPassword='' }
}

function Get-SelectedNodes {
    if ($Node -eq 'all') { return @($nodes.Keys) }
    return @($Node)
}

function Get-PidPath([string] $Name) { return Join-Path $runRoot "$Name.pid" }

function Get-NodeProcess([string] $Name) {
    $pidPath = Get-PidPath $Name
    if (-not (Test-Path -LiteralPath $pidPath -PathType Leaf)) { return $null }
    $raw = [IO.File]::ReadAllText($pidPath).Trim()
    if ($raw -notmatch '^\d+$') { return $null }
    Get-Process -Id ([int]$raw) -ErrorAction SilentlyContinue
}

function Test-Port([int] $Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return $task.Wait(500) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Invoke-RconPacket {
    param([IO.Stream] $Stream, [int] $RequestId, [int] $Type, [string] $Payload)
    $payloadBytes = [Text.Encoding]::UTF8.GetBytes($Payload)
    $length = 4 + 4 + $payloadBytes.Length + 2
    $buffer = [byte[]]::new(4 + $length)
    [BitConverter]::GetBytes([int]$length).CopyTo($buffer, 0)
    [BitConverter]::GetBytes([int]$RequestId).CopyTo($buffer, 4)
    [BitConverter]::GetBytes([int]$Type).CopyTo($buffer, 8)
    $payloadBytes.CopyTo($buffer, 12)
    $Stream.Write($buffer, 0, $buffer.Length)
    $Stream.Flush()

    $reader = [IO.BinaryReader]::new($Stream, [Text.Encoding]::UTF8, $true)
    $responseLength = $reader.ReadInt32()
    $responseId = $reader.ReadInt32()
    $responseType = $reader.ReadInt32()
    $bodyLength = $responseLength - 10
    $body = if ($bodyLength -gt 0) { [Text.Encoding]::UTF8.GetString($reader.ReadBytes($bodyLength)) } else { '' }
    [void]$reader.ReadByte()
    [void]$reader.ReadByte()
    [pscustomobject]@{ Id=$responseId; Type=$responseType; Body=$body }
}

function Stop-BackendGracefully([hashtable] $Config) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $client.Connect('127.0.0.1', [int]$Config.RconPort)
        $stream = $client.GetStream()
        $auth = Invoke-RconPacket -Stream $stream -RequestId 41 -Type 3 -Payload $Config.RconPassword
        if ($auth.Id -eq -1) { throw 'RCON authentication failed' }
        [void](Invoke-RconPacket -Stream $stream -RequestId 42 -Type 2 -Payload 'stop')
    } finally {
        $client.Dispose()
    }
}

function Start-Node([string] $Name) {
    $config = $nodes[$Name]
    $existing = Get-NodeProcess $Name
    if ($null -ne $existing) {
        Write-Output "$Name already running (PID $($existing.Id))"
        return
    }
    $dir = Join-Path $networkRoot $config.Dir
    $jar = Join-Path $dir $config.Jar
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "$Name JAR missing; run scripts/network-bootstrap.ps1 first"
    }
    $stdout = Join-Path $runRoot "$Name.stdout.log"
    $stderr = Join-Path $runRoot "$Name.stderr.log"
    $arguments = @("-Xms$($config.Xms)", "-Xmx$($config.Xmx)", '-jar', $config.Jar)
    if ($config.Type -eq 'backend') { $arguments += '--nogui' }
    $process = Start-Process -FilePath 'java' -ArgumentList $arguments -WorkingDirectory $dir `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    [IO.File]::WriteAllText((Get-PidPath $Name), [string]$process.Id)
    Write-Output "$Name started (PID $($process.Id), port $($config.Port))"
}

function Stop-Node([string] $Name) {
    $config = $nodes[$Name]
    $process = Get-NodeProcess $Name
    if ($null -eq $process) {
        Remove-Item -LiteralPath (Get-PidPath $Name) -Force -ErrorAction SilentlyContinue
        Write-Output "$Name is not running"
        return
    }
    if ($config.Type -eq 'backend' -and (Test-Port ([int]$config.RconPort))) {
        try { Stop-BackendGracefully $config } catch { Write-Warning "$Name graceful stop failed: $_" }
        try { Wait-Process -Id $process.Id -Timeout 45 -ErrorAction Stop } catch {}
    }
    $process = Get-Process -Id $process.Id -ErrorAction SilentlyContinue
    if ($null -ne $process) { Stop-Process -Id $process.Id -Force }
    Remove-Item -LiteralPath (Get-PidPath $Name) -Force -ErrorAction SilentlyContinue
    Write-Output "$Name stopped"
}

function Show-Status([string] $Name) {
    $config = $nodes[$Name]
    $process = Get-NodeProcess $Name
    [pscustomobject]@{
        Node=$Name
        Process=if($null -ne $process){'RUNNING'}else{'STOPPED'}
        Pid=if($null -ne $process){$process.Id}else{$null}
        Port=$config.Port
        Listening=(Test-Port ([int]$config.Port))
    }
}

$selected = Get-SelectedNodes
switch ($Action) {
    'Start' { foreach ($name in $selected) { Start-Node $name } }
    'Stop' {
        $stopOrder = if ($Node -eq 'all') { @('proxy','folia-lab','leaves-survival','paper-rpg') } else { $selected }
        foreach ($name in $stopOrder) { Stop-Node $name }
    }
    'Status' { $selected | ForEach-Object { Show-Status $_ } | Format-Table -AutoSize }
}

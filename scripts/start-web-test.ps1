[CmdletBinding()]
param([int] $Port = 8788)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot
$webRoot = Join-Path $workspace "ks-Eco\src\main\resources\web"
while (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { $Port++ }
$process = Start-Process -FilePath python -ArgumentList "-m", "http.server", $Port, "--bind", "127.0.0.1" `
    -WorkingDirectory $webRoot -WindowStyle Hidden -PassThru
[pscustomobject]@{
    Url = "http://127.0.0.1:$Port/test.html"
    ProcessId = $process.Id
    WebRoot = $webRoot
}

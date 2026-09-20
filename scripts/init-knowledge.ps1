param(
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $projectRoot '.env'
$frontendPort = 8080

if (Test-Path $envFile) {
    $portLine = Get-Content $envFile |
        Where-Object { $_ -match '^\s*FRONTEND_PORT\s*=' } |
        Select-Object -Last 1
    if ($portLine) {
        $configuredPort = ($portLine -split '=', 2)[1].Trim().Trim('"').Trim("'")
        if ($configuredPort -match '^\d+$') {
            $frontendPort = [int]$configuredPort
        }
    }
}

$baseUrl = "http://localhost:$frontendPort"
$healthUrl = "$baseUrl/api/health"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$ready = $false

Write-Host "Waiting for backend: $healthUrl"
do {
    try {
        $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
        if ($health -eq 'ok') {
            $ready = $true
            break
        }
    } catch {
        # Connection failures are expected while the backend is starting.
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

if (-not $ready) {
    throw 'Backend startup timed out. Run: docker compose logs backend'
}

Write-Host 'Initializing knowledge base (this calls the DashScope embedding API)...'
$result = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/knowledge/init" -TimeoutSec 900
if ([string]$result -match '\u5931\u8d25|fail|error') {
    throw "Knowledge initialization failed: $result"
}

Write-Host $result
Write-Host "Done. Open: $baseUrl"

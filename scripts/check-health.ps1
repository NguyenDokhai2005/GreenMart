param(
    [switch]$CheckBrokerFailure,
    [string]$BaseUrl = 'http://localhost:8080'
)
$ErrorActionPreference = 'Stop'
function Assert-Health([string]$Path, [int]$ExpectedCode, [string]$ExpectedStatus) {
    $response = Invoke-WebRequest -Uri "$BaseUrl$Path" -SkipHttpErrorCheck
    if ([int]$response.StatusCode -ne $ExpectedCode) {
        throw "$Path expected HTTP $ExpectedCode, got $($response.StatusCode)"
    }
    $body = if ($response.Content -is [byte[]]) {
        [Text.Encoding]::UTF8.GetString($response.Content)
    } else { [string]$response.Content }
    if ($ExpectedStatus -and (($body | ConvertFrom-Json).status -ne $ExpectedStatus)) {
        throw "$Path expected status $ExpectedStatus"
    }
    Write-Host "$Path : HTTP $ExpectedCode $ExpectedStatus"
}
Assert-Health '/actuator/health' 200 'UP'
Assert-Health '/actuator/health/liveness' 200 'UP'
Assert-Health '/actuator/health/readiness' 200 'UP'
Assert-Health '/actuator/env' 404 ''
if ($CheckBrokerFailure) {
    # Explicit opt-in: this temporarily stops RabbitMQ in the current Compose project.
    try {
        docker compose stop rabbitmq
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop RabbitMQ' }
        Assert-Health '/actuator/health' 503 'DOWN'
        Assert-Health '/actuator/health/readiness' 503 'DOWN'
        Assert-Health '/actuator/health/liveness' 200 'UP'
    } finally {
        docker compose start rabbitmq
        if ($LASTEXITCODE -ne 0) { throw 'Could not restart RabbitMQ' }
    }
    $recovered = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $response = Invoke-WebRequest "$BaseUrl/actuator/health" -SkipHttpErrorCheck
            if ($response.StatusCode -eq 200) { $recovered = $true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $recovered) { throw 'Health did not recover after RabbitMQ restart' }
    Assert-Health '/actuator/health' 200 'UP'
}

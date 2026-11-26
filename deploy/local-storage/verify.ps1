<#
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
#>

[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')]
    [string] $RunId = ("storage-" + (Get-Date -Format 'yyyyMMddHHmmss')),
    [ValidateRange(30, 600)]
    [int] $TimeoutSeconds = 180,
    [switch] $SkipBuild,
    [switch] $Shutdown
)

$ErrorActionPreference = 'Stop'
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryDirectory = (Resolve-Path (Join-Path $scriptDirectory '..\..')).Path
$reportPath = Join-Path $scriptDirectory ("verification-report-{0}.json" -f $RunId)
$serviceName = "sdk-consumer-$RunId"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$startedAt = (Get-Date).ToUniversalTime().ToString('o')
$oldProtocol = $env:OTEL_EXPORTER_OTLP_PROTOCOL
$oldEndpoint = $env:OTEL_EXPORTER_OTLP_ENDPOINT
$script:dockerInWsl = $false
$script:dockerWslAsRoot = $false
$result = [ordered]@{
    runId = $RunId
    serviceName = $serviceName
    startedAtUtc = $startedAt
    completedAtUtc = $null
    metricStored = $false
    exemplarStored = $false
    logStored = $false
    traceStored = $false
    status = 'running'
    error = $null
}

function Test-CommandAvailable {
    param([Parameter(Mandatory = $true)][string] $Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found on PATH."
    }
}

function Initialize-Docker {
    if (Get-Command docker -ErrorAction SilentlyContinue) {
        return
    }
    if (Get-Command wsl.exe -ErrorAction SilentlyContinue) {
        & wsl.exe docker version *> $null
        if ($LASTEXITCODE -eq 0) {
            $script:dockerInWsl = $true
            return
        }
        & wsl.exe -u root -- docker version *> $null
        if ($LASTEXITCODE -eq 0) {
            $script:dockerInWsl = $true
            $script:dockerWslAsRoot = $true
            return
        }
    }
    throw "Docker was not found on the Windows PATH or in the default WSL distribution."
}

function Invoke-DockerCompose {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    if ($script:dockerInWsl) {
        if ($script:dockerWslAsRoot) {
            & wsl.exe -u root --cd $scriptDirectory -- docker compose @Arguments
        } else {
            & wsl.exe --cd $scriptDirectory -- docker compose @Arguments
        }
    } else {
        & docker compose @Arguments
    }
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed."
    }
}

function Wait-HttpEndpoint {
    param([Parameter(Mandatory = $true)][string] $Uri)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "Timed out waiting for $Uri."
}

function Wait-Until {
    param(
        [Parameter(Mandatory = $true)][string] $Description,
        [Parameter(Mandatory = $true)][scriptblock] $Condition
    )
    while ((Get-Date) -lt $deadline) {
        try {
            if (& $Condition) { return }
        } catch {
            # Storage services may still be creating indexes or registering services.
        }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for $Description."
}

function Restore-OtlpEnvironment {
    if ($null -eq $oldProtocol) { Remove-Item Env:OTEL_EXPORTER_OTLP_PROTOCOL -ErrorAction SilentlyContinue } else { $env:OTEL_EXPORTER_OTLP_PROTOCOL = $oldProtocol }
    if ($null -eq $oldEndpoint) { Remove-Item Env:OTEL_EXPORTER_OTLP_ENDPOINT -ErrorAction SilentlyContinue } else { $env:OTEL_EXPORTER_OTLP_ENDPOINT = $oldEndpoint }
}

try {
    Initialize-Docker
    Test-CommandAvailable mvn
    Test-CommandAvailable java

    Push-Location $scriptDirectory
    try {
        Invoke-DockerCompose -Arguments @('up', '-d') | Write-Host
    } finally {
        Pop-Location
    }

    Wait-HttpEndpoint 'http://127.0.0.1:19090/-/ready'
    Wait-HttpEndpoint 'http://127.0.0.1:19200'
    Wait-HttpEndpoint 'http://127.0.0.1:16686'

    if (-not $SkipBuild) {
        Push-Location $repositoryDirectory
        try {
            mvn -B -ntp -DskipTests install
            if ($LASTEXITCODE -ne 0) { throw 'SDK artifact installation failed.' }
        } finally {
            Pop-Location
        }
    }

    $env:OTEL_EXPORTER_OTLP_PROTOCOL = 'http/protobuf'
    $env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://127.0.0.1:14318'
    Push-Location (Join-Path $repositoryDirectory 'consumer-smoke')
    try {
        mvn -B -ntp package exec:java '-Dexec.mainClass=net.openan.observability.consumer.ConsumerSmoke' ("-Dexec.args=$RunId") '-Dexec.cleanupDaemonThreads=false'
        if ($LASTEXITCODE -ne 0) { throw 'Consumer smoke application failed.' }
    } finally {
        Pop-Location
    }

    $metricQuery = [uri]::EscapeDataString(('gen_ai_agent_a2at_task_count_total{service_name="{0}"}' -f $serviceName))
    Wait-Until 'metric in Prometheus' {
        $response = Invoke-RestMethod "http://127.0.0.1:19090/api/v1/query?query=$metricQuery"
        $response.status -eq 'success' -and @($response.data.result).Count -gt 0
    }
    $result.metricStored = $true

    $exemplarQuery = [uri]::EscapeDataString(('{service_name="{0}"}' -f $serviceName))
    $exemplarEnd = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $exemplarStart = $exemplarEnd - 300
    Wait-Until 'metric exemplar in Prometheus' {
        $response = Invoke-RestMethod "http://127.0.0.1:19090/api/v1/query_exemplars?query=$exemplarQuery&start=$exemplarStart&end=$exemplarEnd"
        $response.status -eq 'success' -and @($response.data | ForEach-Object { $_.exemplars } | Where-Object { $_ }).Count -gt 0
    }
    $result.exemplarStored = $true

    $logRequest = @{ size = 1; query = @{ match_phrase = @{ 'resource.attributes.service.name' = $serviceName } } } | ConvertTo-Json -Depth 8 -Compress
    Wait-Until 'log in OpenSearch' {
        $response = Invoke-RestMethod -Method Post -ContentType 'application/json' -Body $logRequest -Uri 'http://127.0.0.1:19200/a2at-logs-v2/_search'
        [int]$response.hits.total.value -gt 0
    }
    $result.logStored = $true

    $serviceQuery = [uri]::EscapeDataString($serviceName)
    Wait-Until 'trace service in Jaeger' {
        $services = Invoke-RestMethod "http://127.0.0.1:16686/api/services?service=$serviceQuery"
        @($services.data) -contains $serviceName
    }
    Wait-Until 'trace in Jaeger' {
        $traces = Invoke-RestMethod "http://127.0.0.1:16686/api/traces?service=$serviceQuery&limit=1"
        @($traces.data).Count -gt 0
    }
    $result.traceStored = $true
    $result.status = 'passed'
} catch {
    $result.status = 'failed'
    $result.error = $_.Exception.Message
    throw
} finally {
    Restore-OtlpEnvironment
    $result.completedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    $result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $reportPath -Encoding utf8
    Write-Host "Verification report: $reportPath"
    if ($Shutdown) {
        Push-Location $scriptDirectory
        try { Invoke-DockerCompose -Arguments @('down') | Write-Host } finally { Pop-Location }
    }
}

[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int] $OtlpPort = $(if ($env:OBS_OTLP_PORT) { $env:OBS_OTLP_PORT } else { 4318 }),

    [string[]] $ComposeProject = @('vitaloom-backend', 'vitaloom-observability'),

    [switch] $Once
)

$ErrorActionPreference = 'Stop'
$invariant = [Globalization.CultureInfo]::InvariantCulture
$byteUnits = @{
    B = 1; kB = 1000; MB = 1000000; GB = 1000000000; TB = 1000000000000; PB = 1000000000000000
    KiB = 1KB; MiB = 1MB; GiB = 1GB; TiB = 1TB; PiB = 1PB
}

function ConvertFrom-DockerSize([string] $Size) {
    if ($Size -notmatch '^\s*(\d+(?:\.\d+)?)\s*([A-Za-z]+)\s*$' -or !$byteUnits.ContainsKey($Matches[2])) {
        throw "Unexpected Docker size: $Size"
    }
    [long] ([double]::Parse($Matches[1], $invariant) * $byteUnits[$Matches[2]])
}

$metricDefinitions = @(
    @{ name = 'vitaloom.docker.container.cpu.utilization'; unit = ''; description = 'CPU use as a fraction of one logical CPU.' }
    @{ name = 'vitaloom.docker.container.memory.usage'; unit = 'By'; description = 'Memory usage reported by Docker stats.' }
    @{ name = 'vitaloom.docker.container.network.receive'; unit = 'By'; description = 'Total network bytes received.' }
    @{ name = 'vitaloom.docker.container.network.transmit'; unit = 'By'; description = 'Total network bytes sent.' }
)
$otlpUrl = "http://127.0.0.1:$OtlpPort/v1/metrics"
$docker = (Get-Command docker.exe -CommandType Application | Select-Object -First 1).Source

do {
    $cycle = [Diagnostics.Stopwatch]::StartNew()
    $projectsById = @{}
    foreach ($project in $ComposeProject) {
        $ids = & $docker ps --filter "label=com.docker.compose.project=$project" --format '{{.ID}}'
        if ($LASTEXITCODE -ne 0) { throw "Could not list Docker containers in $project." }
        foreach ($id in $ids) { $projectsById[$id] = $project }
    }

    if ($projectsById.Count -gt 0) {
        $ids = @($projectsById.Keys)
        $rows = & $docker stats --no-stream --format '{{json .}}' @ids
        if ($LASTEXITCODE -ne 0) { throw 'Could not read Docker container stats.' }

        $time = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000000).ToString($invariant)
        $dataPoints = @(0..3 | ForEach-Object { ,(New-Object 'System.Collections.Generic.List[object]') })

        foreach ($row in $rows) {
            $stat = $row | ConvertFrom-Json
            if ($stat.CPUPerc -eq '--' -or $stat.MemUsage -eq '--' -or $stat.NetIO -eq '--') { continue }

            $network = $stat.NetIO -split '/', 2
            $values = @(
                ([double]::Parse($stat.CPUPerc.TrimEnd('%'), $invariant) / 100)
                (ConvertFrom-DockerSize (($stat.MemUsage -split '/', 2)[0]))
                (ConvertFrom-DockerSize $network[0])
                (ConvertFrom-DockerSize $network[1])
            )
            $attributes = @(
                @{ key = 'container.name'; value = @{ stringValue = $stat.Name } }
                @{ key = 'container.id'; value = @{ stringValue = $stat.ID } }
                @{ key = 'compose.project'; value = @{ stringValue = $projectsById[$stat.ID] } }
            )
            for ($i = 0; $i -lt $metricDefinitions.Count; $i++) {
                $dataPoints[$i].Add(@{ attributes = $attributes; timeUnixNano = $time; asDouble = $values[$i] })
            }
        }

        if ($dataPoints[0].Count -gt 0) {
            $metrics = for ($i = 0; $i -lt $metricDefinitions.Count; $i++) {
                $metric = $metricDefinitions[$i].Clone()
                if ($i -lt 2) {
                    $metric.gauge = @{ dataPoints = $dataPoints[$i].ToArray() }
                } else {
                    $metric.sum = @{
                        dataPoints = $dataPoints[$i].ToArray()
                        aggregationTemporality = 2
                        isMonotonic = $true
                    }
                }
                $metric
            }
            $payload = @{
                resourceMetrics = @(@{
                    scopeMetrics = @(@{
                        scope = @{ name = 'vitaloom.docker-stats' }
                        metrics = @($metrics)
                    })
                })
            }
            $response = Invoke-RestMethod -Method Post -Uri $otlpUrl -ContentType 'application/json' `
                -Body ($payload | ConvertTo-Json -Depth 15 -Compress) -TimeoutSec 5 -UseBasicParsing
            if ([long]$response.partialSuccess.rejectedDataPoints -gt 0) {
                throw "Collector rejected Docker metrics: $($response.partialSuccess.errorMessage)"
            }
            Write-Verbose "Sent Docker metrics for $($dataPoints[0].Count) containers."
        }
    }

    if (!$Once) {
        Start-Sleep -Milliseconds ([Math]::Max(0, 15000 - [int]$cycle.ElapsedMilliseconds))
    }
} until ($Once)

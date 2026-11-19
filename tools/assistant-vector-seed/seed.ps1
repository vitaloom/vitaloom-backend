[CmdletBinding()]
param(
    [string]$ComposeFile = (Join-Path $PSScriptRoot '../../docker-compose.assistant-vector.yaml'),
    [string]$Project = 'vitaloom-vector',
    [string]$OverrideFile,
    [ValidateRange(1, 1800)]
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$compose = @('compose', '-p', $Project, '-f', (Resolve-Path -LiteralPath $ComposeFile).Path)
if ($OverrideFile) {
    $compose += @('-f', (Resolve-Path -LiteralPath $OverrideFile).Path)
}

function Invoke-Compose {
    param([string[]]$Arguments)
    docker @compose @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose command failed (exit ${LASTEXITCODE}): $($Arguments -join ' ')"
    }
}

$service = 'assistant-vector'
$database = 'assistant_vector'
$stageDir = "/tmp/vitaloom-seed-$([guid]::NewGuid().ToString('N'))"
$indexFile = Join-Path $PSScriptRoot 'indexes.json'
$specs = Get-Content -Raw -LiteralPath $indexFile | ConvertFrom-Json
Invoke-Compose @('exec', '-T', $service, 'mkdir', $stageDir)
try {
    foreach ($path in @($indexFile, (Join-Path $PSScriptRoot 'seed.js'))) {
        Invoke-Compose @('cp', $path, "${service}:${stageDir}/")
    }

    $mongoShell = @($service, 'mongosh', $database, '--quiet', '--file', "$stageDir/seed.js")
    $restorePlan = Invoke-Compose (@('exec', '-T', '-e', "SEED_DIRECTORY=$stageDir", '-e', 'SEED_PHASE=preflight') + $mongoShell)
    $missing = $restorePlan | ConvertFrom-Json
    foreach ($collection in $missing) {
        $null = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "$collection.archive.gz")
    }

    foreach ($spec in $specs) {
        if ($spec.collection -notin $missing) {
            Write-Output "Already matches snapshot: $($spec.collection)"
            continue
        }
        $archiveName = "$($spec.collection).archive.gz"
        $archive = "$stageDir/$archiveName"
        $localArchive = Join-Path $PSScriptRoot $archiveName
        Invoke-Compose @('cp', $localArchive, "${service}:${stageDir}/")
        Invoke-Compose @('exec', '-T', $service, 'mongorestore', '--gzip', "--archive=$archive",
            "--nsInclude=$database.$($spec.collection)", '--stopOnError')
    }

    Invoke-Compose (@('exec', '-T', '-e', "SEED_DIRECTORY=$stageDir", '-e', 'SEED_PHASE=verify',
        '-e', "SEED_TIMEOUT_SECONDS=$TimeoutSeconds") + $mongoShell)
} finally {
    docker @compose exec -T $service rm -rf -- $stageDir
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Could not remove $stageDir from $service."
    }
}

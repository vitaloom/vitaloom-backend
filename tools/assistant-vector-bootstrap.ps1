param(
    [ValidateSet('gemini-embedding-2', 'gemini-embedding-001')]
    [string]$EmbeddingModel = 'gemini-embedding-2',
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$backend = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $backend 'target/vitaloom-backend-0.0.1-SNAPSHOT.jar'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java.exe' }
$app = $null
$vectorCompose = @('compose', '-p', 'vitaloom-vector', '-f', 'docker-compose.assistant-vector.yaml')

Push-Location $backend
try {
    docker network inspect vitaloom-assistant-vector --format '{{.Name}}' 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        docker network create vitaloom-assistant-vector | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Vector network creation failed.' }
    }
    docker @vectorCompose up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Vector Compose failed to start.' }
    docker compose up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Compose failed to start.' }
    $binding = docker @vectorCompose port assistant-vector 27017
    if ($LASTEXITCODE -ne 0 -or $binding -notmatch '^127\.0\.0\.1:(\d+)$') {
        throw 'Expected a loopback port for assistant-vector.'
    }
    $vectorUri = "mongodb://127.0.0.1:$($Matches[1])/assistant_vector?directConnection=true&serverSelectionTimeoutMS=2000"
    docker @vectorCompose exec -T assistant-vector mongosh assistant_vector --quiet --file /opt/vitaloom/assistant-vector-index.js
    if ($LASTEXITCODE -ne 0) { throw 'Vector index provisioning failed.' }

    if (!(Test-Path -LiteralPath $jar)) {
        .\mvnw.cmd -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    }
    $runDirectory = Join-Path $backend ('target/assistant-vector-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $runDirectory | Out-Null
    $javaArgs = @('-jar', ('"{0}"' -f $jar), '--spring.profiles.active=default',
        '--server.address=127.0.0.1', '--server.port=0',
        '--ASSISTANT_RAG_SANDBOX_ENABLED=true', '--ASSISTANT_RAG_INGEST=true',
        "--ASSISTANT_VECTOR_MONGO_URI=$vectorUri", "--ASSISTANT_GEMINI_EMBEDDING_MODEL=$EmbeddingModel")
    $app = Start-Process $java -ArgumentList $javaArgs -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runDirectory 'backend.log') `
        -RedirectStandardError (Join-Path $runDirectory 'backend.err.log')
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ($app.HasExited) { throw "Backend exited before vector verification. See $runDirectory." }
        $result = docker @vectorCompose exec -T -e "ASSISTANT_GEMINI_EMBEDDING_MODEL=$EmbeddingModel" assistant-vector `
            mongosh assistant_vector --quiet --file /opt/vitaloom/assistant-vector-verify.js 2>&1
        if ($LASTEXITCODE -eq 0) { $result; return }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "FAQ vectors are not queryable. Check Backend database configuration, Gemini key and quota. See $runDirectory."
} finally {
    if ($app -and !$app.HasExited) {
        Stop-Process -Id $app.Id
        $app.WaitForExit()
    }
    Pop-Location
}

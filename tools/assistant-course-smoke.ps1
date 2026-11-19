param(
    [int]$Port = 18080,
    [int]$MongoPort = 27182,
    [int]$WebPort = 18081,
    [switch]$KeepRunning,
    [switch]$GeminiRag,
    [string]$AtlasImage = 'mongodb/mongodb-atlas-local:preview'
)

$ErrorActionPreference = 'Stop'
if ($GeminiRag -and [string]::IsNullOrWhiteSpace($env:ASSISTANT_GEMINI_API_KEY)) {
    throw 'GeminiRag needs the approved synthetic sandbox key in ASSISTANT_GEMINI_API_KEY.'
}
$backend = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $backend 'target/vitaloom-backend-0.0.1-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Build the backend with mvnw.cmd package -DskipTests first.' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java.exe' }
$run = [guid]::NewGuid().ToString('N').Substring(0, 10)
$container = "vitaloom-assistant-mvp-$run"
$base = "http://127.0.0.1:$Port/api"
$endpoint = '/assistant/mvp/conversations'
$runDirectory = Join-Path $backend "target/assistant-course-$run"
New-Item -ItemType Directory -Path $runDirectory | Out-Null
$secret = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$refreshSecret = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$samplePassword = 'VitaLoomCourse!123'
$app = $null
$mongoStarted = $false
$passed = $false

function Wait-Ready {
    for ($i = 0; $i -lt 90; $i++) {
        if ($app.HasExited) { throw "Backend exited. See $runDirectory." }
        try {
            $result = Invoke-WebRequest "$base/health" -TimeoutSec 1
            if ($result.StatusCode -eq 200) { return }
        } catch { Start-Sleep -Milliseconds 400 }
    }
    throw "Backend readiness timed out. See $runDirectory."
}

function Start-Backend($accounts, $stage, [bool]$enabled = $true, $ragOwner = '', [bool]$ingest = $false) {
    $mongoUri = "mongodb://127.0.0.1:$MongoPort/assistant_course?directConnection=true"
    $vectorUri = if ($ragOwner) { $mongoUri } else { '' }
    $argsForJvm = @('-jar', ('"{0}"' -f $jar), '--spring.profiles.active=default',
        "--vitaloom.assistant.course.enabled=$($enabled.ToString().ToLowerInvariant())",
        '--server.address=127.0.0.1', "--server.port=$Port",
        "--spring.mongodb.uri=$mongoUri",
        '--spring.mongodb.database=assistant_course',
        "--vitaloom.auth.access-secret=$secret", "--vitaloom.auth.refresh-secret=$refreshSecret",
        "--vitaloom.cors.allowed-origins=http://localhost:$WebPort,http://127.0.0.1:$WebPort",
        "--ASSISTANT_COURSE_ACCOUNT_IDS=$accounts",
        "--ASSISTANT_SANDBOX_ACCOUNT_ID=$ragOwner",
        "--ASSISTANT_VECTOR_MONGO_URI=$vectorUri",
        "--ASSISTANT_RAG_SANDBOX_ENABLED=$([bool]$ragOwner)",
        "--ASSISTANT_RAG_INGEST=$ingest")
    if (!$ragOwner) { $argsForJvm += '--ASSISTANT_GEMINI_API_KEY=' }
    $script:app = Start-Process -FilePath $java -ArgumentList $argsForJvm -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runDirectory "$stage.log") `
        -RedirectStandardError (Join-Path $runDirectory "$stage.err.log")
    Wait-Ready
}

function Stop-Backend {
    if ($app -and !$app.HasExited) {
        Stop-Process -Id $app.Id
        $app.WaitForExit()
    }
}

function Request($method, $path, $token = '', $data = $null) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    $options = @{ Method = $method; Uri = "$base$path"; Headers = $headers; TimeoutSec = 30 }
    if ($null -ne $data) {
        $options.Body = [Text.Encoding]::UTF8.GetBytes(($data | ConvertTo-Json -Compress))
        $options.ContentType = 'application/json; charset=utf-8'
    }
    Invoke-RestMethod @options
}

function Expect-Status($status, [scriptblock]$action) {
    try { $null = & $action } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq $status) { return }
        throw
    }
    throw "Expected HTTP $status."
}

function Assert($condition, $message) {
    if (!$condition) { throw $message }
}

function Send-Turn($token, $conversation, $question, $turnId = ([guid]::NewGuid().ToString()), [bool]$geminiAccepted = $false) {
    $id = $conversation.id
    Request Post "$endpoint/$id/turns" $token @{ text = $question; turnId = $turnId; geminiAccepted = $geminiAccepted }
}

try {
    $image = if ($GeminiRag) { $AtlasImage } else { 'mongo:8' }
    $dockerArgs = @('run', '--rm', '-d', '--name', $container, '-p', "127.0.0.1:${MongoPort}:27017")
    if ($GeminiRag) {
        $indexScript = Join-Path $PSScriptRoot 'mongo/assistant-vector-index.js'
        $dockerArgs += @('--mount', "type=bind,source=$indexScript,target=/opt/vitaloom/assistant-vector-index.js,readonly")
    }
    $null = docker @dockerArgs $image
    if ($LASTEXITCODE -ne 0) { throw 'Unable to start the isolated MongoDB container.' }
    $mongoStarted = $true
    $mongoReadyAttempts = if ($GeminiRag) { 120 } else { 45 }
    for ($i = 0; $i -lt $mongoReadyAttempts; $i++) {
        if ($GeminiRag) {
            $health = docker inspect --format '{{.State.Health.Status}}' $container
            if ($health -ne 'healthy') { Start-Sleep -Seconds 1; continue }
        }
        docker exec $container mongosh --quiet --eval 'quit(db.hello().isWritablePrimary ? 0 : 2)' 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    Assert ($i -lt $mongoReadyAttempts) "MongoDB did not become writable. Container health: $health"
    Start-Backend '' 'registration' $false

    $first = Request Post '/auth/register' '' @{email = "assistant-a-$run@example.test"; password = $samplePassword}
    $second = Request Post '/auth/register' '' @{email = "assistant-b-$run@example.test"; password = $samplePassword}
    $outside = Request Post '/auth/register' '' @{email = "assistant-c-$run@example.test"; password = $samplePassword}
    Expect-Status 404 { Request Get $endpoint $first.accessToken }
    $accounts = "$($first.user.id),$($second.user.id)"
    Stop-Backend
    $ragOwner = ''
    if ($GeminiRag) {
        $ragOwner = $first.user.id
        docker exec $container mongosh assistant_course --quiet --file /opt/vitaloom/assistant-vector-index.js | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not create Atlas Local vector index.' }
    }
    Start-Backend $accounts 'session' $true $ragOwner ([bool]$GeminiRag)

    $origin = "http://localhost:$WebPort"
    $preflight = Invoke-WebRequest -Method Options -Uri "$base$endpoint" -TimeoutSec 10 -Headers @{
        Origin = $origin
        'Access-Control-Request-Method' = 'POST'
        'Access-Control-Request-Headers' = 'authorization,content-type'
    }
    Assert ($preflight.Headers['Access-Control-Allow-Origin'] -contains $origin) 'Expo Web origin failed CORS preflight.'
    Expect-Status 401 { Request Get $endpoint }
    Expect-Status 403 { Request Get $endpoint $outside.accessToken }
    Expect-Status 403 { Request Post $endpoint $first.accessToken @{accepted = $false} }
    $owner = $first.accessToken
    $other = $second.accessToken
    $now = [datetimeoffset]::UtcNow
    $today = $now.ToOffset([timespan]::FromHours(7)).ToString('yyyy-MM-dd')
    $null = Request Post '/water/logs' $owner @{amountMl = 375; loggedAt = $now.ToString('o')}
    $null = Request Post '/sleep/v2/sessions' $owner @{sleepType = 'MAIN'; method = 'DURATION'; endDay = $today; minutes = 423}
    $chat = Request Post $endpoint $owner @{accepted = $true}
    $id = $chat.id
    $otherChat = Request Post $endpoint $other @{accepted = $true}
    Expect-Status 404 { Request Get "$endpoint/$id" $other }
    Expect-Status 404 { Request Delete "$endpoint/$id" $other }
    Expect-Status 404 { Send-Turn $other $chat 'Nước hôm nay?' }
    Assert ((Request Get $endpoint $other).items.id -eq $otherChat.id) 'Foreign chat leaked in list.'

    $turn = [guid]::NewGuid().ToString()
    $fixture = 'Mình xem nhật ký giấc ngủ ở đâu?'
    if ($GeminiRag) {
        $probe = @'
const faq = db.assistant_sandbox_faq;
const rows = faq.find({ status: 'APPROVED' }).toArray();
if (rows.length !== 3) quit(2);
for (const row of rows) {
  const matches = faq.aggregate([{ $vectorSearch: {
    index: 'assistant_fixture_v1', path: 'embedding', exact: true, limit: 4,
    queryVector: row.embedding,
    filter: { status: 'APPROVED', generation: row.generation,
              language: 'vi', domain: 'APP_GUIDANCE' }
  }}]).toArray();
  if (!matches.some(match => match._id === row._id)) quit(2);
}
quit(0);
'@
        for ($i = 0; $i -lt 30; $i++) {
            if ($app.HasExited) { throw "Ingest failed. See $runDirectory." }
            docker exec $container mongosh assistant_course --quiet --eval $probe 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) { break }
            Start-Sleep -Seconds 1
        }
        Assert ($LASTEXITCODE -eq 0) 'FAQ vector did not become searchable.'
        $otherFixture = Send-Turn $other $otherChat $fixture ([guid]::NewGuid().ToString()) $true
        Assert ($otherFixture.turns[-1].parts[0].source.id -eq 'sleep-journal') 'Non-sandbox account took the Gemini path.'
    }
    $answer = Send-Turn $owner $chat $fixture $turn ([bool]$GeminiRag)
    Assert ($answer.turns[0].parts[0].source.route -eq '/sleep/journal') 'Expected a trusted citation.'
    if ($GeminiRag) {
        $reply = $answer.turns[-1]
        Assert ($reply.outcome -eq 'ANSWERED' -and $reply.parts[0].source.id -eq 'app-help-sleep-journal' -and
                $reply.parts[0].source.version -eq 1) 'Gemini RAG did not confirm the versioned fixture.'
        $sandboxChat = Request Post $endpoint $owner @{accepted = $true}
        foreach ($faq in @(
            @{question='Tìm chỗ xem lại các lần ngủ đã ghi ở đâu?'; id='app-help-sleep-journal'},
            @{question='Có chỗ nào xem các lần uống trong tuần không?'; id='app-help-water-progress'},
            @{question='Mình vừa uống nước, nhập ở đâu?'; id='app-help-water-log'}
        )) {
            $faqReply = (Send-Turn $owner $sandboxChat $faq.question ([guid]::NewGuid().ToString()) $true).turns[-1]
            Assert ($faqReply.outcome -eq 'ANSWERED' -and $faqReply.parts[0].source.id -eq $faq.id) 'Gemini did not confirm the expected FAQ source.'
        }
        $sleepFixture = 'Nhật ký ngủ tổng hợp hôm nay ghi bao nhiêu phút?'
        $denied = (Send-Turn $owner $sandboxChat $sleepFixture ([guid]::NewGuid().ToString()) $true).turns[-1]
        Assert ($denied.outcome -eq 'PERMISSION_REQUIRED') 'Synthetic Sleep tool requires permission.'
        $null = Request Put "$endpoint/$($sandboxChat.id)/permission" $owner @{allowed = $true}
        $sleepReply = (Send-Turn $owner $sandboxChat $sleepFixture ([guid]::NewGuid().ToString()) $true).turns[-1]
        Assert ($sleepReply.outcome -eq 'ANSWERED' -and $sleepReply.parts[0].source.id -eq 'synthetic-sleep-journal' -and
                $sleepReply.parts[0].text -match '423 phút') 'Synthetic Gemini T01 callback failed.'
    }
    Assert ((Send-Turn $owner $chat $fixture $turn ([bool]$GeminiRag)).turns.Count -eq 1) 'Retry duplicated the turn.'
    Expect-Status 409 { Send-Turn $owner $chat 'Different question' $turn }
    $answer = Send-Turn $owner $chat 'Nước hôm nay?'
    Assert ($answer.turns[-1].outcome -eq 'PERMISSION_REQUIRED') 'Personal access was granted by default.'
    $null = Request Put "$endpoint/$id/permission" $owner @{allowed = $true}
    $answer = Send-Turn $owner $chat 'Nước hôm nay?'
    Assert ($answer.turns[-1].parts[0].text -match '375 ml') 'Owner Water fact is missing.'
    $null = Request Put "$endpoint/$($otherChat.id)/permission" $other @{allowed = $true}
    $answer = Send-Turn $other $otherChat 'Nước hôm nay?'
    Assert ($answer.turns[-1].outcome -eq 'NO_DATA') 'Foreign Water fact returned.'
    $answer = Send-Turn $other $otherChat 'Tôi ngủ hôm nay bao lâu?'
    Assert ($answer.turns[-1].outcome -eq 'NO_DATA') 'Foreign Sleep fact returned.'
    $answer = Send-Turn $other $otherChat 'Mở trang ghi chép cho giấc ngủ ở đâu?'
    Assert ($answer.turns[-1].parts[0].source.route -eq '/sleep/journal') 'Expected paraphrase retrieval.'
    $answer = Send-Turn $other $otherChat 'Tôi mở dữ liệu đường huyết ở đâu?'
    Assert ($answer.turns[-1].outcome -eq 'INSUFFICIENT_EVIDENCE') 'Unexpected evidence for an unsupported question.'
    $answer = Send-Turn $owner $chat 'Còn ngày hôm qua?'
    Assert ($answer.turns[-1].outcome -eq 'NO_DATA') 'Follow-up should query yesterday afresh.'
    $answer = Send-Turn $owner $chat 'Nước tuần này?'
    Assert ($answer.turns[-1].parts[0].text -match '375 ml') 'Weekly Water fact is missing.'
    $answer = Send-Turn $owner $chat 'Tôi ngủ hôm nay bao lâu?'
    Assert ($answer.turns[-1].parts[0].text -match '423 phút.*DURATION') 'Sleep Journal fact is missing.'
    $answer = Send-Turn $owner $chat 'Giấc ngủ gần đây?'
    Assert ($answer.turns[-1].parts[0].text -match '423 phút') 'Sleep Recent fact is missing.'
    $answer = Send-Turn $owner $chat 'Mở nhật ký giấc ngủ ở đâu và tôi uống nước hôm nay bao nhiêu?'
    Assert ($answer.turns[-1].outcome -eq 'PARTIAL' -and $answer.turns[-1].parts.Count -eq 2) 'Mixed evidence is missing.'
    $answer = Send-Turn $owner $chat 'Tôi đau ngực và uống nước hôm nay bao nhiêu?'
    Assert ($answer.turns[-1].outcome -eq 'SAFETY' -and !$answer.turns[-1].parts[0].source) 'Safety priority failed.'

    Stop-Backend
    Start-Backend $accounts 'restart' $true $ragOwner
    Assert ((Request Get "$endpoint/$id" $owner).turns.Count -eq 9) 'History did not survive backend restart.'
    Assert ((Request Put "$endpoint/$id/permission" $owner @{allowed = $false}).turns.Count -eq 0) 'Revocation left old transcript.'
    $answer = Send-Turn $owner $chat 'Nước hôm nay?'
    Assert ($answer.turns[-1].outcome -eq 'PERMISSION_REQUIRED') 'Personal access remained enabled.'
    $null = Request Delete "$endpoint/$id" $owner
    Expect-Status 404 { Request Get "$endpoint/$id" $owner }

    $passed = $true
    Write-Output 'PASS: feature gate, Web CORS, JWT/allowlist, consent, owner isolation, trusted sources, paraphrase, no evidence, replay, T01-T04, follow-up, mixed, safety, restart, revocation, deletion.'
    if ($GeminiRag) { Write-Output 'PASS: live 768-dimensional FAQ embedding, three paraphrases and synthetic Gemini T01 tool callback.' }
    if ($KeepRunning) {
        Write-Output "Backend: $base"
        Write-Output "Demo A: $($first.user.email) / $samplePassword"
        Write-Output "Demo B: $($second.user.email) / $samplePassword"
        Write-Output "Cleanup: Stop-Process -Id $($app.Id); docker stop $container"
    }
} finally {
    if (!$KeepRunning -or !$passed) {
        Stop-Backend
        if ($mongoStarted) { docker stop $container | Out-Null }
    }
}

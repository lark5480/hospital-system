<#
.SYNOPSIS
    号源并发防超卖测试：验证 SlotMapper.incrementBooked 的条件 UPDATE 在并发下不超卖。

.DESCRIPTION
    主张：BookingService.book() 用 `UPDATE ... WHERE booked < capacity` 在 DB 层原子占号，
          返回 0 即满号回滚，因此并发下绝不会写出超额预约。

    为什么不用登录接口取 token：登录有验证码、强制改密、连续失败 5 次锁定 15 分钟三重摩擦。
    本项目 JWT 自管（HS256，claim = sub / roles / authorities / iatMs，见 JwtTokenService.issue），
    故以固定 APP_JWT_SECRET 启动 core 后自行签发 token，把测试变量收敛到「并发占号」这一件事上。

    为什么要多个患者：book() 有 R-25 重复预约幂等校验（同一患者 + 同一号源直接拒绝），
    单个患者制造不出并发——只有不同患者之间才只剩容量守卫这一条防线。
    用 -SamePatientSameSlot 可反过来验证「同一患者并发双写」这个已知的 P3 竞态窗口。

.PARAMETER Secret
    JWT 签名密钥，必须与启动 hospital-core 时的 APP_JWT_SECRET 一致。
    未显式传入时取环境变量 APP_JWT_SECRET。

.EXAMPLE
    # 1) 起中间件与 core（密钥需 ≥32 字节，且不能命中弱密钥名单）
    docker compose up -d
    $env:APP_JWT_SECRET = '<your-32-byte-secret>'
    java -jar hospital-core/target/hospital-core-0.0.1-SNAPSHOT.jar

    # 2) 造数据（新建容量 2 的号源 + 12 个患者），记下返回的号源 id
    Get-Content scripts/slot-test-setup.sql -Raw | docker exec -i hospital-postgres psql -U postgres -d hospital -f -

    # 3) 并发抢号
    pwsh -File scripts/slot-race-test.ps1 -SlotId <id> -Capacity 2 -Patients 12

    # 4) 现场还原
    Get-Content scripts/slot-test-cleanup.sql -Raw | docker exec -i hospital-postgres psql -U postgres -d hospital -f -

.NOTES
    实测结论见 docs/review/2026-10-06-concurrency-verification.md。
#>
[CmdletBinding()]
param(
    [string]$BaseUrl     = 'http://localhost:8101',
    [int]$SlotId         = 0,       # 必填;不用 Mandatory 是为了在非交互场景下报错而不是挂起等输入
    [long]$PackageId     = 1,
    [int]$PatientIdFrom  = 411,
    [int]$Patients       = 12,
    [int]$Capacity       = 2,
    [switch]$SamePatientSameSlot,   # 负面验证：同一患者对同一号源并发双写（R-25 已知竞态）
    [string]$Secret      = $env:APP_JWT_SECRET
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

if ([string]::IsNullOrWhiteSpace($Secret)) {
    throw "缺少 JWT 密钥：请设置环境变量 APP_JWT_SECRET（须与启动 hospital-core 时的取值一致），或用 -Secret 传入。"
}
if ($SlotId -le 0) {
    throw "缺少 -SlotId：请传入被测号源 id（由 scripts/slot-test-setup.sql 输出的 NEW_SLOT 给出）。"
}

function New-Jwt {
    param([string]$Sec, [string]$Sub, [string[]]$Authorities = @('patient:booking'))
    function B64Url([byte[]]$d) { [Convert]::ToBase64String($d).TrimEnd('=').Replace('+','-').Replace('/','_') }
    $now = [DateTimeOffset]::UtcNow
    $payload = [ordered]@{
        jti         = [guid]::NewGuid().ToString()
        sub         = $Sub
        roles       = @('PATIENT')
        authorities = $Authorities
        iatMs       = $now.ToUnixTimeMilliseconds()
        iat         = $now.ToUnixTimeSeconds()
        exp         = $now.AddHours(1).ToUnixTimeSeconds()
    }
    $h = B64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $p = B64Url ([Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Compress)))
    $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Sec))
    $s = B64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$h.$p")))
    return "$h.$p.$s"
}

# 组装请求主体：patientId 与 phone 按 slot-test-setup.sql 的规律对应
$subjects = if ($SamePatientSameSlot) {
    for ($i = 1; $i -le $Patients; $i++) {
        [pscustomobject]@{ PatientId = $PatientIdFrom; Phone = '13900000001' }
    }
} else {
    for ($i = 1; $i -le $Patients; $i++) {
        [pscustomobject]@{ PatientId = $PatientIdFrom + $i - 1; Phone = '139000000' + ('{0:d2}' -f $i) }
    }
}

Write-Host "=========================================="
Write-Host "  号源并发防超卖测试"
Write-Host "  目标: $BaseUrl   号源: $SlotId（容量 $Capacity）   并发: $Patients"
if ($SamePatientSameSlot) { Write-Host "  模式: 同一患者并发双写（验证 R-25 已知竞态）" -ForegroundColor Yellow }
Write-Host "=========================================="

# 预生成 token，避免把签发耗时算进并发窗口
foreach ($s in $subjects) {
    $s | Add-Member -NotePropertyName Token -NotePropertyValue (New-Jwt -Sec $Secret -Sub $s.Phone)
}

# 同步起跑：所有请求忙等到同一时刻再发，最大化真实竞争窗口
$fireAt = (Get-Date).AddSeconds(3)
Write-Host ("同步起跑时刻: {0:HH:mm:ss.fff}（3 秒后）" -f $fireAt)

$results = $subjects | ForEach-Object -Parallel {
    $s = $_
    while ((Get-Date) -lt $using:fireAt) { }
    $body = @{ patientId = $s.PatientId; packageId = $using:PackageId; slotId = $using:SlotId } | ConvertTo-Json -Compress
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-WebRequest -Method Post -Uri "$($using:BaseUrl)/api/patient/appointments" `
                -Headers @{ Authorization = "Bearer $($s.Token)" } -ContentType 'application/json' `
                -Body $body -TimeoutSec 20 -UseBasicParsing
        $sw.Stop()
        [pscustomobject]@{ PatientId = $s.PatientId; Phone = $s.Phone; Status = [int]$r.StatusCode
                           Ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 1); Body = ($r.Content -replace '\s+',' ') }
    } catch {
        $sw.Stop()
        $code = 0; $txt = $_.Exception.Message
        if ($_.Exception.Response) {
            $code = [int]$_.Exception.Response.StatusCode.value__
            try { $txt = (New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch { }
        }
        [pscustomobject]@{ PatientId = $s.PatientId; Phone = $s.Phone; Status = $code
                           Ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 1); Body = ($txt -replace '\s+',' ') }
    }
} -ThrottleLimit $Patients

Write-Host ""
$results | Sort-Object PatientId | Format-Table PatientId, Phone, Status, Ms -AutoSize | Out-String | Write-Host

$ok    = @($results | Where-Object { $_.Status -eq 200 }).Count
$full  = @($results | Where-Object { $_.Status -eq 409 }).Count
$other = @($results | Where-Object { $_.Status -notin 200, 409 })

Write-Host "HTTP 200（占号成功）: $ok"
Write-Host "HTTP 409（号源已满）: $full"
if ($other.Count -gt 0) {
    Write-Host "其他状态码：" -ForegroundColor Yellow
    $other | ForEach-Object { Write-Host ("  患者 {0} → {1}  {2}" -f $_.PatientId, $_.Status, $_.Body.Substring(0, [Math]::Min(120, $_.Body.Length))) -ForegroundColor Yellow }
}

Write-Host ""
Write-Host "==================== 判定 ===================="
if ($SamePatientSameSlot) {
    if ($ok -gt 1) { Write-Host "⚠ 同一患者并发双写产生 $ok 条预约（>1）——复现了 R-25 的 P3 竞态窗口" -ForegroundColor Yellow }
    else           { Write-Host "本次未复现 R-25 竞态（成功数 $ok）——竞态窗口未被命中" -ForegroundColor Green }
} else {
    if     ($ok -eq $Capacity) { Write-Host "✅ 成功数 == 容量（$ok == $Capacity），无超卖" -ForegroundColor Green }
    elseif ($ok -lt $Capacity) { Write-Host "⚠ 成功数（$ok）< 容量（$Capacity）——未超卖，但有请求被非容量原因拒绝" -ForegroundColor Yellow }
    else                       { Write-Host "❌ 成功数（$ok）> 容量（$Capacity）——发生超卖" -ForegroundColor Red }
}
Write-Host "=============================================="

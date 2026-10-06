# order-e2e.ps1 — flash-sale「真实下单闭环」顺序测试
#
# 为什么需要它：wrk 脚本无法逐请求获取并求解图形验证码，所以压测打下单接口只会得到
# 验证码/限流拒绝路径的吞吐，不能代表真实下单。本脚本按真实客户端流程串行跑：
#
#   1. GET  /api/auth/captcha              取 captchaId + SVG（从 SVG 文本节点解出算术答案）
#   2. POST /api/flash-sale/{id}/purchase?captchaId=&captchaAnswer=   下单，拿 messageKey
#   3. GET  /api/order/status?messageKey=  轮询直到 DONE / FAILED
#
# 产出：成功率、端到端各阶段延迟（P50/P95）、失败原因分布。
#
# 用法：pwsh -File order-e2e.ps1 [-BaseUrl http://localhost:8080] [-FlashSaleId 1] [-Iterations 30]

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$FlashSaleId = 1,
    [int]$Iterations = 30,
    [int]$MinIntervalMs = 1200,   # 下单接口带 @RateLimit(permits=5, windowSeconds=5)，串行也需节流，否则测的是限流器
    [string]$UserIdCsv = '',      # 逗号分隔的全新用户 ID，每轮换一个（同一用户受「每人限购」约束，只会拿到 50002）
    [string]$Secret = 'flash-sale-secret-key-min-256-bits-long-for-hs256'
)

$UserIds = @()
if ($UserIdCsv) { $UserIds = @($UserIdCsv.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ } | ForEach-Object { [int]$_ }) }

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

function New-Jwt {
    param([string]$Sec, [string]$Sub = '1')
    function B64Url([byte[]]$d) { [Convert]::ToBase64String($d).TrimEnd('=').Replace('+','-').Replace('/','_') }
    $now = [int][double]::Parse((Get-Date -UFormat %s))
    $header  = B64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $payload = B64Url ([Text.Encoding]::UTF8.GetBytes((@{ sub = $Sub; role = 'ADMIN'; iat = $now; exp = $now + 86400 } | ConvertTo-Json -Compress)))
    $si = "$header.$payload"
    $h = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Sec))
    $sig = B64Url ($h.ComputeHash([Text.Encoding]::UTF8.GetBytes($si)))
    return "$si.$sig"
}

function Get-CaptchaAnswer {
    param([string]$Svg)
    # SVG 中算术表达式以文本节点形式存在，例如： 3 [-] 2 [=] [?]
    $texts = [regex]::Matches($Svg, '>([^<>]+)<') | ForEach-Object { $_.Groups[1].Value.Trim() } | Where-Object { $_ -ne '' }
    $expr = ($texts -join '')
    if ($expr -match '(\d+)\s*([\+\-×x\*])\s*(\d+)') {
        $a = [int]$Matches[1]; $op = $Matches[2]; $b = [int]$Matches[3]
        switch -Regex ($op) {
            '\+'    { return $a + $b }
            '-'     { return $a - $b }
            '[×x\*]' { return $a * $b }
        }
    }
    return $null
}

$token = New-Jwt -Sec $Secret
$headers = @{ Authorization = "Bearer $token" }
$ok = 0; $fail = 0
$failReasons = @{}
$latCaptcha = @(); $latPurchase = @(); $latPoll = @()

Write-Host ("flash-sale 真实下单闭环测试  目标={0}  活动={1}  轮数={2}" -f $BaseUrl, $FlashSaleId, $Iterations)
Write-Host '------------------------------------------------------------------------'

for ($i = 1; $i -le $Iterations; $i++) {
    # 每轮换一个用户（同一用户受「每人限购」约束，重复用同一 ID 只会拿到 50002）
    if ($UserIds.Count -gt 0) {
        $sub = [string]$UserIds[($i - 1) % $UserIds.Count]
        $headers = @{ Authorization = "Bearer $(New-Jwt -Sec $Secret -Sub $sub)" }
    }
    # 1) 取验证码
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $cap = Invoke-RestMethod -Uri "$BaseUrl/api/auth/captcha" -TimeoutSec 10
    } catch { $fail++; $failReasons['captcha_http_error'] = 1 + ($failReasons['captcha_http_error'] ?? 0); continue }
    $sw.Stop(); $latCaptcha += $sw.Elapsed.TotalMilliseconds
    $cid = $cap.data.captchaId
    $ans = Get-CaptchaAnswer -Svg $cap.data.svg
    if ($null -eq $ans) { $fail++; $failReasons['captcha_unsolvable'] = 1 + ($failReasons['captcha_unsolvable'] ?? 0); continue }

    # 2) 下单
    if ($i -gt 1 -and $MinIntervalMs -gt 0) { Start-Sleep -Milliseconds $MinIntervalMs }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $pur = Invoke-RestMethod -Method Post -TimeoutSec 10 -Headers $headers `
                -Uri "$BaseUrl/api/flash-sale/$FlashSaleId/purchase?captchaId=$cid&captchaAnswer=$ans"
    } catch {
        $sw.Stop()
        $code = $_.Exception.Response.StatusCode.value__
        $fail++
        $k = "purchase_http_$code"
        $failReasons[$k] = 1 + ($failReasons[$k] ?? 0)
        continue
    }
    $sw.Stop(); $latPurchase += $sw.Elapsed.TotalMilliseconds

    if ($pur.code -ne 200) {   # flash-sale 的成功码是 200（ResultCode.SUCCESS），不是 0
        $fail++
        $k = "purchase_code_$($pur.code):$($pur.msg)"
        $failReasons[$k] = 1 + ($failReasons[$k] ?? 0)
        continue
    }

    # 3) 轮询订单状态
    $mk = $pur.data.messageKey
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $final = $null
    for ($p = 0; $p -lt 20; $p++) {
        Start-Sleep -Milliseconds 150
        try { $st = Invoke-RestMethod -Uri "$BaseUrl/api/order/status?messageKey=$mk" -TimeoutSec 10 -Headers $headers } catch { break }
        if ($st.data -and $st.data.status -and $st.data.status -ne 'PROCESSING') { $final = $st.data; break }    }
    $sw.Stop(); $latPoll += $sw.Elapsed.TotalMilliseconds

    if ($final -and $final.status -eq 'DONE') { $ok++ }
    else {
        $fail++
        $k = if ($final) { "final_$($final.status):$($final.failReason)" } else { 'poll_timeout_or_no_final' }
        $failReasons[$k] = 1 + ($failReasons[$k] ?? 0)
    }

    if ($i % 5 -eq 0) { Write-Host ("  已完成 {0}/{1}  成功={2} 失败={3}" -f $i, $Iterations, $ok, $fail) }
}

function Stat($arr) {
    if (-not $arr -or $arr.Count -eq 0) { return 'n/a' }
    $s = $arr | Sort-Object
    $p = { param($q) $s[[math]::Min($s.Count - 1, [int][math]::Ceiling($q * $s.Count) - 1)] }
    return ("P50={0:N1}ms  P95={1:N1}ms  max={2:N1}ms  n={3}" -f (& $p 0.50), (& $p 0.95), $s[-1], $s.Count)
}

Write-Host ''
Write-Host '==================== 结果 ====================' -ForegroundColor Cyan
Write-Host ("总轮数        : {0}" -f $Iterations)
Write-Host ("成功闭环 DONE : {0}  ({1:N1}%)" -f $ok, (100.0 * $ok / [math]::Max(1, $Iterations)))
Write-Host ("失败          : {0}" -f $fail)
if ($failReasons.Count -gt 0) {
    Write-Host '失败原因分布  :'
    $failReasons.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { Write-Host ("    {0,-40} {1}" -f $_.Key, $_.Value) }
}
Write-Host ("取验证码延迟  : {0}" -f (Stat $latCaptcha))
Write-Host ("下单接口延迟  : {0}" -f (Stat $latPurchase))
Write-Host ("状态轮询延迟  : {0}" -f (Stat $latPoll))
Write-Host '==============================================' -ForegroundColor Cyan

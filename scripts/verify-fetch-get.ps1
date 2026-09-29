param([int]$Port = 18765)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$headers = @{ 'X-Test-Token' = (Get-Content -LiteralPath (Join-Path $repoRoot 'webview2-sample/build/http-token') -Raw) }
$base = "http://127.0.0.1:$Port"
function Eval([string]$script) {
    Invoke-RestMethod -Uri "$base/evaluate" -Headers $headers -Method Post -ContentType 'text/plain; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($script))
}
$results = [System.Collections.Generic.List[string]]::new()
function Assert([bool]$condition, [string]$name) {
    if (!$condition) { throw "FAIL: $name" }
    $results.Add($name)
    Write-Output "PASS: $name"
}
function Wait-Until([scriptblock]$predicate) {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (& $predicate) { return }
        Start-Sleep -Milliseconds 100
    }
    throw 'Timeout waiting for page test'
}
Wait-Until { (Invoke-RestMethod -Uri "$base/state" -Headers $headers).status -eq 'Ready' }
Eval 'document.querySelector("#fetchButton").click(); true' | Out-Null
Wait-Until { (Eval 'window.fetchDemo?.done === true') -eq $true }
$demo = Eval 'window.fetchDemo'
Assert (!$demo.error) 'page network demo completed'
Assert ($demo.pageOrigin -ne $demo.apiOrigin) 'page and API use different origins'
Assert ($demo.browserBlocked -and $demo.stats.browserRequests -gt 0) 'browser GET reached API but CORS blocked reading response'
Assert ($demo.promise -and $demo.response.ok -and $demo.response.status -eq 200) 'native fetchGet resolves Promise with HTTP 200'
Assert ($demo.response.json.header -eq 'from-JavaScript' -and $demo.response.json.query -eq 'via=native') 'JsonObject options carry headers and URL query'
Assert ($demo.response.json.nested.enabled -and $demo.response.json.nested.items[1] -eq 'two') 'JsonObject return preserves nested objects and arrays'
Assert ($demo.response.headers.'x-demo-reply'[0] -eq 'native-fetch' -and $demo.response.body.Contains('中文')) 'response includes headers and Unicode body'
Eval @'
window.fetchErrors = {};
(async () => {
    const r = window.fetchErrors;
    const missing = await App.fetchGet(apiOrigin + '/missing', {});
    r.httpError = !missing.ok && missing.status === 404 && missing.json.path === '/missing';
    try { await App.fetchGet(apiOrigin + '/slow', {timeoutMs:100}); }
    catch(e) { r.timeout = e.name === 'HttpTimeoutException'; }
    try { await App.fetchGet(apiOrigin + '/data', {headers:{'X-Demo':42}}); }
    catch(e) { r.validation = e.name === 'IllegalArgumentException'; }
    r.done = true;
})().catch(e => { fetchErrors.error = String(e); fetchErrors.done = true; });
true
'@ | Out-Null
Wait-Until { (Eval 'window.fetchErrors.done === true') -eq $true }
$errors = Eval 'window.fetchErrors'
Assert (!$errors.error -and $errors.httpError) 'HTTP 404 resolves structured response instead of rejecting'
Assert ($errors.timeout) 'native request timeout rejects Promise'
Assert ($errors.validation) 'invalid JsonObject header value rejects Promise'
$state = Invoke-RestMethod -Uri "$base/state" -Headers $headers
Assert ($state.activations -eq 0) 'network demo does not activate a window'
$evidence = Join-Path $repoRoot 'webview2-sample/build/evidence'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
Eval 'document.querySelector("#fetchResult").scrollIntoView(); true' | Out-Null
Start-Sleep -Milliseconds 300
Invoke-WebRequest -Uri "$base/screenshot" -Headers $headers -OutFile (Join-Path $evidence 'fetch-get.png')
@{passed=$results;demo=$demo;errors=$errors;activations=$state.activations} | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $evidence 'fetch-get.json')
Eval 'scrollTo(0,0); true' | Out-Null

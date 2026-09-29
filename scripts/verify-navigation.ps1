param([int]$Port = 18765)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$headers = @{ 'X-Test-Token' = (Get-Content -LiteralPath (Join-Path $repoRoot 'webview2-sample/build/http-token') -Raw) }
$base = "http://127.0.0.1:$Port"
function State { Invoke-RestMethod -Uri "$base/state" -Headers $headers }
function Eval([string]$script) { Invoke-RestMethod -Uri "$base/evaluate" -Headers $headers -Method Post -ContentType 'text/plain; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($script)) }
function Control([hashtable]$values) { Invoke-RestMethod -Uri "$base/control" -Headers $headers -Method Post -ContentType 'application/json' -Body ($values | ConvertTo-Json) | Out-Null }
function Await([string]$label, [scriptblock]$condition) {
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $state = State
        if ($state.status.StartsWith('Error')) { throw $state.status }
        if (& $condition $state) { Write-Output "PASS: $label"; return }
        Start-Sleep -Milliseconds 50
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $label"
}
$original = State
Eval "history.pushState({}, '', '#navigation-check'); document.title='Navigation check'; true" | Out-Null
Await 'Compose URL/title/history state updated' { param($s) $s.url.EndsWith('#navigation-check') -and $s.title -eq 'Navigation check' -and $s.canGoBack }
Control @{goBack=$true}
Await 'state.goBack and canGoForward' { param($s) $s.url -eq $original.url -and $s.canGoForward }
Control @{goForward=$true}
Await 'state.goForward' { param($s) $s.url.EndsWith('#navigation-check') }
Control @{goBack=$true}
Await 'restore original URL' { param($s) $s.url -eq $original.url }
Eval ('document.title=' + ($original.title | ConvertTo-Json -Compress)) | Out-Null
if ((State).activations -ne 0) { throw 'Window activated' }

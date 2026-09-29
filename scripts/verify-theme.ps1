param([int]$Port = 18765)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$headers = @{ 'X-Test-Token' = (Get-Content -LiteralPath (Join-Path $repoRoot 'webview2-sample/build/http-token') -Raw) }
$base = "http://127.0.0.1:$Port"
function Eval([string]$script) {
    Invoke-RestMethod -Uri "$base/evaluate" -Method Post -Headers $headers -ContentType 'text/plain; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($script))
}
Eval 'window.themeTestMarker="retained"; window.themeChanges=0; matchMedia("(prefers-color-scheme:dark)").addEventListener("change",()=>window.themeChanges++); true' | Out-Null
foreach ($scheme in @('Light', 'Dark', 'Light', 'Auto')) {
    Invoke-RestMethod -Uri "$base/control" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{colorScheme=$scheme} | ConvertTo-Json) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        Start-Sleep -Milliseconds 100
        $state = Invoke-RestMethod -Uri "$base/state" -Headers $headers
        if ($state.status.StartsWith('Error')) { throw $state.status }
        $expected = if ($scheme -eq 'Dark') { 'true' } else { 'false' }
        $condition = if ($scheme -eq 'Auto') { 'true' } else { "matchMedia('(prefers-color-scheme:dark)').matches===$expected" }
        $result = Eval "window.themeTestMarker==='retained' && ($condition)"
        if ($result -eq 'true') { break }
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($result -ne 'true') { throw "Theme update failed: $scheme ($result)" }
    if ($state.activations -ne 0) { throw 'Window activation detected' }
    Write-Output "PASS: $scheme without reload or window activation"
}
if ((Eval 'window.themeChanges>=2') -ne 'true') { throw 'Missing media change events' }
Write-Output 'PASS: media query change events delivered'

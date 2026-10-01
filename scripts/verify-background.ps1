param([int]$Port = 18765)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$headers = @{ 'X-Test-Token' = (Get-Content -LiteralPath (Join-Path $repoRoot 'webview2-sample/build/http-token') -Raw) }
$base = "http://127.0.0.1:$Port"
$evidence = Join-Path $repoRoot 'webview2-sample/build/evidence'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$results = [System.Collections.Generic.List[string]]::new()
function State {
    $snapshot = Invoke-RestMethod -Uri "$base/state" -Headers $headers
    if ($snapshot.status.StartsWith('Error')) { throw $snapshot.status }
    return $snapshot
}
function Post([string]$route, [object]$body) {
    Invoke-RestMethod -Uri "$base/$route" -Headers $headers -Method Post -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress) | Out-Null
}
function Eval([string]$script) { Invoke-RestMethod -Uri "$base/evaluate" -Headers $headers -Method Post -ContentType 'text/plain; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($script)) }
function Assert([bool]$condition, [string]$name) {
    if (!$condition) { throw "FAIL: $name" }
    $results.Add($name)
    Write-Output "PASS: $name"
}
function Wait-Until([scriptblock]$predicate, [string]$name) {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (& $predicate) { return }
        Start-Sleep -Milliseconds 100
    }
    throw "Timeout: $name"
}
function Click([string]$name) {
    $rect = (State).bounds.$name
    if (!$rect) { throw "No bounds for $name" }
    Post 'pointer' @{ x=[int]($rect.x + $rect.width/2); y=[int]($rect.y+$rect.height/2) }
    Start-Sleep -Milliseconds 350
}
function Screenshot([string]$name) {
    Start-Sleep -Milliseconds 350
    Invoke-WebRequest -Uri "$base/screenshot" -Headers $headers -OutFile (Join-Path $evidence "$name.png")
}

Post 'control' @{ popup=$false; dialog=$false; menu=$false; mounted=$true; opacity=1; width=1100; reload=$true }
Wait-Until { $s=State; $s.status -eq 'Ready' -and $s.frames -gt 5 } 'initial browser frame'
Start-Sleep -Milliseconds 500
$initial = State
Assert ($initial.width -eq $initial.bounds.webArea.width -and $initial.height -eq $initial.bounds.webArea.height) 'capture matches Compose pixel dimensions'
Assert ((Eval 'document.querySelector("h1").textContent') -eq 'Real Edge WebView2') 'actual browser document loaded'
$scrollbar = Eval '({viewportWidth:innerWidth,clientWidth:document.documentElement.clientWidth,viewportHeight:innerHeight,scrollHeight:document.documentElement.scrollHeight})'
Assert ($scrollbar.scrollHeight -gt $scrollbar.viewportHeight) 'fixture overflows vertically'
Assert ($scrollbar.viewportWidth -eq $scrollbar.clientWidth) 'FluentOverlay scrollbar reserves no layout width'
$scrollbar | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'scrollbar.json') -Encoding utf8
Assert ((Eval 'window.bridgeVersion') -eq '0.1.0') 'page startup calls synchronous Kotlin method'
Assert ((Eval 'App.getVersion() instanceof Promise') -eq $false) 'ordinary Kotlin method returns synchronously'
Eval 'window.bridgeGreeting=null; App.greet("Compose").then(value=>window.bridgeGreeting=value); true' | Out-Null
Wait-Until { (Eval 'window.bridgeGreeting') -eq 'Hello, Compose' } 'suspend Kotlin response'
Assert ((Eval 'window.bridgeGreeting') -eq 'Hello, Compose') 'explicit message response resolves application Promise'
Screenshot 'base'

$clicks = Eval 'window.clicks'
$before = (State).overlayClicks
Click 'overlay'
Assert ((State).overlayClicks -eq $before+1) 'Compose overlay receives pointer events'
Assert ((Eval 'window.clicks') -eq $clicks) 'overlay click does not reach browser'

Click 'openPopup'
Wait-Until { (State).popup } 'popup opening'
Screenshot 'popup'
$before = (State).popupClicks
Click 'popupAction'
Assert ((State).popupClicks -eq $before+1) 'standard Popup receives pointer events'
Assert ((Eval 'window.clicks') -eq $clicks) 'Popup click does not reach browser'
Click 'popupClose'
Assert (!(State).popup) 'Popup closes through its own button'

Click 'openDialog'
Wait-Until { (State).dialog } 'dialog opening'
Screenshot 'dialog'
$before = (State).dialogClicks
Click 'dialogConfirm'
Assert ((State).dialogClicks -eq $before+1 -and !(State).dialog) 'standard Dialog confirms and closes'
Assert ((Eval 'window.clicks') -eq $clicks) 'Dialog click does not reach browser'

Click 'openMenu'
Wait-Until { (State).menu } 'menu opening'
Screenshot 'menu'
$before = (State).popupClicks
Click 'menuItem'
Assert ((State).popupClicks -eq $before+1 -and !(State).menu) 'standard DropdownMenu receives pointer events'
Assert ((Eval 'window.clicks') -eq $clicks) 'menu click does not reach browser'

$button = Eval '(()=>{let r=document.querySelector("#pageButton").getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,dpr:devicePixelRatio}})()'
$area = (State).bounds.webArea
Post 'pointer' @{ x=[int]($area.x+$button.x*$button.dpr); y=[int]($area.y+$button.y*$button.dpr) }
Wait-Until { (Eval 'window.clicks') -gt $clicks } 'browser click'
Assert ((Eval 'document.querySelector("#pageButton").textContent') -eq 'Button clicked') 'browser pointer resumes after overlays close'
Post 'pointer' @{ x=[int]($area.x+$area.width/2); y=[int]($area.y+$area.height/2); wheel=4 }
Wait-Until { (Eval 'scrollY') -gt 50 } 'browser scrolling'
Assert ((Eval 'scrollY') -gt 50) 'wheel reaches browser'
Assert ((Eval 'innerWidth-document.documentElement.clientWidth') -eq 0) 'scrollbar reserves no layout width after wheel scrolling'
Screenshot 'scrollbar-scrolled'
Eval 'scrollTo(0,0)' | Out-Null

Click 'composeInput'
Invoke-RestMethod -Uri "$base/text" -Headers $headers -Method Post -ContentType 'text/plain' -Body 'Compose stays independent' | Out-Null
Assert ((State).composeText -match 'Compose stays independent') 'independent Compose text input accepts background key events'
Screenshot 'compose-input'
Post 'control' @{ opacity=0.5 }
Screenshot 'opacity'
Post 'control' @{ opacity=1; width=900 }
Wait-Until { $s=State; $s.width -lt $initial.width -and $s.width -eq $s.bounds.webArea.width } 'resized capture'
Assert ((State).width -lt $initial.width) 'capture follows window resize'
Screenshot 'resized'
Post 'control' @{ width=1100 }

for ($index=0; $index -lt 5; $index++) {
    Post 'control' @{ mounted=$false }
    Wait-Until { (State).activeNativeHosts -eq 0 } 'native host teardown'
    Post 'control' @{ mounted=$true }
    Wait-Until { $s=State; $s.activeNativeHosts -eq 1 -and $s.status -eq 'Ready' -and $s.frames -gt 2 } 'native host recreation'
}
Assert ((State).activeNativeHosts -eq 1) 'five dispose/recreate cycles return to one live native host'
$start = State
$timer = [Diagnostics.Stopwatch]::StartNew()
Start-Sleep -Seconds 3
$end = State
$timer.Stop()
$fps = ($end.frames-$start.frames)/$timer.Elapsed.TotalSeconds
$cpuCores = ($end.processCpuNanos-$start.processCpuNanos)/1e9/$timer.Elapsed.TotalSeconds
Assert ($fps -gt 0) 'animated browser produces ongoing frames'
Assert ($end.activations -eq 0) 'no test window activation occurred'
Screenshot 'final'
$report = @{ time=(Get-Date -Format 'o'); passed=$results; framesPerSecond=$fps; javaCpuCoreEquivalent=$cpuCores; heapBytes=$end.heapBytes; width=$end.width; height=$end.height; activations=$end.activations; pending=@('real keyboard / IME / focus','cross-monitor DPI','minimize and restore','long-running resource/performance testing') }
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'report.json') -Encoding utf8
$report | ConvertTo-Json -Depth 5

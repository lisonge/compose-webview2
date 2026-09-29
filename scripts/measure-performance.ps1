param([int]$Port = 18765, [int]$Seconds = 5, [string]$Name = 'performance')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$headers = @{ 'X-Test-Token' = (Get-Content -LiteralPath (Join-Path $repoRoot 'webview2-sample/build/http-token') -Raw) }
$base = "http://127.0.0.1:$Port"
function State { Invoke-RestMethod -Uri "$base/state" -Headers $headers }
function Control($value) { Invoke-RestMethod -Uri "$base/control" -Headers $headers -Method Post -ContentType 'application/json' -Body ($value | ConvertTo-Json -Compress) | Out-Null }
function Eval([string]$script) { Invoke-RestMethod -Uri "$base/evaluate" -Headers $headers -Method Post -ContentType 'text/plain; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($script)) | Out-Null }
function Ready([long]$AfterRevision = -1) {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do { $s=State; if ($s.viewportReady -and !$s.isLoading -and $s.viewportRevision -gt $AfterRevision) { return }; Start-Sleep -Milliseconds 100 } while ([DateTime]::UtcNow -lt $deadline)
    throw "Viewport did not become ready: $($s | ConvertTo-Json -Compress)"
}
$results = [System.Collections.Generic.List[object]]::new()
$originalUrl=(State).url
try {
    Control @{ popup=$false; dialog=$false; menu=$false; mounted=$true; width=1100; url='about:blank' }
    Ready
    $deadline=[DateTime]::UtcNow.AddSeconds(20)
    while ((State).url -ne 'about:blank') {
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Benchmark page did not load' }
        Start-Sleep -Milliseconds 100
    }
    # Settle the offscreen AWT window geometry before both comparison runs.
    $revision=(State).viewportRevision
    Control @{ width=900 }; Ready -AfterRevision $revision
    $revision=(State).viewportRevision
    Control @{ width=1100 }; Ready -AfterRevision $revision
    foreach ($scenario in @('static', 'animation', 'scroll', 'resize')) {
        Eval "clearInterval(window.benchmarkTimer); document.body.innerHTML='<div id=box style=`"height:6000px;background:linear-gradient(#1460a0,#f08040)`">benchmark</div>'; scrollTo(0,0)"
        if ($scenario -eq 'animation') { Eval "box.animate([{opacity:1},{opacity:0.2}],{duration:500,iterations:Infinity,direction:'alternate'})" }
        if ($scenario -eq 'scroll') { Eval "window.benchmarkTimer=setInterval(()=>scrollTo(0,(performance.now()/2)%5000),16)" }
        Start-Sleep -Milliseconds 500
        $before=State; $timer=[Diagnostics.Stopwatch]::StartNew(); $resizeMs=[System.Collections.Generic.List[double]]::new()
        while ($timer.Elapsed.TotalSeconds -lt $Seconds) {
            if ($scenario -eq 'resize') {
                $previousRevision=(State).viewportRevision
                $resizeTimer=[Diagnostics.Stopwatch]::StartNew()
                Control @{ width= $(if ($resizeMs.Count % 2 -eq 0) { 900 } else { 1100 }) }
                Ready -AfterRevision $previousRevision
                $resizeMs.Add($resizeTimer.Elapsed.TotalMilliseconds)
            } else { Start-Sleep -Milliseconds 100 }
        }
        $after=State; $elapsed=$timer.Elapsed.TotalSeconds
        if ($after.activations -ne 0) { throw 'Unexpected window activation' }
        $fps=($after.frames-$before.frames)/$elapsed
        $result=[ordered]@{
            scenario=$scenario; seconds=[math]::Round($elapsed,2); width=$after.width; height=$after.height; scale=$after.frameScale
            deliveredFps=[math]::Round($fps,2)
            jvmProcessCpuCores=[math]::Round(($after.processCpuNanos-$before.processCpuNanos)/1e9/$elapsed,3)
            jvmAllocatedMiBPerSecond=[math]::Round(($after.allocatedBytes-$before.allocatedBytes)/1MB/$elapsed,2)
            deliveredPixelMiBPerSecond=[math]::Round($fps*$after.width*$after.height*4/1MB,2)
            finalHeapMiB=[math]::Round($after.heapBytes/1MB,2)
            resizeReadyMeanMs= $(if ($resizeMs.Count) { [math]::Round(($resizeMs | Measure-Object -Average).Average,2) } else { $null })
            activations=$after.activations
        }
        $results.Add($result); $result | ConvertTo-Json -Compress
    }
} finally { Eval 'clearInterval(window.benchmarkTimer)'; Control @{ url=$originalUrl; width=1100 } }
$directory=Join-Path $repoRoot 'build/performance'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
@{ measuredAt=[DateTime]::UtcNow.ToString('o'); note='JVM process CPU includes JNI, excludes WebView2 child processes. Allocation excludes native/Skia buffers. Pixel throughput is delivered BGRA payload, not total copy traffic. Resize latency is HTTP-observed with 100 ms polling; physical cross-monitor DPI is not tested.'; scenarios=$results } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $directory "$Name.json") -Encoding utf8

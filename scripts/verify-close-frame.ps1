param([int]$Port = 18765)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$headers = @{ 'X-Test-Token' = (Get-Content -LiteralPath (Join-Path $repoRoot 'webview2-sample/build/http-token') -Raw) }
$base = "http://127.0.0.1:$Port"
$evidence = Join-Path $repoRoot 'webview2-sample/build/evidence'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

# Pause only the test page's animation, so pre/post close pixels can be compared exactly.
Invoke-RestMethod -Uri "$base/evaluate" -Headers $headers -Method Post -ContentType 'text/plain' -Body 'document.getAnimations().forEach(a=>a.pause());document.activeElement?.blur()' | Out-Null
Start-Sleep -Milliseconds 500
$before = Invoke-RestMethod -Uri "$base/state" -Headers $headers
if ($before.status -ne 'Ready' -or $before.frames -lt 1) { throw 'Expected a running browser with captured frames' }
$beforeFile = Join-Path $evidence 'before-close.png'
$afterFile = Join-Path $evidence 'during-close.png'
Invoke-WebRequest -Uri "$base/screenshot" -Headers $headers -OutFile $beforeFile
try {
    # Test-only hold keeps the same closing scene visible after native teardown.
    Invoke-RestMethod -Uri "$base/quit?hold=true" -Headers $headers -Method Post | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(8)
    do {
        Start-Sleep -Milliseconds 50
        $after = Invoke-RestMethod -Uri "$base/state" -Headers $headers
    } while (!$after.shutdownReady -and [DateTime]::UtcNow -lt $deadline)
    if (!$after.shutdownReady -or $after.activeNativeHosts -ne 0 -or $after.status -ne 'Closed') {
        throw 'Native teardown did not complete while retaining the scene'
    }
    Start-Sleep -Milliseconds 300
    Invoke-WebRequest -Uri "$base/screenshot" -Headers $headers -OutFile $afterFile
    Add-Type -AssemblyName 'System.Drawing'
    $area = $before.bounds.webArea
    $rect = [Drawing.Rectangle]::new([int]$area.x, [int]$area.y, [int]$area.width, [int]$area.height)
    function PixelHash([string]$path) {
        $image = [Drawing.Bitmap]::new($path)
        try {
            $crop = $image.Clone($rect, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
            try {
                $locked = $crop.LockBits([Drawing.Rectangle]::new(0, 0, $crop.Width, $crop.Height),
                    [Drawing.Imaging.ImageLockMode]::ReadOnly, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
                try {
                    $bytes = [byte[]]::new([Math]::Abs($locked.Stride) * $crop.Height)
                    [Runtime.InteropServices.Marshal]::Copy($locked.Scan0, $bytes, 0, $bytes.Length)
                    $hash = [Security.Cryptography.SHA256]::Create()
                    try { return [Convert]::ToBase64String($hash.ComputeHash($bytes)) } finally { $hash.Dispose() }
                } finally { $crop.UnlockBits($locked) }
            } finally { $crop.Dispose() }
        } finally { $image.Dispose() }
    }
    if ((PixelHash $beforeFile) -ne (PixelHash $afterFile)) { throw 'WebView region changed during native teardown' }
    if ($after.activations -ne 0) { throw 'Background test activated a window' }
    Write-Output 'PASS: last WebView frame remains pixel-identical after native hosts reach zero'
    Write-Output 'PASS: shutdown does not activate a window'
} finally {
    Invoke-RestMethod -Uri "$base/quit" -Headers $headers -Method Post | Out-Null
}

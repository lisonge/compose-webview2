$ErrorActionPreference = 'Stop'
foreach ($name in @('java', 'javac', 'git')) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) { Write-Output "$name : $($command.Source)" } else { Write-Output "$name : MISSING" }
}
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (Test-Path -LiteralPath $vswhere) {
    & $vswhere -latest -products '*' -requires 'Microsoft.VisualStudio.Component.VC.Tools.x86.x64' -property 'installationPath'
} else { Write-Output 'Visual Studio Build Tools: MISSING' }
$sdk = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10/Include'
if (Test-Path -LiteralPath $sdk) { Get-ChildItem -LiteralPath $sdk -Name }
Write-Output 'Run .\gradlew.bat :webview2-compose:nativeSmoke to verify JNI, D3D11 and Graphics Capture.'

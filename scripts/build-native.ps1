param(
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (!(Test-Path -LiteralPath $vswhere)) { throw 'Visual Studio Build Tools is required' }
$vs = @(& $vswhere -latest -products '*' -requires 'Microsoft.VisualStudio.Component.VC.Tools.x86.x64' -format json | ConvertFrom-Json)[0]
if (!$vs) { throw 'Install the MSVC x64/x86 build tools component' }
$vsRoot = $vs.installationPath
$generator = switch (([version]$vs.installationVersion).Major) {
    17 { 'Visual Studio 17 2022' }
    18 { 'Visual Studio 18 2026' }
    default { throw "Unsupported Visual Studio version: $($vs.installationVersion)" }
}
$cmake = Join-Path $vsRoot 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
if (!(Test-Path -LiteralPath $cmake)) { $cmake = (Get-Command 'cmake' -ErrorAction Stop).Source }
$env:JAVA_HOME = $JavaHome
$buildDirectory = Join-Path $repoRoot 'webview2-native/build/windows-x64'
& $cmake -S (Join-Path $repoRoot 'webview2-native') -B $buildDirectory -G $generator -A 'x64' "-DCMAKE_GENERATOR_INSTANCE=$vsRoot"
if ($LASTEXITCODE) { throw 'CMake configure failed' }
& $cmake --build $buildDirectory --config 'Release' --parallel
if ($LASTEXITCODE) { throw 'Native build failed' }
& $cmake --install $buildDirectory --config 'Release' --prefix $OutputDirectory
if ($LASTEXITCODE) { throw 'Native install failed' }

<#
    Builds libmoonload.so — the loader that picks up downloaded engines.

    Tiny, and the only native code that must always be present, so it ships inside the APK like
    any other jniLib. Everything it loads lives in the app's data directory instead.

    Usage:  powershell -ExecutionPolicy Bypass -File android\build-moonload.ps1
#>
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot

$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'C:\moonbuild\android-sdk' }
$ndk = (Get-ChildItem (Join-Path $sdk 'ndk') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$cc = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android24-clang.cmd'
if (-not (Test-Path $cc)) { throw "NDK clang not found: $cc" }

$dest = Join-Path $here 'app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$out = Join-Path $dest 'libmoonload.so'

& $cc -shared -fPIC -O2 -Wall -o $out (Join-Path $here 'moonload\moonload.c') -ldl
if ($LASTEXITCODE -ne 0) { throw 'clang failed' }

Write-Host "Built $out ($((Get-Item $out).Length) bytes)" -ForegroundColor Green

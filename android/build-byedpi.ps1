<#
    Builds libbyedpi.so — byedpi (hufrea/byedpi, MIT) cross-compiled for arm64.

    It is not a library despite the name: it is the ciadpi executable, and the .so is a
    disguise. Android only unpacks and marks executable the files under jniLibs, so a
    binary that has to be run has to be called lib*.so to get there. Every VPN client that
    ships a core does the same thing.

    zapret proper cannot be used here: it needs nfqueue and iptables, which means root.
    byedpi does the same job from userspace as a local SOCKS5 proxy, and our own tunnel
    can feed it — see XrayConfig.byedpiOutbound.

    Usage:  powershell -ExecutionPolicy Bypass -File android\build-byedpi.ps1
#>

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dest = Join-Path $PSScriptRoot 'app\src\main\jniLibs\arm64-v8a'
$work = Join-Path $env:TEMP "byedpi-$(Get-Random)"

$sdk = if ($env:MOON_TOOLCHAIN) { Join-Path $env:MOON_TOOLCHAIN 'android-sdk' } else { 'C:\moonbuild\android-sdk' }
$ndk = (Get-ChildItem (Join-Path $sdk 'ndk') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$cc  = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android21-clang.cmd'
if (-not (Test-Path $cc)) { throw "NDK clang not found: $cc" }

New-Item -ItemType Directory -Force -Path $work, $dest | Out-Null
try {
    Write-Host 'Fetching byedpi...' -ForegroundColor Cyan
    $zip = Join-Path $work 'src.zip'
    Invoke-WebRequest -Uri 'https://github.com/hufrea/byedpi/archive/refs/heads/main.zip' -OutFile $zip -UseBasicParsing
    Expand-Archive -Path $zip -DestinationPath $work -Force
    $src = (Get-ChildItem $work -Directory -Filter 'byedpi-*' | Select-Object -First 1).FullName

    # The .c list comes from their Makefile. win_service.c is deliberately absent: it is the
    # Windows service wrapper and it does not compile anywhere else.
    $files = @('packets.c', 'main.c', 'conev.c', 'proxy.c', 'desync.c', 'mpool.c', 'extend.c') |
             ForEach-Object { Join-Path $src $_ }
    foreach ($f in $files) { if (-not (Test-Path $f)) { throw "missing source: $f" } }

    $out = Join-Path $work 'libbyedpi.so'
    Write-Host 'Compiling for arm64...' -ForegroundColor Cyan
    # -pie: Android has refused to run non-position-independent executables since 5.0.
    & $cc -D_DEFAULT_SOURCE -I$src -std=c99 -O2 -Wall -Wno-unused -fPIE -pie -s -o $out @files
    if ($LASTEXITCODE -ne 0) { throw 'clang failed' }

    Copy-Item $out (Join-Path $dest 'libbyedpi.so') -Force
    $kb = [math]::Round((Get-Item (Join-Path $dest 'libbyedpi.so')).Length / 1KB)
    Write-Host "`nDone: jniLibs\arm64-v8a\libbyedpi.so ($kb KB)" -ForegroundColor Green
}
finally { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }

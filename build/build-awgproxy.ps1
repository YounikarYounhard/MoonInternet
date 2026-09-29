<#
    Builds awgproxy — AmneziaWG behind a local SOCKS5 listener.

    The obfuscation and the cryptography are amneziawg-go's (amnezia-vpn/amneziawg-go, MIT),
    used as a library and unmodified. Our own code is in main.go and is the plumbing only:
    a SOCKS5 handshake and the copying either side of it.

    SOCKS rather than a TUN of its own, because that puts AmneziaWG where Hysteria2 already
    sits — xray dials it as an ordinary proxy outbound, so every routing rule the app has
    applies to it without writing any of that again. See main.go.

    THE VERSION MATTERS. `go get amneziawg-go@latest` resolves to v1.0.4, which has none of
    the 3.x keys: the 3.0 work lives on master under a LOWER version number. go.mod pins the
    commit; do not let anything "upgrade" it to the tag.

    Usage:  powershell -ExecutionPolicy Bypass -File build\build-awgproxy.ps1
            powershell ... -File build\build-awgproxy.ps1 -Android     # arm64 for the phone
#>
param(
    [switch]$Android,
    # Re-pin the engine to $awgRef below and update go.mod. Not done on every build: a build
    # should not quietly change which cryptography it links.
    [switch]$Sync
)

# The engine commit. NOT a tag: `@latest` resolves to v1.0.4, which predates AmneziaWG 3 entirely,
# because the 3.x work lives on master under a LOWER version number. This one is master at
# 28 Aug 2026 and includes "feat: awg 3.1 features" (1f50ad7, 12 Aug) plus the fixes after it.
# Bump deliberately, and re-test against a real server when you do — 3.0 and 3.1 do not interoperate:
# a 3.0 build against a 3.1 server sends handshake initiations that are simply never answered.
# Note the /v3 module path — AmneziaWG 3 raised the major version, so the import paths carry it too.
$awgRef = 'b5928efb6ca19f0153958460c3d141f04abc5c2e'

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$proj = Join-Path $here 'awgproxy'

# On PATH in CI; not on PATH on the development machine, where it lives under C:\moonbuild.
$go = if ($env:MOON_GO) { Join-Path $env:MOON_GO (Join-Path 'bin' 'go') }
      elseif (Get-Command go -ErrorAction SilentlyContinue) { (Get-Command go).Source }
      elseif (Test-Path 'C:\moonbuild\go126\bin\go.exe') { 'C:\moonbuild\go126\bin\go.exe' }
      elseif (Test-Path 'C:\moonbuild\go\bin\go.exe') { 'C:\moonbuild\go\bin\go.exe' }
      else { $null }
if (-not $go) { throw 'Go not found. Put it on PATH or set MOON_GO.' }
if (-not $env:GOPATH -and (Test-Path 'C:\moonbuild\gopath')) { $env:GOPATH = 'C:\moonbuild\gopath' }

Write-Host "Go: $(& $go version)" -ForegroundColor Cyan

Push-Location $proj
try {
    if ($Sync) {
        Write-Host "Pinning amneziawg-go to $awgRef ..." -ForegroundColor Cyan
        & $go get "github.com/amnezia-vpn/amneziawg-go/v3@$awgRef"
        if ($LASTEXITCODE -ne 0) { throw 'go get failed' }
        & $go get "github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack@$awgRef"
        if ($LASTEXITCODE -ne 0) { throw 'go get netstack failed' }
        & $go mod tidy
    }

    if ($Android) {
        # arm64 only, like every other core we ship: the phone build is arm64 and shipping
        # other ABIs would just be an app that dies the moment it tries to connect.
        $env:GOOS = 'android'
        $env:GOARCH = 'arm64'

        # A real shared library now, not an executable wearing a library's name.
        #
        # The engine is downloaded after the app is installed, and it lands in the app's data
        # directory — the one place Android will not exec a file from since Android 10. It will
        # map one executable and load it, which is what a .so is for. c-shared needs cgo, hence
        # the NDK compiler: this is the one build of ours that cannot have CGO_ENABLED=0.
        $sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'C:\moonbuild\android-sdk' }
        $ndk = (Get-ChildItem (Join-Path $sdk 'ndk') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
        $cc = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android24-clang.cmd'
        if (-not (Test-Path $cc)) { throw "NDK clang not found: $cc" }
        $env:CGO_ENABLED = '1'
        $env:CC = $cc

        $dest = Join-Path (Split-Path $here -Parent) 'dist\cores\android\arm64-v8a'
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        $out = Join-Path $dest 'libawgproxy.so'
        & $go build -tags moonlib -buildmode=c-shared -trimpath -ldflags '-s -w' -o $out .
        if ($LASTEXITCODE -ne 0) { throw 'go build failed' }
        # The C header cgo writes beside it is for a C caller; ours is loaded by name at runtime.
        Remove-Item ($out -replace '\.so$', '.h') -ErrorAction SilentlyContinue
        Write-Host "Built $out ($([math]::Round((Get-Item $out).Length/1MB,1)) MB)" -ForegroundColor Green
    }
    else {
        $env:GOOS = 'windows'
        $env:GOARCH = 'amd64'
        $dest = Join-Path (Split-Path $here -Parent) 'cores\awg'
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        $out = Join-Path $dest 'awgproxy.exe'
        & $go build -trimpath -ldflags '-s -w' -o $out .
        if ($LASTEXITCODE -ne 0) { throw 'go build failed' }
        Write-Host "Built $out ($([math]::Round((Get-Item $out).Length/1MB,1)) MB)" -ForegroundColor Green
    }
}
finally {
    Pop-Location
    Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED -ErrorAction SilentlyContinue
}

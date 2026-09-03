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
    [switch]$Android
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$proj = Join-Path $here 'awgproxy'

# Go is not on PATH on this machine — see the build-environment note.
$goRoot = if ($env:MOON_GO) { $env:MOON_GO }
          elseif (Test-Path 'C:\moonbuild\go126\bin\go.exe') { 'C:\moonbuild\go126' }
          elseif (Test-Path 'C:\moonbuild\go\bin\go.exe') { 'C:\moonbuild\go' }
          else { $null }
if (-not $goRoot) { throw 'Go not found. Set MOON_GO to a Go installation.' }
$go = Join-Path $goRoot 'bin\go.exe'
if (-not $env:GOPATH -and (Test-Path 'C:\moonbuild\gopath')) { $env:GOPATH = 'C:\moonbuild\gopath' }

Write-Host "Go: $(& $go version)" -ForegroundColor Cyan

Push-Location $proj
try {
    if ($Android) {
        # arm64 only, like every other core we ship: the phone build is arm64 and shipping
        # other ABIs would just be an app that dies the moment it tries to connect.
        $env:GOOS = 'android'
        $env:GOARCH = 'arm64'
        $env:CGO_ENABLED = '0'
        $dest = Join-Path (Split-Path $here -Parent) 'android\app\src\main\jniLibs\arm64-v8a'
        New-Item -ItemType Directory -Force -Path $dest | Out-Null

        # libawgproxy.so is an executable wearing a library's name. Android only unpacks and
        # marks executable what lives under jniLibs, so a binary that has to be run has to be
        # called lib*.so to get there — the same disguise libbyedpi.so wears.
        $out = Join-Path $dest 'libawgproxy.so'
        & $go build -trimpath -ldflags '-s -w' -o $out .
        if ($LASTEXITCODE -ne 0) { throw 'go build failed' }
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

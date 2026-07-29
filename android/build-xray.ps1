<#
    Builds libv2ray.aar — xray-core wrapped for Android (2dust/AndroidLibXrayLite), the same
    core v2RayTun / HAPP / INCY ship, and the same one our Windows build runs.

    Why not sing-box: the subscriptions we target use VLESS XHTTP (sing-box has no such
    transport at all) and gRPC with an empty serviceName, which xray and sing-box interpret
    differently. Matching the desktop engine removes a whole class of "works on PC, not on
    the phone" bugs.

    Same traps as build-libbox.ps1 — space-free path, javac on PATH, arm64 only.

    Usage:  powershell -ExecutionPolicy Bypass -File android\build-xray.ps1
#>

$ErrorActionPreference = 'Stop'
$proj = Split-Path $PSScriptRoot -Parent
$tc = if ($env:MOON_TOOLCHAIN) { $env:MOON_TOOLCHAIN } else { "C:\moonbuild" }

$sdk = Join-Path $tc "android-sdk"
$ndk = (Get-ChildItem (Join-Path $sdk "ndk") -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$jdk = (Get-ChildItem $tc -Directory -Filter "jdk-17*" | Select-Object -First 1).FullName
$gopath = Join-Path $tc "gopath"
$work = Join-Path $tc "xray-src"

# AndroidLibXrayLite's go.mod requires a newer toolchain than the one libbox needed.
$goRoot = Join-Path $tc "go126"
if (-not (Test-Path (Join-Path $goRoot "bin\go.exe"))) {
    $ver = (Invoke-WebRequest "https://go.dev/VERSION?m=text" -UseBasicParsing).Content.Split("`n")[0].Trim()
    $zip = Join-Path $tc "$ver.windows-amd64.zip"
    Write-Host "Downloading $ver ..." -ForegroundColor Cyan
    Invoke-WebRequest "https://go.dev/dl/$ver.windows-amd64.zip" -OutFile $zip -UseBasicParsing
    Expand-Archive $zip -DestinationPath (Join-Path $tc "go126tmp") -Force
    Move-Item (Join-Path $tc "go126tmp\go") $goRoot
    Remove-Item $zip, (Join-Path $tc "go126tmp") -Recurse -Force -ErrorAction SilentlyContinue
}

$go = Join-Path $goRoot "bin\go.exe"

$env:GOPATH           = $gopath
$env:GOROOT           = $goRoot
$env:JAVA_HOME        = $jdk
$env:ANDROID_HOME     = $sdk
$env:ANDROID_NDK_HOME = $ndk
$env:CGO_ENABLED      = "1"
$env:PATH             = "$goRoot\bin;$gopath\bin;$jdk\bin;$env:PATH"

if (-not (Test-Path $work)) {
    git clone --depth 1 https://github.com/2dust/AndroidLibXrayLite.git $work
    if ($LASTEXITCODE -ne 0) { throw "git clone failed" }
}

Push-Location $work
try {
    Write-Host "Preparing gomobile (Go $(& $go version)) ..." -ForegroundColor Cyan
    & $go install golang.org/x/mobile/cmd/gomobile@latest
    & $go install golang.org/x/mobile/cmd/gobind@latest
    & $go get golang.org/x/mobile/bind
    & $go mod tidy
    & "$gopath\bin\gomobile.exe" init

    Write-Host "Building libv2ray.aar (several minutes) ..." -ForegroundColor Cyan
    # -checklinkname=0: xray pulls in wlynxg/anet, which reaches into net.zoneCache through
    # //go:linkname. Go 1.23+ rejects that by default and the link fails with
    # "invalid reference to net.zoneCache". anet is what lets Android 11+ enumerate interfaces
    # at all, so the fix is to allow the linkname, not to drop the dependency.
    & "$gopath\bin\gomobile.exe" bind -target=android/arm64 -androidapi 24 `
        -o libv2ray.aar -trimpath -ldflags="-s -w -checklinkname=0" .
    if ($LASTEXITCODE -ne 0) { throw "gomobile bind failed" }

    $dest = Join-Path $proj "android\app\libs"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item (Join-Path $work "libv2ray.aar") (Join-Path $dest "libv2ray.aar") -Force

    $mb = [math]::Round((Get-Item (Join-Path $work "libv2ray.aar")).Length / 1MB, 1)
    Write-Host "Done: android\app\libs\libv2ray.aar ($mb MB)" -ForegroundColor Green
}
finally { Pop-Location }

<#
    Builds libbox.aar - sing-box compiled for Android with gomobile.

    No official prebuilt AAR exists anywhere, so we compile it from source (which GPL-3.0
    expects us to make possible anyway).

    Hard-won details, do not "clean up" without testing:
      * the toolchain MUST live in a path without spaces - cgo splits the compiler path on the
        first space and then reports "C compiler not found";
      * -gcflags=all=-l is required: the Go linker panics on inlined generics in sing-box
        ("missing func info") otherwise;
      * javac must be on PATH, gomobile shells out to it for the Java stubs;
      * arm64 only - the 32-bit ARM build fails inside gvisor, and arm64 covers ~every
        phone that can run this app.

    Usage:  powershell -ExecutionPolicy Bypass -File android\build-libbox.ps1
#>

$ErrorActionPreference = 'Stop'
$proj = Split-Path $PSScriptRoot -Parent

# Toolchain root: keep it space-free (see note above).
$tc = if ($env:MOON_TOOLCHAIN) { $env:MOON_TOOLCHAIN } else { "C:\moonbuild" }
if (-not (Test-Path $tc)) { throw "toolchain not found: $tc (set MOON_TOOLCHAIN or run the setup)" }

$go   = Join-Path $tc "go\bin\go.exe"
$sdk  = Join-Path $tc "android-sdk"
$ndk  = (Get-ChildItem (Join-Path $sdk "ndk") -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$jdk  = (Get-ChildItem $tc -Directory -Filter "jdk-17*" | Select-Object -First 1).FullName
$gopath = Join-Path $tc "gopath"
$work = Join-Path $tc "singbox-src"

foreach ($p in @($go, $ndk, $jdk)) { if (-not (Test-Path $p)) { throw "not found: $p" } }

$env:GOPATH           = $gopath
$env:JAVA_HOME        = $jdk
$env:ANDROID_HOME     = $sdk
$env:ANDROID_NDK_HOME = $ndk
$env:CGO_ENABLED      = "1"
$env:PATH             = "$(Split-Path $go);$gopath\bin;$jdk\bin;$env:PATH"

$TAG = "v1.11.15"

if (-not (Test-Path $work)) {
    Write-Host "Cloning sing-box $TAG ..." -ForegroundColor Cyan
    git clone --depth 1 --branch $TAG https://github.com/SagerNet/sing-box.git $work
    if ($LASTEXITCODE -ne 0) { throw "git clone failed" }
}

Push-Location $work
try {
    Write-Host "Preparing gomobile ..." -ForegroundColor Cyan
    & $go install golang.org/x/mobile/cmd/gomobile@latest
    & $go install golang.org/x/mobile/cmd/gobind@latest
    & $go get golang.org/x/mobile/bind          # gomobile needs this as a module dependency
    & "$gopath\bin\gomobile.exe" init

    Write-Host "Building libbox.aar (a few minutes) ..." -ForegroundColor Cyan
    $bindArgs = @(
        "bind",
        "-target=android/arm64",
        "-androidapi", "24",
        "-javapkg=io.nekohasekai",
        "-o", "libbox.aar",
        "-tags=with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api",
        "-gcflags=all=-l",
        "./experimental/libbox"
    )
    & "$gopath\bin\gomobile.exe" @bindArgs
    if ($LASTEXITCODE -ne 0) { throw "gomobile bind failed" }

    $aar = Join-Path $work "libbox.aar"
    if (-not (Test-Path $aar)) { throw "AAR was not produced" }

    $dest = Join-Path $proj "android\app\libs"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item $aar (Join-Path $dest "libbox.aar") -Force

    $mb = [math]::Round((Get-Item $aar).Length / 1MB, 1)
    Write-Host ""
    Write-Host "Done: android\app\libs\libbox.aar ($mb MB)" -ForegroundColor Green
}
finally { Pop-Location }

<#
    Builds sing-box for Android as jniLibs\arm64-v8a\libsingbox.so.

    Why at all: Hysteria2 on the phone runs on xray's own implementation, and that one is broken
    under Russian filtering — XTLS/Xray-core#6717, closed as "not planned", while sing-box works
    on the same endpoints. The desktop has always run Hysteria2 on sing-box; this gives the phone
    the same engine. Everything else stays on xray, which sing-box cannot replace (no XHTTP).

    Not gomobile (see build-libbox.ps1, written when sing-box was tried as THE core): a plain
    executable needs no PlatformInterface, and the app already knows how to run one — awgproxy and
    byedpi arrive the same way. Android only unpacks and marks executable what it finds in jniLibs
    and only if it is named lib*.so, hence the name.

    Pure Go, no cgo: an android/arm64 build would need the NDK for nothing, and a linux/arm64
    binary runs on Android as it is. Only the QUIC tag is on — Hysteria2 is all this has to carry.

    Usage:  powershell -ExecutionPolicy Bypass -File android\build-singbox.ps1 [-Tag v1.13.14]
#>
#   The tag is bounded by the Go in the toolchain, not by taste: sing-box 1.13 needs Go 1.24, and
#   asking a 1.23 to build it makes it reach for a newer toolchain over the network — which from
#   here answers 403. 1.11.15 is what this toolchain builds, and its Hysteria2 is the same engine
#   the desktop runs. Raise both together when Go gets updated.
param([string]$Tag = 'v1.11.15')

$ErrorActionPreference = 'Stop'
$proj = Split-Path $PSScriptRoot -Parent

# git and go both narrate on stderr, and under ErrorActionPreference=Stop PowerShell turns every
# such line into a terminating error — a clone that worked perfectly still "fails". Judge them by
# their exit code, which is the only thing they mean by it.
function Invoke-Native {
    param([string]$What, [scriptblock]$Run)
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $said = & $Run 2>&1 | ForEach-Object { "$_" } }
    finally { $ErrorActionPreference = $old }
    if ($LASTEXITCODE -ne 0) {
        # Show what it said. Swallowing the output is how "failed (exit 1)" ends up being all
        # anybody knows about a broken build.
        $said | Select-Object -Last 25 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkYellow }
        throw "$What failed (exit $LASTEXITCODE)"
    }
}

# The development machine keeps its toolchain under C:\moonbuild; CI has Go on PATH and a temp dir.
$tc = if ($env:MOON_TOOLCHAIN) { $env:MOON_TOOLCHAIN }
      elseif (Test-Path 'C:\moonbuild') { 'C:\moonbuild' }
      else { [IO.Path]::GetTempPath() }
$go = if (Test-Path (Join-Path $tc 'go\bin\go.exe')) { Join-Path $tc 'go\bin\go.exe' }
      elseif (Get-Command go -ErrorAction SilentlyContinue) { (Get-Command go).Source }
      else { $null }
if (-not $go) { throw "go not found (set MOON_TOOLCHAIN, or put Go on PATH)" }
Write-Host "Go: $(& $go version)" -ForegroundColor Cyan

# One checkout per tag, never shared. build-libbox.ps1 keeps singbox-src pinned to its own version
# and runs `go get` in it, which edits go.mod in place; re-pointing that tree at another tag means
# forcing a checkout over somebody else's working copy. A directory of our own costs a clone and
# owes nothing to anyone.
$work = Join-Path $tc "singbox-$($Tag.TrimStart('v'))"
if (-not (Test-Path $work)) {
    Write-Host "Cloning sing-box $Tag ..." -ForegroundColor Cyan
    Invoke-Native "clone $Tag" { git clone --depth 1 --branch $Tag https://github.com/SagerNet/sing-box.git $work }
} else {
    Write-Host "Using the existing checkout of $Tag" -ForegroundColor Cyan
}

# Android 11+ will not let an app bind() a NETLINK_ROUTE socket, and sing-box opens one for its
# network monitor whenever it has no platform interface - not only when auto_detect_interface asks
# for it. The monitor fails to start, sing-box treats that as fatal, and Hysteria2 dies with
# "start service: permission denied". It never showed on the Android 10 test phone, where the
# bind is still allowed; it is every phone from Android 11 on.
# Nothing here needs the monitor: it exists to follow interface changes for auto_detect_interface,
# which we never set (our own package is excluded from the tunnel, so its sockets bypass it anyway).
# So it is built only when asked for. A missing monitor is a mode sing-box already runs in - it is
# what happens on a platform where creating one returns ErrInvalid.
#
# An empty branch rather than a narrower condition: the branch's `else` is the platform-interface
# path, and narrowing the condition sent us into it with no platform interface - a nil dereference
# at startup. That was the first attempt at this patch, and it never got past box.New.
$nm = Join-Path (Join-Path $work 'route') 'network.go'
$src = [IO.File]::ReadAllText($nm)
$mark = '// no network monitor unless asked for'
$was = 'if !usePlatformDefaultInterfaceMonitor {'
$now = "if !usePlatformDefaultInterfaceMonitor && !enforceInterfaceMonitor {`n`t`t$mark - see android/build-singbox.ps1`n`t} else if !usePlatformDefaultInterfaceMonitor {"
if ($src.Contains($mark)) {
    Write-Host 'Network monitor patch already in place' -ForegroundColor Cyan
} elseif ($src.Contains($was)) {
    [IO.File]::WriteAllText($nm, $src.Replace($was, $now))
    Write-Host 'Patched: network monitor only with auto_detect_interface' -ForegroundColor Cyan
} else {
    throw 'route/network.go no longer looks as expected - re-check the netlink patch before building'
}

if (Test-Path (Join-Path $tc 'gopath')) { $env:GOPATH = Join-Path $tc 'gopath' }
$env:GOOS        = 'linux'
$env:GOARCH      = 'arm64'
$env:CGO_ENABLED = '0'
# Build with the Go we have. Left to itself it downloads whatever the module asks for, and a
# "403 Forbidden" from the module proxy is a far worse error message than "needs a newer Go".
$env:GOTOOLCHAIN = 'local'

$dest = [IO.Path]::Combine($proj, 'android', 'app', 'src', 'main', 'jniLibs', 'arm64-v8a')
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$out = Join-Path $dest 'libsingbox.so'

Push-Location $work
try {
    Write-Host 'Building (a couple of minutes) ...' -ForegroundColor Cyan
    Invoke-Native 'go build' { & $go build -tags with_quic -trimpath -ldflags '-s -w' -o $out ./cmd/sing-box }
}
finally { Pop-Location }

$mb = [math]::Round((Get-Item $out).Length / 1MB, 1)
Write-Host ""
Write-Host "Done: android\app\src\main\jniLibs\arm64-v8a\libsingbox.so ($mb MB)" -ForegroundColor Green
Write-Host "Check it on a phone:  adb push <file> /data/local/tmp/sb; adb shell 'chmod 755 /data/local/tmp/sb; /data/local/tmp/sb version'" -ForegroundColor DarkGray

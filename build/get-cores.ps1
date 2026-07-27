<#
    Downloads the tunnel engines into cores\.

    They are NOT in the repository: together they weigh ~120 MB and they're
    third-party binaries with their own licenses (see THIRD-PARTY-NOTICES.md).

    Usage:  powershell -ExecutionPolicy Bypass -File build\get-cores.ps1
#>

$ErrorActionPreference = 'Stop'
$root   = Split-Path $PSScriptRoot -Parent
$cores  = Join-Path $root 'cores'
$tmp    = Join-Path $env:TEMP "moon-cores-$(Get-Random)"
New-Item -ItemType Directory -Force -Path $cores, $tmp | Out-Null

function Get-Release([string]$repo, [string]$pattern) {
    $api = "https://api.github.com/repos/$repo/releases/latest"
    $rel = Invoke-RestMethod -Uri $api -Headers @{ 'User-Agent' = 'moon-internet-build' }
    $asset = $rel.assets | Where-Object { $_.name -like $pattern } | Select-Object -First 1
    if (-not $asset) { throw "$repo : asset '$pattern' not found in $($rel.tag_name)" }
    $out = Join-Path $tmp $asset.name
    Write-Host "  $repo $($rel.tag_name) -> $($asset.name)"
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $out -UseBasicParsing
    return $out
}

function Expand-Into([string]$zip, [string]$dest) {
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Expand-Archive -Path $zip -DestinationPath $dest -Force
}

try {
    Write-Host 'Fetching cores...' -ForegroundColor Cyan

    # xray-core — VLESS / VMess / Trojan / Shadowsocks + routing
    $z = Get-Release 'XTLS/Xray-core' 'Xray-windows-64.zip'
    Expand-Into $z (Join-Path $cores 'xray')

    # sing-box — TUN, Hysteria2, WireGuard
    $z = Get-Release 'SagerNet/sing-box' '*windows-amd64.zip'
    $sb = Join-Path $tmp 'sb'; Expand-Into $z $sb
    $exe = Get-ChildItem $sb -Recurse -Filter 'sing-box.exe' | Select-Object -First 1
    New-Item -ItemType Directory -Force -Path (Join-Path $cores 'singbox') | Out-Null
    Copy-Item $exe.FullName (Join-Path $cores 'singbox\sing-box.exe') -Force

    # tun2socks — alternative TUN engine
    $z = Get-Release 'xjasonlyu/tun2socks' 'tun2socks-windows-amd64.zip'
    $t2 = Join-Path $tmp 't2'; Expand-Into $z $t2
    $exe = Get-ChildItem $t2 -Recurse -Filter '*.exe' | Select-Object -First 1
    New-Item -ItemType Directory -Force -Path (Join-Path $cores 'tun2socks') | Out-Null
    Copy-Item $exe.FullName (Join-Path $cores 'tun2socks\tun2socks.exe') -Force

    # wintun.dll — TUN driver both engines load. Ships inside the xray archive.
    $wintun = Get-ChildItem (Join-Path $cores 'xray') -Recurse -Filter 'wintun.dll' | Select-Object -First 1
    if ($wintun) {
        Copy-Item $wintun.FullName (Join-Path $cores 'singbox\wintun.dll')   -Force
        Copy-Item $wintun.FullName (Join-Path $cores 'tun2socks\wintun.dll') -Force
    } else {
        Write-Warning 'wintun.dll not found — grab it from https://www.wintun.net/ and drop it next to each engine'
    }

    Write-Host "`nDone. cores\ is ready:" -ForegroundColor Green
    Get-ChildItem $cores -Recurse -File | Select-Object @{n='file';e={$_.FullName.Substring($cores.Length+1)}},
        @{n='MB';e={[math]::Round($_.Length/1MB,1)}} | Format-Table -AutoSize
}
finally { Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }

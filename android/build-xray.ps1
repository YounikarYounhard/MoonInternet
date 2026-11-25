# Fetch the published upstream Android binding and verify the release asset before replacing the local AAR.
param([string]$Tag = 'v26.11.9')

$ErrorActionPreference = 'Stop'
$headers = @{ 'User-Agent' = 'moon-internet-build'; Accept = 'application/vnd.github+json' }
$token = if ($env:GH_TOKEN) { $env:GH_TOKEN } else { $env:GITHUB_TOKEN }
if ($token) { $headers.Authorization = "Bearer $token" }

$release = Invoke-RestMethod -Uri "https://api.github.com/repos/2dust/AndroidLibXrayLite/releases/tags/$Tag" -Headers $headers
$asset = $release.assets | Where-Object name -eq 'libv2ray.aar'
if (!$asset -or $asset.digest -notmatch '^sha256:[0-9a-f]{64}$') { throw "Missing libv2ray.aar digest for $Tag" }

$target = Join-Path $PSScriptRoot 'app/libs'
New-Item -ItemType Directory -Path $target -Force | Out-Null
$aar = Join-Path $target 'libv2ray.aar'
$expected = $asset.digest.Substring(7)
if ((Test-Path -LiteralPath $aar) -and (Get-FileHash -LiteralPath $aar).Hash -eq $expected) {
    Write-Host "AndroidLibXrayLite $Tag already verified"
    return
}

$download = Join-Path $target "libv2ray-$([Guid]::NewGuid()).tmp"
try {
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $download -UseBasicParsing
    if ((Get-FileHash -LiteralPath $download).Hash -ne $expected) { throw "libv2ray.aar SHA-256 mismatch for $Tag" }
    Move-Item -LiteralPath $download -Destination $aar -Force
    Write-Host "AndroidLibXrayLite $Tag verified"
}
finally {
    if (Test-Path -LiteralPath $download) { Remove-Item -LiteralPath $download }
}

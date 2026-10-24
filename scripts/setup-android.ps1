$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tools = Join-Path $workspace '.tools\android'
New-Item -ItemType Directory -Path $tools -Force | Out-Null
# Official Google SDK archives; checksums recorded from repository2-1.xml on 2026-10-22.
$packages = @(
    @{ Name='platforms-android-36'; Archive='platform-36-ext19_r01.zip'; SHA1='52e240c229cf2ad561ee262ebdfff266e180c585' },
    @{ Name='build-tools-36.0.0'; Archive='build-tools_r36_windows.zip'; SHA1='f16ccffd34de8790dede813a6c7d8e2c11a27b50' },
    @{ Name='platform-tools'; Archive='platform-tools_r37.0.1-win.zip'; SHA1='e03e78b1d80b396f1c3358e31251cb31740e1110' }
)
foreach ($package in $packages) {
    $archive = Join-Path $tools $package.Archive
    if (-not (Test-Path -LiteralPath $archive)) {
        Invoke-WebRequest -Uri ('https://dl.google.com/android/repository/' + $package.Archive) -OutFile $archive -TimeoutSec 240
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA1).Hash -ne $package.SHA1) { throw ('SDK checksum failed: ' + $package.Name) }
    $target = Join-Path $tools $package.Name
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $target -Force
    Write-Output ('SDK ready: ' + $package.Name)
}

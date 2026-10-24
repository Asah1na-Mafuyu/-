param([switch]$SkipDiagnostics)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$project = Join-Path $workspace 'src\JiJianTranslate\JiJianTranslate.csproj'
$version = ([xml](Get-Content -LiteralPath $project -Raw -Encoding utf8)).Project.PropertyGroup.Version
$dist = Join-Path $workspace 'dist'
$publish = Join-Path $dist 'publish'
New-Item -ItemType Directory -Path $publish -Force | Out-Null
$runtimeVersion = ([xml](Get-Content -LiteralPath $project -Raw -Encoding utf8)).Project.PropertyGroup.RuntimeFrameworkVersion
dotnet restore $project -r win-x64 -p:SelfContained=true -p:PublishSingleFile=true --nologo
if ($LASTEXITCODE -ne 0) { throw 'Runtime restore failed.' }
$packages = if ($env:NUGET_PACKAGES) { $env:NUGET_PACKAGES } else { Join-Path $env:USERPROFILE '.nuget\packages' }
$coreRuntime = Join-Path $packages ('microsoft.netcore.app.runtime.win-x64\' + $runtimeVersion)
$desktopRuntime = Join-Path $packages ('microsoft.windowsdesktop.app.runtime.win-x64\' + $runtimeVersion)
$assets = Join-Path $workspace 'src\JiJianTranslate\Assets'
Copy-Item -LiteralPath (Join-Path $coreRuntime 'LICENSE.TXT') -Destination (Join-Path $assets 'DotNet-LICENSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $coreRuntime 'THIRD-PARTY-NOTICES.TXT') -Destination (Join-Path $assets 'DotNet-THIRD-PARTY-NOTICES.txt') -Force
Copy-Item -LiteralPath (Join-Path $desktopRuntime 'LICENSE') -Destination (Join-Path $assets 'WindowsDesktop-LICENSE.txt') -Force
dotnet build $project -c Release --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
if (-not $SkipDiagnostics) {
    $check = Start-Process -FilePath (Join-Path $workspace 'src\JiJianTranslate\bin\Release\net10.0-windows\JiJianTranslate.exe') -ArgumentList @('--diagnostics', ('"' + (Join-Path $workspace '.qa') + '"')) -WindowStyle Hidden -Wait -PassThru
    if ($check.ExitCode -ne 0) { throw 'Diagnostics failed. See .qa/report.txt.' }
}
dotnet publish $project -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -p:DebugType=embedded -o $publish --nologo
if ($LASTEXITCODE -ne 0) { throw 'Publish failed.' }
$portable = Join-Path $dist ('极简翻译-Windows-x64-v' + $version)
New-Item -ItemType Directory -Path $portable -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $publish 'JiJianTranslate.exe') -Destination (Join-Path $portable '极简翻译.exe') -Force
Copy-Item -LiteralPath (Join-Path $workspace '使用说明.md') -Destination $portable -Force
Get-ChildItem -LiteralPath $assets -Filter '*.txt' | Copy-Item -Destination $portable -Force
Copy-Item -LiteralPath (Join-Path $publish 'JiJianTranslate.exe') -Destination (Join-Path $dist ('极简翻译-安装-v' + $version + '.exe')) -Force
$zip = Join-Path $dist ('极简翻译-Windows-x64-v' + $version + '.zip')
Compress-Archive -LiteralPath $portable -DestinationPath $zip -Force
Get-ChildItem -LiteralPath $dist -File | Where-Object { $_.Extension -in @('.exe', '.zip', '.apk') } | Sort-Object Name | Get-FileHash -Algorithm SHA256 | ForEach-Object { $_.Hash + '  ' + [IO.Path]::GetFileName($_.Path) } | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Encoding utf8
Write-Output ('Built: ' + $dist)

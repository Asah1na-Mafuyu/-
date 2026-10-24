param([switch]$SkipDiagnostics)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$project = Join-Path $workspace 'src\JiJianTranslate\JiJianTranslate.csproj'
$dist = Join-Path $workspace 'dist'
$publish = Join-Path $dist 'publish-light-shared'
$version = ([xml](Get-Content -LiteralPath $project -Raw -Encoding utf8)).Project.PropertyGroup.Version
New-Item -ItemType Directory -Path $publish -Force | Out-Null

# Keep the Windows Desktop runtime shared; bundle only this app into one EXE.
# Isolate intermediate files from the self-contained build to avoid stale runtime bundles.
dotnet publish $project -c Release -r win-x64 --self-contained false -p:PublishSelfContained=false -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=false -p:DebugType=embedded --artifacts-path (Join-Path $workspace '.qa\build-light') -o $publish --nologo
if ($LASTEXITCODE -ne 0) { throw 'Lightweight publish failed.' }
$exe = Join-Path $dist ('极简翻译-轻量版-v' + $version + '.exe')
Copy-Item -LiteralPath (Join-Path $publish 'JiJianTranslate.exe') -Destination $exe -Force
if (-not $SkipDiagnostics) {
    $report = Join-Path $workspace '.qa\light'
    $check = Start-Process -FilePath $exe -ArgumentList @('--diagnostics', ('"' + $report + '"')) -WindowStyle Hidden -Wait -PassThru
    if ($check.ExitCode -ne 0) { throw 'Lightweight diagnostics failed. See .qa/light/report.txt.' }
}
Get-ChildItem -LiteralPath $dist -File | Where-Object { $_.Extension -in @('.exe', '.zip', '.apk') } | Sort-Object Name | Get-FileHash -Algorithm SHA256 | ForEach-Object { $_.Hash + '  ' + [IO.Path]::GetFileName($_.Path) } | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Encoding utf8
Write-Output ('Built: ' + $exe)
Write-Output ('Size: ' + (Get-Item -LiteralPath $exe).Length + ' bytes; requires .NET 10 Windows Desktop Runtime (x64).')

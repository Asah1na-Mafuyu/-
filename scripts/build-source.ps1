param([string]$Version)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$project = Join-Path $workspace 'src\JiJianTranslate\JiJianTranslate.csproj'
if (-not $Version) { $Version = ([xml](Get-Content -LiteralPath $project -Raw -Encoding utf8)).Project.PropertyGroup.Version }
$dist = Join-Path $workspace 'dist'
$zip = Join-Path $dist ('极简翻译-源码-v' + $Version + '.zip')
New-Item -ItemType Directory -Path $dist -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
# An explicit allowlist excludes .signing, API data, dist, SDKs, and local QA.
$files = @()
foreach ($folder in @('src','scripts','third_party','docs')) {
    $files += Get-ChildItem -LiteralPath (Join-Path $workspace $folder) -Recurse -File | Where-Object { $_.FullName -notmatch '[\\/](bin|obj|build|__pycache__)[\\/]' }
}
$files += Get-ChildItem -LiteralPath $workspace -File | Where-Object { $_.Extension -eq '.md' -or $_.Name -in @('global.json','.gitignore') }
$stream = [IO.File]::Open($zip,[IO.FileMode]::Create)
try {
    $archive = New-Object IO.Compression.ZipArchive($stream,[IO.Compression.ZipArchiveMode]::Create,$true)
    try { foreach ($file in ($files | Sort-Object FullName -Unique)) {
        $relative = $file.FullName.Substring($workspace.Length + 1).Replace('\','/')
        if ($relative -match '(^|/)(\.signing|\.tools|\.qa|dist)(/|$)' -or $relative -match '\.(p12|pfx|key|dat)$') { throw 'Unexpected private file in source list.' }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.FullName,$relative,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }} finally { $archive.Dispose() }
} finally { $stream.Dispose() }
Get-ChildItem -LiteralPath $dist -File | Where-Object { $_.Extension -in @('.exe','.zip','.apk') } | Sort-Object Name | Get-FileHash -Algorithm SHA256 | ForEach-Object { $_.Hash+'  '+[IO.Path]::GetFileName($_.Path) } | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Encoding utf8
Write-Output ('Source archive: ' + $zip)

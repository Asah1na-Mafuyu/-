param([string]$AndroidSdk)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
# Google's native Windows tools still need an ASCII path. Keep files in the original
# workspace and use a checked junction instead of moving the user's project.
if ($workspace -match '[^\x00-\x7F]') {
    $hash = [Security.Cryptography.SHA256]::Create()
    $suffix = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($workspace))).Replace('-','').Substring(0,12)
    $hash.Dispose()
    $aliasPath = Join-Path ([IO.Path]::GetTempPath()) ('JiJianAndroid-' + $suffix)
    if (Test-Path -LiteralPath $aliasPath) {
        $link = Get-Item -LiteralPath $aliasPath -Force
        if ($link.LinkType -ne 'Junction' -or [IO.Path]::GetFullPath([string]@($link.Target)[0]) -ne $workspace) { throw 'The Android build junction points to an unexpected location.' }
    } else { New-Item -ItemType Junction -Path $aliasPath -Target $workspace | Out-Null }
    $workspace = $aliasPath
}
$source = Join-Path $workspace 'src\android\app\src\main'
$version = '0.3.0'
$versionCode = 3
$build = Join-Path $workspace '.qa\android-build'
$dist = Join-Path $workspace 'dist'
$qa = Join-Path $workspace '.qa\android'
$java = Get-Command java -ErrorAction Stop
$jdk = Split-Path (Split-Path $java.Source -Parent) -Parent
if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin\javac.exe'))) { throw 'A JDK with javac, keytool, and jar is required.' }
$env:JAVA_HOME = $jdk
if ($AndroidSdk) {
    $androidJar = Join-Path $AndroidSdk 'platforms\android-36\android.jar'
    $buildTools = Join-Path $AndroidSdk 'build-tools\36.0.0'
} else {
    $sdk = Join-Path $workspace '.tools\android'
    $androidJar = Join-Path $sdk 'platforms-android-36\android-36-ext19\android.jar'
    $buildTools = Join-Path $sdk 'build-tools-36.0.0\android-16'
    if (-not (Test-Path -LiteralPath $androidJar) -or -not (Test-Path -LiteralPath (Join-Path $buildTools 'aapt2.exe'))) { & (Join-Path $PSScriptRoot 'setup-android.ps1') }
}
$generated = Join-Path $build 'generated'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
foreach ($folder in @($build,$dist,$qa,$generated,$classes,$dex)) { New-Item -ItemType Directory -Path $folder -Force | Out-Null }
$assets = Join-Path $source 'assets'
New-Item -ItemType Directory -Path $assets -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $workspace 'third_party\edge-tts\NOTICE.txt') -Destination (Join-Path $assets 'Edge-NOTICE.txt') -Force
Copy-Item -LiteralPath (Join-Path $workspace 'third_party\edge-tts\LICENSE.txt') -Destination (Join-Path $assets 'Edge-LICENSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $workspace 'third_party\edge-tts\GPL-3.0.txt') -Destination (Join-Path $assets 'Edge-GPL-3.0.txt') -Force
# SDK tools keep the app independent of Gradle, Kotlin, AndroidX, and native libraries.
& (Join-Path $buildTools 'aapt2.exe') compile --dir (Join-Path $source 'res') -o (Join-Path $build 'resources.zip')
if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed.' }
& (Join-Path $buildTools 'aapt2.exe') link -o (Join-Path $build 'unsigned.apk') -I $androidJar --manifest (Join-Path $source 'AndroidManifest.xml') --java $generated --min-sdk-version 29 --target-sdk-version 36 --version-code $versionCode --version-name $version -A $assets (Join-Path $build 'resources.zip')
if ($LASTEXITCODE -ne 0) { throw 'Resource linking failed.' }
$sources = Get-ChildItem -LiteralPath (Join-Path $source 'java'),$generated -Filter '*.java' -Recurse | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }
$list = Join-Path $build 'sources.txt'
[IO.File]::WriteAllLines($list,$sources,(New-Object Text.UTF8Encoding($false)))
& (Join-Path $jdk 'bin\javac.exe') --release 8 -Xlint:-options -encoding UTF-8 -classpath $androidJar -d $classes ('@'+$list)
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
& (Join-Path $jdk 'bin\jar.exe') --create --file (Join-Path $build 'classes.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Class archive failed.' }
& (Join-Path $buildTools 'd8.bat') --release --min-api 29 --lib $androidJar --output $dex (Join-Path $build 'classes.jar')
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed.' }
& (Join-Path $jdk 'bin\jar.exe') --update --file (Join-Path $build 'unsigned.apk') -C $dex classes.dex
if ($LASTEXITCODE -ne 0) { throw 'APK assembly failed.' }
& (Join-Path $buildTools 'zipalign.exe') -P 16 -f 4 (Join-Path $build 'unsigned.apk') (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }

$signing = Join-Path $workspace '.signing'
New-Item -ItemType Directory -Path $signing -Force | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls $signing /inheritance:r /grant:r ($identity + ':(OI)(CI)F') 'SYSTEM:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect the signing directory.' }
$keystore = Join-Path $signing 'android-release.p12'
$credentialsPath = Join-Path $signing 'credentials.json'
if ((Test-Path -LiteralPath $keystore) -ne (Test-Path -LiteralPath $credentialsPath)) { throw 'Signing material is incomplete. Restore the existing key and credentials; do not replace the release key.' }
if (-not (Test-Path -LiteralPath $keystore)) {
    $random = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($random); $rng.Dispose()
    $credentials = @{ Alias='jijian-release'; Password=[Convert]::ToBase64String($random) }
    $credentials | ConvertTo-Json | Set-Content -LiteralPath $credentialsPath -Encoding utf8
} else { $credentials = Get-Content -LiteralPath $credentialsPath -Raw -Encoding utf8 | ConvertFrom-Json }
$env:JIJIAN_ANDROID_STORE_PASS = $credentials.Password
try {
    if (-not (Test-Path -LiteralPath $keystore)) {
        & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -keystore $keystore -storetype PKCS12 -storepass:env JIJIAN_ANDROID_STORE_PASS -keypass:env JIJIAN_ANDROID_STORE_PASS -alias $credentials.Alias -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10950 -dname 'CN=JiJian Translate Personal, OU=Personal, O=JiJian Translate, C=CN'
        if ($LASTEXITCODE -ne 0) { throw 'Release signing key generation failed.' }
    }
    $apk = Join-Path $dist ('极简翻译-Android-v' + $version + '.apk')
    $signed = Join-Path $build 'signed.apk'
    & (Join-Path $buildTools 'apksigner.bat') sign --ks $keystore --ks-key-alias $credentials.Alias --ks-pass env:JIJIAN_ANDROID_STORE_PASS --key-pass env:JIJIAN_ANDROID_STORE_PASS --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $signed (Join-Path $build 'aligned.apk')
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $signed | Set-Content -LiteralPath (Join-Path $qa 'signature.txt') -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    & (Join-Path $buildTools 'zipalign.exe') -c -P 16 4 $signed
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment verification failed.' }
    Copy-Item -LiteralPath $signed -Destination $apk -Force
} finally { Remove-Item Env:JIJIAN_ANDROID_STORE_PASS -ErrorAction SilentlyContinue }
Get-ChildItem -LiteralPath $dist -File | Where-Object { $_.Extension -in @('.exe','.zip','.apk') } | Sort-Object Name | Get-FileHash -Algorithm SHA256 | ForEach-Object { $_.Hash+'  '+[IO.Path]::GetFileName($_.Path) } | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Encoding utf8
Write-Output ('Signed APK: '+$apk)
Write-Output ('Size: '+(Get-Item -LiteralPath $apk).Length+' bytes')

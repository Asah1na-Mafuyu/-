param([string]$Serial='emulator-5580',[switch]$InputChecks)
$ErrorActionPreference='Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Run diagnostics only on an isolated Android emulator.' }
$physical=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$hash=[Security.Cryptography.SHA256]::Create()
$suffix=[BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($physical))).Replace('-','').Substring(0,12)
$hash.Dispose()
$aliasPath=Join-Path ([IO.Path]::GetTempPath()) ('JiJianAndroid-'+$suffix)
$workspace=if(Test-Path -LiteralPath $aliasPath){$aliasPath}else{$physical}
$sdk=Join-Path $workspace '.tools\android'
$tools=Join-Path $sdk 'build-tools-36.0.0\android-16'
$androidJar=Join-Path $sdk 'platforms-android-36\android-36-ext19\android.jar'
$adb=Join-Path $sdk 'platform-tools\platform-tools\adb.exe'
$jdk=Split-Path (Split-Path (Get-Command java).Source -Parent) -Parent
$env:JAVA_HOME=$jdk
$testSource=Join-Path $workspace 'src\android\tests'
$build=Join-Path $workspace '.qa\android-test-build'
$classes=Join-Path $build 'classes'
$dex=Join-Path $build 'dex'
foreach($folder in @($build,$classes,$dex)){New-Item -ItemType Directory -Path $folder -Force|Out-Null}
& (Join-Path $tools 'aapt2.exe') link -o (Join-Path $build 'unsigned.apk') -I $androidJar --manifest (Join-Path $testSource 'AndroidManifest.xml') --min-sdk-version 29 --target-sdk-version 36 --version-code 1 --version-name '0.1.0'
if($LASTEXITCODE -ne 0){throw 'Test manifest failed.'}
$sources=Get-ChildItem -LiteralPath (Join-Path $testSource 'java') -Recurse -Filter '*.java'|ForEach-Object{'"'+$_.FullName.Replace('\','/')+'"'}
$list=Join-Path $build 'sources.txt';[IO.File]::WriteAllLines($list,$sources,(New-Object Text.UTF8Encoding($false)))
$mainClasses=Join-Path $workspace '.qa\android-build\classes.jar'
& (Join-Path $jdk 'bin\javac.exe') --release 8 -Xlint:-options -encoding UTF-8 -classpath ($androidJar+';'+$mainClasses) -d $classes ('@'+$list)
if($LASTEXITCODE -ne 0){throw 'Test Java compilation failed.'}
& (Join-Path $jdk 'bin\jar.exe') --create --file (Join-Path $build 'classes.jar') -C $classes .
& (Join-Path $tools 'd8.bat') --release --min-api 29 --lib $androidJar --classpath $mainClasses --output $dex (Join-Path $build 'classes.jar')
if($LASTEXITCODE -ne 0){throw 'Test DEX compilation failed.'}
& (Join-Path $jdk 'bin\jar.exe') --update --file (Join-Path $build 'unsigned.apk') -C $dex classes.dex
& (Join-Path $tools 'zipalign.exe') -f 4 (Join-Path $build 'unsigned.apk') (Join-Path $build 'aligned.apk')
$credentials=Get-Content -LiteralPath (Join-Path $workspace '.signing\credentials.json') -Raw -Encoding utf8|ConvertFrom-Json
$env:JIJIAN_ANDROID_STORE_PASS=$credentials.Password
try{
    & (Join-Path $tools 'apksigner.bat') sign --ks (Join-Path $workspace '.signing\android-release.p12') --ks-key-alias $credentials.Alias --ks-pass env:JIJIAN_ANDROID_STORE_PASS --key-pass env:JIJIAN_ANDROID_STORE_PASS --v4-signing-enabled false --out (Join-Path $build 'checks.apk') (Join-Path $build 'aligned.apk')
    if($LASTEXITCODE -ne 0){throw 'Test APK signing failed.'}
}finally{Remove-Item Env:JIJIAN_ANDROID_STORE_PASS -ErrorAction SilentlyContinue}
& $adb -s $Serial install -r (Join-Path $workspace '.qa\android-build\signed.apk')
if($LASTEXITCODE -ne 0){throw 'App install failed.'}
& $adb -s $Serial install -r (Join-Path $build 'checks.apk')
if($LASTEXITCODE -ne 0){throw 'Test install failed.'}
$oldVoice=$null
if($InputChecks){
    $oldVoice=(& $adb -s $Serial shell settings get secure voice_recognition_service | Out-String).Trim()
    if($oldVoice -eq 'com.jijian.translate.checks/com.jijian.translate.TestRecognitionService'){$oldVoice='null'}
    & $adb -s $Serial shell pm grant com.jijian.translate android.permission.RECORD_AUDIO
    & $adb -s $Serial shell pm grant com.jijian.translate.checks android.permission.RECORD_AUDIO
    & $adb -s $Serial shell am broadcast -n com.jijian.translate.checks/com.jijian.translate.TestControlReceiver --ez enabled true | Out-Null
    & $adb -s $Serial shell settings put secure voice_recognition_service com.jijian.translate.checks/com.jijian.translate.TestRecognitionService
}
try{
    if($InputChecks){$output=& $adb -s $Serial shell am instrument -w -e inputChecks true com.jijian.translate.checks/com.jijian.translate.AndroidChecks}
    else{$output=& $adb -s $Serial shell am instrument -w com.jijian.translate.checks/com.jijian.translate.AndroidChecks}
}finally{
    if($InputChecks){& $adb -s $Serial shell am broadcast -n com.jijian.translate.checks/com.jijian.translate.TestControlReceiver --ez enabled false | Out-Null;if($oldVoice -and $oldVoice -ne 'null'){& $adb -s $Serial shell settings put secure voice_recognition_service $oldVoice}else{& $adb -s $Serial shell settings delete secure voice_recognition_service | Out-Null}}
}
$reportFile=if($InputChecks){'.qa\android\checks-input.txt'}else{'.qa\android\checks.txt'}
$output|Set-Content -LiteralPath (Join-Path $workspace $reportFile) -Encoding utf8
$output|Write-Output
if($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch '0 failed' -or ($output -join "`n") -match 'FAIL '){throw 'Android diagnostics failed.'}

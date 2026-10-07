param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JdkRoot = $env:JAVA_HOME,
    [string]$BuildDirectory = (Join-Path $PSScriptRoot '../android/app/build/manual'),
    [string]$OutputFile = (Join-Path $PSScriptRoot '../../Yinlian-Android-0.8.0.apk')
)
$ErrorActionPreference = 'Stop'
if (-not $SdkRoot -or -not $JdkRoot) { throw 'Set ANDROID_HOME and JAVA_HOME or pass -SdkRoot and -JdkRoot.' }
$taskBuild = [IO.Path]::GetFullPath($BuildDirectory)
$taskOutput = [IO.Path]::GetFullPath($OutputFile)
$taskApp = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../android/app/src/main'))
$taskBuildTools = Join-Path $SdkRoot 'build-tools/35.0.0'
$taskAndroidJar = Join-Path $SdkRoot 'platforms/android-35/android.jar'
$env:JAVA_HOME = $JdkRoot
New-Item -ItemType Directory -Path $taskBuild -Force | Out-Null
foreach ($taskSub in @('gen','classes','dex')) {
    $taskTarget=[IO.Path]::GetFullPath((Join-Path $taskBuild $taskSub))
    if (-not $taskTarget.StartsWith($taskBuild+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe generated build path' }
    if (Test-Path -LiteralPath $taskTarget) { Remove-Item -LiteralPath $taskTarget -Recurse -Force }
    New-Item -ItemType Directory -Path $taskTarget -Force | Out-Null
}
$taskUtf8 = New-Object System.Text.UTF8Encoding($false)
$taskManifest = [IO.File]::ReadAllText((Join-Path $taskApp 'AndroidManifest.xml')).Replace('<manifest xmlns:', '<manifest package="com.mirrorlink.mobile" xmlns:')
[IO.File]::WriteAllText((Join-Path $taskBuild 'AndroidManifest.xml'), $taskManifest, $taskUtf8)
& (Join-Path $taskBuildTools 'aapt2.exe') compile --dir (Join-Path $taskApp 'res') -o (Join-Path $taskBuild 'resources.zip')
if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed' }
& (Join-Path $taskBuildTools 'aapt2.exe') link -o (Join-Path $taskBuild 'unsigned.apk') -I $taskAndroidJar --manifest (Join-Path $taskBuild 'AndroidManifest.xml') --java (Join-Path $taskBuild 'gen') --min-sdk-version 26 --target-sdk-version 35 --version-code 8 --version-name '0.8.0' --auto-add-overlay -A (Join-Path $taskApp 'assets') -R (Join-Path $taskBuild 'resources.zip')
if ($LASTEXITCODE -ne 0) { throw 'Resource linking failed' }
$taskLibraries = @(Get-ChildItem -LiteralPath (Join-Path $taskApp '../../libs') -Filter '*.jar' | ForEach-Object { $_.FullName })
$taskClasspath = ($taskAndroidJar + ';' + ($taskLibraries -join ';'))
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $taskApp 'java'),(Join-Path $taskBuild 'gen') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& (Join-Path $JdkRoot 'bin/javac.exe') -encoding UTF-8 -source 17 -target 17 -classpath $taskClasspath -d (Join-Path $taskBuild 'classes') $taskSources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
$taskClasses = @(Get-ChildItem -LiteralPath (Join-Path $taskBuild 'classes') -Recurse -Filter '*.class' | ForEach-Object { $_.FullName })
& (Join-Path $JdkRoot 'bin/java.exe') -cp (Join-Path $taskBuildTools 'lib/d8.jar') com.android.tools.r8.D8 --release --min-api 26 --lib $taskAndroidJar --output (Join-Path $taskBuild 'dex') $taskClasses $taskLibraries
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$taskZip = [IO.Compression.ZipFile]::Open((Join-Path $taskBuild 'unsigned.apk'), [IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($taskDex in (Get-ChildItem -LiteralPath (Join-Path $taskBuild 'dex') -Filter '*.dex')) {
        $taskOld = $taskZip.GetEntry($taskDex.Name)
        if ($taskOld) { $taskOld.Delete() }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZip, $taskDex.FullName, $taskDex.Name) | Out-Null
    }
} finally { $taskZip.Dispose() }
& (Join-Path $taskBuildTools 'zipalign.exe') -f 4 (Join-Path $taskBuild 'unsigned.apk') (Join-Path $taskBuild 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
$taskKey = Join-Path $taskBuild 'debug.keystore'
if (-not (Test-Path -LiteralPath $taskKey)) {
    & (Join-Path $JdkRoot 'bin/keytool.exe') -genkeypair -keystore $taskKey -storepass android -keypass android -alias mirrorlink -dname 'CN=MirrorLink Debug' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw 'Debug signing key creation failed' }
}
New-Item -ItemType Directory -Path (Split-Path $taskOutput) -Force | Out-Null
& (Join-Path $taskBuildTools 'apksigner.bat') sign --ks $taskKey --ks-key-alias mirrorlink --ks-pass pass:android --key-pass pass:android --out $taskOutput (Join-Path $taskBuild 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
& (Join-Path $taskBuildTools 'apksigner.bat') verify --verbose $taskOutput
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
Write-Output "Android APK created: $taskOutput"

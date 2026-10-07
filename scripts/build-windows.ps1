param([string]$NodeExecutable = (Get-Command node -ErrorAction Stop).Source)
$ErrorActionPreference = 'Stop'
$taskProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskOutput = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../Yinlian-Windows-0.8.0'))
$taskName=[string][char]0x541f+[string][char]0x94fe
$taskExe=Join-Path $taskOutput ($taskName+'.exe')
$taskCompiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (-not (Test-Path -LiteralPath $taskCompiler)) { throw '.NET Framework C# compiler is unavailable. Use npm run build:win for Electron instead.' }
New-Item -ItemType Directory -Path $taskOutput,(Join-Path $taskOutput 'runtime'),(Join-Path $taskOutput 'desktop/ui'),(Join-Path $taskOutput 'branding') -Force | Out-Null
& $taskCompiler /nologo /target:winexe /optimize+ /codepage:65001 /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Management.dll "/out:$taskExe" "/win32icon:$taskProject/desktop/branding/yinlian.ico" (Join-Path $taskProject 'desktop/native/Launcher.cs')
if ($LASTEXITCODE -ne 0) { throw 'Windows launcher compilation failed' }
Copy-Item -LiteralPath (Join-Path $taskProject 'desktop/branding/yinlian.ico') -Destination (Join-Path $taskOutput 'branding/yinlian.ico')
Copy-Item -LiteralPath $NodeExecutable -Destination (Join-Path $taskOutput 'runtime/node.exe')
$taskLicense = Join-Path (Split-Path $NodeExecutable) 'LICENSE'
if (Test-Path -LiteralPath $taskLicense) { Copy-Item -LiteralPath $taskLicense -Destination (Join-Path $taskOutput 'runtime/LICENSE-Node.txt') }
else { throw 'The Node.js distribution LICENSE is required for redistribution.' }
Copy-Item -LiteralPath (Join-Path $taskProject 'desktop/settings.cjs') -Destination (Join-Path $taskOutput 'desktop/settings.cjs')
Copy-Item -LiteralPath (Join-Path $taskProject 'desktop/server.cjs') -Destination (Join-Path $taskOutput 'desktop/server.cjs')
foreach($taskFile in @('video-hub.cjs','discovery.cjs')){Copy-Item -LiteralPath (Join-Path $taskProject ('desktop/'+$taskFile)) -Destination (Join-Path $taskOutput ('desktop/'+$taskFile))}
New-Item -ItemType Directory -Path (Join-Path $taskOutput 'desktop/vendor') -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $taskProject 'desktop/vendor') -File | Copy-Item -Destination (Join-Path $taskOutput 'desktop/vendor')
Get-ChildItem -LiteralPath (Join-Path $taskProject 'desktop/ui') -File | Copy-Item -Destination (Join-Path $taskOutput 'desktop/ui')
Get-ChildItem -LiteralPath $taskProject -Filter '*.md' -File | Copy-Item -Destination $taskOutput
Compress-Archive -Path (Join-Path $taskOutput '*') -DestinationPath (Join-Path (Split-Path $taskOutput) 'Yinlian-Windows-0.8.0.zip') -Force
Write-Output "Windows client created: $taskExe"

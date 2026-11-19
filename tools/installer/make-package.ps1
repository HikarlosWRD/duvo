# Monta o pacote do host (dist\Duvo-host-X.zip) a partir do host compilado (host\build). Baixa o scrcpy+adb
# oficiais conferindo o hash de pinned.json. Escreve dist\SHA256SUMS.txt (junte a linha do APK ao publicar).
param([string]$Version = '0.1.0', [string]$SudoVda = '', [switch]$Setup)   # -SudoVda: pasta com SudoVDA.dll/.inf/.cat/.cer/install.bat/nefconc.exe (do pacote oficial do Apollo)
$ErrorActionPreference = 'Stop'
$root  = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$build = Join-Path $root 'host\build'
$dist  = Join-Path $root 'dist'
$stage = Join-Path $dist 'stage'
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage, "$stage\tools", "$stage\licenses" | Out-Null
Copy-Item "$build\sunshine.exe" $stage
Get-ChildItem $build -Filter *.dll | Copy-Item -Destination $stage
Copy-Item "$build\assets" "$stage\assets" -Recurse
Copy-Item "$build\sunshinesvc.exe" "$stage\tools" -ErrorAction SilentlyContinue
$pin = Get-Content "$PSScriptRoot\pinned.json" -Raw | ConvertFrom-Json
foreach ($d in $pin.downloads) {
    $zip = Join-Path $env:TEMP $d.name
    if (-not (Test-Path $zip)) { Invoke-WebRequest $d.url -OutFile $zip -UseBasicParsing }
    if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLower() -ne $d.sha256.ToLower()) { throw "hash diferente para $($d.name)" }
    $x = Join-Path $env:TEMP 'duvo-x'; Remove-Item $x -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive $zip $x -Force
    Copy-Item (Join-Path (Get-ChildItem $x -Directory | Select-Object -First 1).FullName '*') "$stage\tools" -Recurse -Force
}
if ($SudoVda) { Copy-Item $SudoVda "$stage\sudovda" -Recurse }
Copy-Item "$root\tools\host\Duvo.ps1", "$root\tools\host\Duvo-Update.ps1", "$root\tools\host\duvo.ico" $stage
Copy-Item "$PSScriptRoot\Install-Duvo.ps1", "$PSScriptRoot\apply-update.ps1", "$PSScriptRoot\install-drivers.ps1", "$PSScriptRoot\pinned.json" $stage
Copy-Item "$root\LICENSE", "$root\NOTICE" $stage
Copy-Item "$root\docs\licenses\*" "$stage\licenses"
Set-Content "$stage\version.txt" $Version -Encoding ASCII
$out = Join-Path $dist "Duvo-host-$Version.zip"
Remove-Item $out -ErrorAction SilentlyContinue
Compress-Archive -Path "$stage\*" -DestinationPath $out
$h = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
Set-Content (Join-Path $dist 'SHA256SUMS.txt') "$h  Duvo-host-$Version.zip" -Encoding ASCII
if ($Setup) {   # instalador de duplo clique (Inno Setup 6)
    $iscc = (Get-ChildItem "$env:LOCALAPPDATA\Programs\Inno Setup 6", 'C:\Program Files (x86)\Inno Setup 6' -Filter ISCC.exe -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
    if (-not $iscc) { throw 'Inno Setup 6 nao encontrado (winget install JRSoftware.InnoSetup).' }
    $env:DUVO_VERSION = $Version; $env:DUVO_STAGE = $stage; $env:DUVO_OUT = $dist
    & $iscc /Qp "$PSScriptRoot\duvo.iss"
    if ($LASTEXITCODE -ne 0) { throw 'iscc falhou.' }
    $setupExe = Join-Path $dist "Duvo-Setup-$Version.exe"
    Add-Content (Join-Path $dist 'SHA256SUMS.txt') "$((Get-FileHash $setupExe -Algorithm SHA256).Hash.ToLower())  Duvo-Setup-$Version.exe" -Encoding ASCII
}
Write-Host "$out  $h"

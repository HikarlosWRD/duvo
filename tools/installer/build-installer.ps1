# Monta a pasta de instalacao e compila o instalador. Requisitos: host compilado (host\build), Inno Setup 6 (iscc).
# Baixa adb (platform-tools) e scrcpy das fontes oficiais; confira os hashes em tools\installer\pinned.json.
param([string]$Version = '0.1.0')
$ErrorActionPreference = 'Stop'
$root  = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$build = Join-Path $root 'host\build'
$stage = Join-Path $root 'dist\stage'
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage, "$stage\tools" | Out-Null
# host: executavel, DLLs e assets
Copy-Item "$build\sunshine.exe" $stage
Get-ChildItem $build -Filter *.dll | Copy-Item -Destination $stage
Copy-Item "$build\assets" "$stage\assets" -Recurse
Copy-Item "$build\tools\*" "$stage\tools" -Recurse -ErrorAction SilentlyContinue   # sunshinesvc, ddprobe etc., se existirem
Copy-Item "$root\tools\host\Duvo.ps1", "$root\tools\host\duvo.ico", "$PSScriptRoot\install-drivers.ps1", "$PSScriptRoot\pinned.json" $stage
# adb e scrcpy (oficiais)
$pin = Get-Content "$PSScriptRoot\pinned.json" -Raw | ConvertFrom-Json
foreach ($d in $pin.downloads) {
    $zip = Join-Path $env:TEMP $d.name
    Invoke-WebRequest $d.url -OutFile $zip
    if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLower() -ne $d.sha256.ToLower()) { throw "hash diferente para $($d.name)" }
    Expand-Archive $zip "$stage\_x" -Force
    Copy-Item "$stage\_x\*\*" "$stage\tools" -Recurse -Force
    Remove-Item "$stage\_x" -Recurse -Force
}
$env:DUVO_VERSION = $Version; $env:DUVO_STAGE = $stage
& iscc "$PSScriptRoot\duvo.iss"

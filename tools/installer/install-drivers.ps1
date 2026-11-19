# Instala os drivers do Duvo (rodar elevado): ViGEmBus (controle virtual) e, se a pasta sudovda existir, o monitor virtual SudoVDA.
$ErrorActionPreference = 'Stop'
$pin = Get-Content (Join-Path $PSScriptRoot 'pinned.json') -Raw | ConvertFrom-Json
if (-not (Get-Service ViGEmBus -ErrorAction SilentlyContinue)) {
    $exe = Join-Path $env:TEMP 'ViGEmBus-setup.exe'
    Invoke-WebRequest $pin.vigembus.url -OutFile $exe
    if ((Get-FileHash $exe -Algorithm SHA256).Hash.ToLower() -ne $pin.vigembus.sha256) { throw 'ViGEmBus: hash diferente, instalacao cancelada.' }
    Start-Process $exe -ArgumentList '/qn' -Wait
}
$sv = Join-Path $PSScriptRoot 'sudovda\install.bat'
if (Test-Path $sv) { Start-Process $sv -WorkingDirectory (Split-Path $sv) -Wait }

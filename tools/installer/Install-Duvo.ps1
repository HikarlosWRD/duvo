# Duvo - instalacao do host a partir do pacote Duvo-host-X.zip (GPLv3). Sem administrador, exceto para os drivers.
# Uso: extraia o zip e rode  powershell -ExecutionPolicy Bypass -File Install-Duvo.ps1
param(
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'Programs\Duvo'),
    [switch]$Drivers,        # instala ViGEmBus (controle virtual) e SudoVDA (monitor virtual): pede permissao de administrador
    [switch]$NoShortcuts,
    [switch]$Autostart,      # abre o Duvo ao iniciar o Windows
    [switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
New-Item -ItemType Directory -Force $InstallDir, (Join-Path $InstallDir 'duvo-pc') | Out-Null
robocopy $here $InstallDir /E /XD duvo-pc config /XF Install-Duvo.ps1 apply-update.ps1 /R:2 /W:1 /NFL /NDL /NJH /NJS | Out-Null
if ($LASTEXITCODE -ge 8) { throw "Falha ao copiar os arquivos (robocopy $LASTEXITCODE)." }
Copy-Item (Join-Path $here 'apply-update.ps1') $InstallDir -Force   # o proprio pacote traz o aplicador; fica a copia mais nova

$conf = Join-Path $InstallDir 'duvo-pc\duvo.conf'
if (-not (Test-Path $conf)) {
    Set-Content $conf -Value "sunshine_name = Duvo`r`nsystem_tray = disabled`r`nlocale = pt_BR`r`n" -Encoding ASCII
}

$ps = (Get-Command powershell.exe).Source
$launch = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$(Join-Path $InstallDir 'Duvo.ps1')`""
$ico = Join-Path $InstallDir 'duvo.ico'
function New-Shortcut([string]$path) {
    $s = (New-Object -ComObject WScript.Shell).CreateShortcut($path)
    $s.TargetPath = $ps; $s.Arguments = $launch; $s.WorkingDirectory = $InstallDir; $s.IconLocation = $ico; $s.Save()
}
if (-not $NoShortcuts) {
    $menu = Join-Path ([Environment]::GetFolderPath('Programs')) 'Duvo.lnk'
    New-Shortcut $menu
    New-Shortcut (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Duvo.lnk')
    if ($Autostart) { New-Shortcut (Join-Path ([Environment]::GetFolderPath('Startup')) 'Duvo.lnk') }
}
if ($Drivers) {
    Start-Process powershell.exe -Verb RunAs -Wait -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$(Join-Path $InstallDir 'install-drivers.ps1')`""
}
Write-Host "Duvo instalado em $InstallDir"
if (-not $NoLaunch) { Start-Process $ps -ArgumentList $launch }

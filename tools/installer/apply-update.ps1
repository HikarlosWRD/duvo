# Duvo - aplica uma atualizacao do host (GPLv3). Vem dentro do pacote novo e roda de uma pasta temporaria.
# Espera o Duvo.ps1 antigo sair, para o host, copia os arquivos por cima (sem tocar em duvo-pc, onde ficam os
# pareamentos e as configuracoes) e abre o Duvo de novo.
param([Parameter(Mandatory)][string]$Target, [Parameter(Mandatory)][string]$Source, [int]$WaitPid = 0, [switch]$NoRelaunch)
$ErrorActionPreference = 'Stop'
if ($WaitPid -gt 0) { Wait-Process -Id $WaitPid -Timeout 30 -ErrorAction SilentlyContinue }
Get-CimInstance Win32_Process -Filter "Name='sunshine.exe'" | Where-Object { $_.ExecutablePath -and $_.ExecutablePath -like "$Target*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2
robocopy $Source $Target /E /XD duvo-pc config /XF apply-update.ps1 /R:3 /W:2 /NFL /NDL /NJH /NJS | Out-Null
if ($LASTEXITCODE -ge 8) { throw "Falha ao copiar os arquivos (robocopy $LASTEXITCODE)." }
Remove-Item (Split-Path $Source -Parent) -Recurse -Force -ErrorAction SilentlyContinue
if (-not $NoRelaunch) {
    Start-Process powershell.exe -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', "`"$(Join-Path $Target 'Duvo.ps1')`""
}

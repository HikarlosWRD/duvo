# Duvo para Windows: icone na bandeja, janela de app com o painel (Aparelhos, QR, configuracoes) e o host em segundo plano.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing, Microsoft.VisualBasic
$installed = Test-Path (Join-Path $PSScriptRoot 'sunshine.exe')   # instalado: tudo na mesma pasta; no repositorio: host\build
$root   = if ($installed) { $PSScriptRoot } else { (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path }
$build  = if ($installed) { $PSScriptRoot } else { Join-Path $root 'host\build' }
$conf   = 'duvo-pc\duvo.conf'
$icon   = Join-Path $PSScriptRoot 'duvo.ico'
$url    = 'https://localhost:40990/pin'
$data   = Join-Path $env:LOCALAPPDATA 'Duvo\janela'
$certRel = 'duvo-pc\cert.pem'   # gerado pelo host na primeira execucao; o hash e calculado por instalacao
function Get-CertSpkiHash([string]$pem) {
    for ($i = 0; $i -lt 40 -and -not (Test-Path $pem); $i++) { Start-Sleep -Milliseconds 500 }   # primeira execucao: o host ainda esta criando o certificado
    $b64 = ((Get-Content $pem -Raw) -replace '-----[A-Z ]+-----','' -replace '\s','')
    $der = [Convert]::FromBase64String($b64)
    function Read-Tlv($d, [int]$pos) {   # returns tag, header length, content length
        $tag = $d[$pos]; $l = $d[$pos+1]; $h = 2
        if ($l -ge 0x80) { $n = $l - 0x80; $l = 0; for ($i=0; $i -lt $n; $i++) { $l = ($l -shl 8) + $d[$pos+2+$i] }; $h = 2 + $n }
        return @($tag, $h, $l)
    }
    $c = Read-Tlv $der 0; $pos = $c[1]                  # Certificate
    $t = Read-Tlv $der $pos; $pos += $t[1]              # TBSCertificate: walk its fields
    $f = Read-Tlv $der $pos
    if ($f[0] -eq 0xA0) { $pos += $f[1] + $f[2] }       # optional version
    for ($i = 0; $i -lt 5; $i++) { $f = Read-Tlv $der $pos; $pos += $f[1] + $f[2] }   # serial, sigalg, issuer, validity, subject
    $f = Read-Tlv $der $pos
    $spki = $der[$pos..($pos + $f[1] + $f[2] - 1)]
    [Convert]::ToBase64String([Security.Cryptography.SHA256]::Create().ComputeHash([byte[]]$spki))
}
function Get-WinArgs { return "--app=$url --user-data-dir=`"$data`" --ignore-certificate-errors-spki-list=$(Get-CertSpkiHash (Join-Path $build $certRel)) --test-type --no-first-run --no-default-browser-check --disable-sync --disable-features=Translate,msEdgeSync,msEdgeSignInPrompt --lang=pt-BR --window-size=1280,820" }
$edge   = Get-ChildItem 'C:\Program Files (x86)\Microsoft\EdgeCore' -Filter msedge.exe -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName

$mutex = New-Object System.Threading.Mutex($false, 'Local\DuvoTray')
if (-not $mutex.WaitOne(0)) {   # ja esta aberto: so mostra a janela
    if ($edge) { Start-Process $edge (Get-WinArgs) }
    return
}

function Get-Host-Proc { Get-CimInstance Win32_Process -Filter "Name='sunshine.exe'" | Where-Object { $_.CommandLine -like '*duvo-pc*' } }
function Ensure-Conf {   # so o icone do Duvo fica na bandeja (o do host fica desligado)
    $c = Join-Path $build $conf
    if ((Test-Path $c) -and -not (Select-String -Path $c -Pattern '^\s*system_tray' -Quiet)) { Add-Content -Path $c -Value 'system_tray = disabled' }
}
function Start-DuvoHost {
    if (-not (Get-Host-Proc)) {
        Start-Process (Join-Path $build 'sunshine.exe') -ArgumentList $conf -WorkingDirectory $build -WindowStyle Hidden
    }
}
Add-Type -Name WI -Namespace U6 -MemberDefinition '[DllImport("user32.dll")] public static extern System.IntPtr SendMessage(System.IntPtr h, int m, System.IntPtr w, System.IntPtr l);'
$icoSmall = New-Object System.Drawing.Icon($icon, 16, 16)
$icoBig   = New-Object System.Drawing.Icon($icon, 48, 48)
function Set-WindowIcon {   # titulo da janela e barra de tarefas: icone do Duvo no lugar do globo do Edge
    for ($i = 0; $i -lt 10; $i++) {
        $w = Get-Process msedge -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -match 'Duvo' }
        foreach ($p in $w) {
            [void][U6.WI]::SendMessage($p.MainWindowHandle, 0x80, [IntPtr]0, $icoSmall.Handle)
            [void][U6.WI]::SendMessage($p.MainWindowHandle, 0x80, [IntPtr]1, $icoBig.Handle)
        }
        if ($w) { Start-Sleep -Milliseconds 1500; continue }   # reaplica algumas vezes: o Edge troca o icone ao carregar a pagina
        Start-Sleep -Milliseconds 500
    }
}
function Focus-Window { Start-Sleep -Milliseconds 1800; try { [Microsoft.VisualBasic.Interaction]::AppActivate('Duvo') } catch {} }
function Open-Window {
    if (-not $edge) { Start-Process $url; return }
    Start-Process $edge (Get-WinArgs)
    Focus-Window
    Set-WindowIcon
}

Ensure-Conf
Start-DuvoHost
$tray = New-Object System.Windows.Forms.NotifyIcon
$tray.Icon = New-Object System.Drawing.Icon($icon)
$tray.Text = 'Duvo'
$menu = New-Object System.Windows.Forms.ContextMenuStrip
[void]$menu.Items.Add('Abrir o Duvo', $null, { Open-Window })
[void]$menu.Items.Add('Parear um celular', $null, { Open-Window })
[void]$menu.Items.Add('-')
[void]$menu.Items.Add('Sair do Duvo', $null, {
    $tray.Visible = $false
    Get-Host-Proc | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    [System.Windows.Forms.Application]::Exit()
})
$tray.ContextMenuStrip = $menu
$tray.add_DoubleClick({ Open-Window })
$tray.Visible = $true
Start-Sleep -Seconds 14   # o host leva alguns segundos para abrir as portas
Open-Window
[System.Windows.Forms.Application]::Run()


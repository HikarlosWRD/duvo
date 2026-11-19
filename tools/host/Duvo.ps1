# Duvo para Windows: icone na bandeja, janela de app com o painel (Aparelhos, QR, configuracoes) e o host em segundo plano.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing, Microsoft.VisualBasic
$root   = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$build  = Join-Path $root 'host\build'
$conf   = 'duvo-pc\duvo.conf'
$icon   = Join-Path $PSScriptRoot 'duvo.ico'
$url    = 'https://localhost:40990/pin'
$spki   = 'roWkxapWuI+P0ONlQR5JNugqKP9R1NaS7KkOPv9Yhro='   # chave publica do certificado do host (so este PC)
$data   = Join-Path $env:LOCALAPPDATA 'Duvo\janela'
$winArgs = "--app=$url --user-data-dir=`"$data`" --ignore-certificate-errors-spki-list=$spki --test-type --no-first-run --no-default-browser-check --disable-sync --disable-features=Translate,msEdgeSync,msEdgeSignInPrompt --lang=pt-BR --window-size=1280,820"
$edge   = Get-ChildItem 'C:\Program Files (x86)\Microsoft\EdgeCore' -Filter msedge.exe -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName

$mutex = New-Object System.Threading.Mutex($false, 'Local\DuvoTray')
if (-not $mutex.WaitOne(0)) {   # ja esta aberto: so mostra a janela
    if ($edge) { Start-Process $edge $winArgs }
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
    Start-Process $edge $winArgs
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


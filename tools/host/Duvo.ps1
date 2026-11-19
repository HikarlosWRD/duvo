# Duvo para Windows: icone na bandeja, janela de app com o painel (Aparelhos, QR, configuracoes) e o host em segundo plano.
$ErrorActionPreference = 'Stop'
trap { try { Add-Content (Join-Path $env:TEMP 'duvo-tray-crash.log') (($_ | Out-String)) } catch {}; break }
Add-Type -AssemblyName System.Windows.Forms, System.Drawing, Microsoft.VisualBasic
$installed = Test-Path (Join-Path $PSScriptRoot 'sunshine.exe')   # instalado: tudo na mesma pasta; no repositorio: host\build
$root   = if ($installed) { $PSScriptRoot } else { (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path }
$build  = if ($installed) { $PSScriptRoot } else { Join-Path $root 'host\build' }
$conf   = 'duvo-pc\duvo.conf'
$icon   = Join-Path $PSScriptRoot 'duvo.ico'
$url    = 'https://localhost:40990/pin'
$data   = Join-Path $env:LOCALAPPDATA 'Duvo\janela'
function Get-CertPath {   # certificado do painel: o "cert =" do duvo.conf, ou o padrao do host (config\credentials\cacert.pem)
    $c = Join-Path $build $conf
    if (Test-Path $c) {
        $m = Select-String -Path $c -Pattern '^\s*cert\s*=\s*(.+?)\s*$' | Select-Object -First 1
        if ($m) { $v = $m.Matches[0].Groups[1].Value; return $(if ([IO.Path]::IsPathRooted($v)) { $v } else { Join-Path $build $v }) }
    }
    return (Join-Path $build 'config\credentials\cacert.pem')
}
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
function Get-WinArgs { return "--app=$url --user-data-dir=`"$data`" --ignore-certificate-errors-spki-list=$(Get-CertSpkiHash (Get-CertPath)) --test-type --no-first-run --no-default-browser-check --disable-sync --disable-features=Translate,msEdgeSync,msEdgeSignInPrompt --lang=pt-BR --window-size=1280,820" }
$edge   = Get-ChildItem 'C:\Program Files (x86)\Microsoft\EdgeCore' -Filter msedge.exe -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName

function Write-DuvoLog($e) {
    try {
        $d = Join-Path $env:LOCALAPPDATA 'Duvo'
        New-Item -ItemType Directory -Force $d | Out-Null
        Add-Content (Join-Path $d 'tray.log') ("{0} {1}" -f (Get-Date -Format s), ($e | Out-String).Trim())
    } catch {}
}
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

. (Join-Path $PSScriptRoot 'Duvo-Update.ps1')
$script:pendingUpdate = $null
function Invoke-UpdateCheck([bool]$manual) {   # so instalado (version.txt): na pasta do repositorio nao atualiza sozinho
    $cur = Get-InstalledVersion $build
    if (-not $cur) { if ($manual) { [void][System.Windows.Forms.MessageBox]::Show('Esta copia do Duvo nao foi instalada pelo pacote; nao ha atualizacao automatica.', 'Duvo') }; return }
    $r = Get-LatestRelease
    if (-not $r) { if ($manual) { [void][System.Windows.Forms.MessageBox]::Show('Nao consegui verificar agora. Confira a internet e tente de novo.', 'Duvo') }; return }
    if (-not (Test-VersionNewer $cur $r.Version)) { if ($manual) { [void][System.Windows.Forms.MessageBox]::Show("Voce ja esta na versao mais recente ($cur).", 'Duvo') }; return }
    $skip = Join-Path $env:LOCALAPPDATA 'Duvo\update-skipped.txt'
    if (-not $manual -and (Test-Path $skip) -and ((Get-Content $skip -Raw).Trim() -eq $r.Version)) { return }
    $ans = [System.Windows.Forms.MessageBox]::Show("Duvo $($r.Version) esta disponivel (voce tem $cur).`n`nBaixar e atualizar agora? O pacote e verificado (SHA-256) antes de instalar e o Duvo reabre sozinho.", 'Atualizar o Duvo', 'YesNoCancel')
    if ($ans -eq 'Yes') {
        try {
            Start-DuvoUpdate $r $build
            $tray.Visible = $false
            [System.Windows.Forms.Application]::Exit()
            exit
        } catch { [void][System.Windows.Forms.MessageBox]::Show("Nao foi possivel atualizar: $($_.Exception.Message)", 'Duvo') }
    } elseif ($ans -eq 'Cancel') {
        New-Item -ItemType Directory -Force (Split-Path $skip) | Out-Null
        Set-Content $skip $r.Version -Encoding ASCII
    }
}
Ensure-Conf
Start-DuvoHost
$tray = New-Object System.Windows.Forms.NotifyIcon
$tray.Icon = New-Object System.Drawing.Icon($icon)
$tray.Text = 'Duvo'
$menu = New-Object System.Windows.Forms.ContextMenuStrip
[void]$menu.Items.Add('Abrir o Duvo', $null, { Open-Window })
[void]$menu.Items.Add('Parear um celular', $null, { Open-Window })
[void]$menu.Items.Add('Verificar atualizacao', $null, { Invoke-UpdateCheck $true })
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
$updTimer = New-Object System.Windows.Forms.Timer
$updTimer.Interval = 6 * 60 * 60 * 1000
$updTimer.add_Tick({ Invoke-UpdateCheck $false })
$updTimer.Start()
$first = New-Object System.Windows.Forms.Timer
$first.Interval = 45000
$first.add_Tick({ $first.Stop(); Invoke-UpdateCheck $false })
$first.Start()
# Fechar a janela so fecha o painel: o Duvo continua na bandeja. Avisa uma vez a cada fechamento.
$script:hadWindow = $false
$winTimer = New-Object System.Windows.Forms.Timer
$winTimer.Interval = 2000
$winTimer.add_Tick({
    try {
        $open = @(Get-Process msedge -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -match 'Duvo' }).Count -gt 0
        if ($script:hadWindow -and -not $open) {
            $tray.BalloonTipTitle = 'Duvo continua aberto'
            $tray.BalloonTipText = 'Ele ficou na bandeja do Windows (perto do relogio). Clique no icone para abrir de novo.'
            $tray.ShowBalloonTip(4000)
        }
        $script:hadWindow = $open
    } catch { Write-DuvoLog $_ }
})
$winTimer.Start()
# Um erro em qualquer evento nao pode derrubar o icone da bandeja: registra e volta ao laco de mensagens.
$ErrorActionPreference = 'Continue'
while ($true) {
    try { [System.Windows.Forms.Application]::Run(); break } catch { Write-DuvoLog $_; Start-Sleep -Seconds 1 }
}

# Duvo - atualizacao automatica do host (GPLv3). Carregado por Duvo.ps1 (dot-source).
# Le o ultimo release publico no GitHub, baixa o pacote do host, confere o SHA-256 publicado e entrega a troca
# de arquivos para apply-update.ps1 (que roda fora do Duvo.ps1, porque ele mesmo e substituido).
$DuvoRepo = 'HikarlosWRD/duvo'

function Get-InstalledVersion([string]$dir) {
    $f = Join-Path $dir 'version.txt'
    if (Test-Path $f) { return (Get-Content $f -Raw).Trim() }
    return $null
}

function Test-VersionNewer([string]$current, [string]$candidate) {
    $a = @(($current -split '-')[0] -split '\.' | ForEach-Object { $n = 0; [void][int]::TryParse($_, [ref]$n); $n })
    $b = @(($candidate -split '-')[0] -split '\.' | ForEach-Object { $n = 0; [void][int]::TryParse($_, [ref]$n); $n })
    for ($i = 0; $i -lt [Math]::Max($a.Count, $b.Count); $i++) {
        $x = if ($i -lt $a.Count) { $a[$i] } else { 0 }
        $y = if ($i -lt $b.Count) { $b[$i] } else { 0 }
        if ($y -ne $x) { return ($y -gt $x) }
    }
    return $false
}

# Hash esperado de um arquivo numa listagem do sha256sum ("<hash>  <nome>" ou "<hash> *<nome>").
function Get-ExpectedHash([string]$listing, [string]$file) {
    foreach ($line in ($listing -split "`n")) {
        if ($line.Trim() -match '^([0-9a-fA-F]{64})\s+\*?(.+)$' -and $Matches[2] -eq $file) { return $Matches[1].ToLower() }
    }
    return $null
}

# Ultimo release com pacote do host: @{ Version; PageUrl; ZipUrl; SumsUrl } ou $null (offline, sem release...).
function Get-LatestRelease {
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $r = Invoke-RestMethod "https://api.github.com/repos/$DuvoRepo/releases/latest" -Headers @{ Accept = 'application/vnd.github+json'; 'User-Agent' = 'Duvo-updater' } -TimeoutSec 15
        $zip = $r.assets | Where-Object { $_.name -match '^Duvo-host-.+\.zip$' } | Select-Object -First 1
        $sums = $r.assets | Where-Object { $_.name -eq 'SHA256SUMS.txt' } | Select-Object -First 1
        if (-not $zip -or -not $sums) { return $null }
        return @{ Version = ($r.tag_name -replace '^v', ''); PageUrl = $r.html_url; ZipUrl = $zip.browser_download_url; ZipName = $zip.name; SumsUrl = $sums.browser_download_url }
    } catch { return $null }
}

# Baixa e confere; devolve a pasta com os arquivos extraidos. Lanca excecao se algo nao confere.
function Get-VerifiedPackage($release) {
    $tmp = Join-Path $env:TEMP ('duvo-update-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory $tmp | Out-Null
    $zip = Join-Path $tmp $release.ZipName
    $sums = (Invoke-WebRequest $release.SumsUrl -UseBasicParsing -TimeoutSec 30).Content
    if ($sums -is [byte[]]) { $sums = [Text.Encoding]::UTF8.GetString($sums) }
    $want = Get-ExpectedHash $sums $release.ZipName
    if (-not $want) { throw "SHA256SUMS.txt nao lista $($release.ZipName)." }
    Invoke-WebRequest $release.ZipUrl -OutFile $zip -UseBasicParsing -TimeoutSec 600
    if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLower() -ne $want) { Remove-Item $tmp -Recurse -Force; throw 'O pacote baixado nao confere com o hash publicado. Atualizacao cancelada.' }
    $out = Join-Path $tmp 'pkg'
    Expand-Archive $zip $out -Force
    return $out
}

# Entrega a troca para o apply-update.ps1 do pacote novo e devolve; quem chamou deve encerrar o Duvo.ps1.
function Start-DuvoUpdate($release, [string]$installDir) {
    $pkg = Get-VerifiedPackage $release
    $apply = Join-Path $pkg 'apply-update.ps1'
    if (-not (Test-Path $apply)) { throw 'Pacote sem apply-update.ps1.' }
    Start-Process powershell.exe -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', "`"$apply`"", '-Target', "`"$installDir`"", '-Source', "`"$pkg`"", '-WaitPid', $PID
}

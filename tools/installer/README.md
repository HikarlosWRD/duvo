# Pacote e instalador do host (Windows)

- `make-package.ps1 -Version 0.1.0` monta `dist\Duvo-host-0.1.0.zip` (host compilado, `Duvo.ps1`, atualizador, scrcpy+adb com hash conferido) e `dist\SHA256SUMS.txt`.
- `Install-Duvo.ps1` (dentro do zip) instala em `%LOCALAPPDATA%\Programs\Duvo` sem administrador, cria atalhos e a configuracao inicial; `-Drivers` instala ViGEmBus (e SudoVDA, se a pasta `sudovda` estiver no pacote) pedindo administrador.
- Atualizacao automatica: `Duvo.ps1` consulta o ultimo release no GitHub a cada 6 h (menu da bandeja: "Verificar atualizacao"), baixa `Duvo-host-X.zip`, confere o SHA-256 de `SHA256SUMS.txt` e `apply-update.ps1` troca os arquivos preservando `duvo-pc` (pareamentos e configuracao).

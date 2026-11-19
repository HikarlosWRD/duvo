# Instalador do host (Windows)

`build-installer.ps1 -Version 0.1.0` monta `dist\stage` (host compilado, `Duvo.ps1`, ícone, scrcpy+adb com hash conferido) e chama o Inno Setup (`iscc`). O instalador é por usuário (sem admin) e, se marcado, roda `install-drivers.ps1` elevado (ViGEmBus com hash conferido; SudoVDA se a pasta `sudovda` for incluída no stage, vinda do pacote oficial do Apollo).

Status: scripts escritos, ainda não compilados nem testados numa máquina limpa.

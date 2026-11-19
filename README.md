# Duvo

**PT-BR** · [English below](#english)

Controle e jogue no PC Windows pelo celular ou tablet Android: trackpad e teclado, espelhamento, modo Gaming com controle virtual, monitor virtual e o modo inverso (tela do celular no PC). Alternativa livre a Remote Mouse + Moonlight.

- **App Android** (`android/`): Kotlin/Compose, mínimo Android 10.
- **Host Windows** (`host/` + `tools/host/`): fork do [Apollo](https://github.com/ClassicOldSong/Apollo)/Sunshine, distribuído como série de patches em `tools/host/patches/duvo/` (base `adc5c5a0`).

## Como usar
1. Instale o host no PC (veja `docs/dev-setup-host.md`) e abra o Duvo na bandeja.
2. No painel do Duvo, clique em "Parear celular" e escaneie o QR com o app.
3. Escolha o modo: Controle, Espelhar, Gaming ou Monitor.

## Compilar
```
git clone --recurse-submodules <este repositório>
cd android && ./gradlew :app:assembleDebug
```
Host: `bash tools/host/apply-duvo-patches.sh` num clone do Apollo no commit `adc5c5a0` e `tools/host/build.sh` (MSYS2 UCRT64).

Assinatura de release: defina `DUVO_KEYSTORE`, `DUVO_KEYSTORE_PASSWORD`, `DUVO_KEY_ALIAS`, `DUVO_KEY_PASSWORD`. O keystore nunca vai para o repositório.

## Estado
Versão 0.1 (inicial). Ainda não há instalador do host nem atualização automática; veja `docs/superpowers/backlog.md`.

## Licença
GPLv3 ou posterior. Avisos de terceiros em [NOTICE](NOTICE) e `docs/licenses/`.

---
## English
Control and play on a Windows PC from an Android phone or tablet: trackpad and keyboard, screen mirroring, Gaming mode with a virtual gamepad, virtual monitor, and reverse mode (phone screen on the PC). A free alternative to Remote Mouse + Moonlight.

The Android app lives in `android/`; the Windows host is an Apollo/Sunshine fork shipped as a patch series (`tools/host/patches/duvo/`, base `adc5c5a0`). Build with `./gradlew :app:assembleDebug` after cloning with `--recurse-submodules`. Status: v0.1, no host installer or auto-update yet. License: GPL-3.0-or-later, third-party notices in `NOTICE`.

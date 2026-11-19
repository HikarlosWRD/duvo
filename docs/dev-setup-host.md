# Setup do host (fork do Apollo) no Windows

Registro dos comandos que funcionaram em 2026-11-17 (Windows 11, MSYS2 UCRT64).

## Versoes

- Apollo: `master` em `adc5c5a0` (v0.4.8-24), clonado em `host/` (remote `upstream`, branch local `duvo`). As mudancas do Duvo ficam como serie de patches em `tools/host/patches/duvo/` (ver secao 2).
- MSYS2 atualizado com `pacman -Syu`; gcc 16.2.0, cmake 4.5.0, ninja 1.13.2, OpenSSL 4.1.0, Boost de sistema 1.92 (nao usado, ver abaixo), libvpl 2.17.0
- Node.js v26.7.0 / npm 11.19.0 (instalacao oficial do Windows, fora do MSYS2, como o Apollo exige)

## 1. Dependencias (shell MSYS2 UCRT64)

```bash
pacman -Syu --noconfirm        # rodar duas vezes (a 1a fecha o terminal)
pacman -S --noconfirm --needed git mingw-w64-ucrt-x86_64-{boost,cmake,cppwinrt,curl-winssl,MinHook,miniupnpc,nsis,onevpl,openssl,opus,toolchain,nlohmann_json,ninja}
```

Nao instalar `mingw-w64-ucrt-x86_64-nodejs` (ver `host/docs/building.md`).

Uso nao interativo a partir de outro shell:
`MSYSTEM=UCRT64 CHERE_INVOKING=1 C:/msys64/usr/bin/bash.exe -lc "bash script.sh"`

## 2. Clone

```bash
git clone --recurse-submodules https://github.com/ClassicOldSong/Apollo host   # ~2.5 min
cd host && git remote rename origin upstream && git checkout -b duvo adc5c5a0
git submodule update --init --recursive   # submodulos na versao do adc5c5a0
```

`host/` e git-ignorado no repo raiz (ainda nao ha remote publico do fork). O codigo do Duvo no host vive
em `tools/host/patches/duvo/`: a serie completa `adc5c5a0..duvo` exportada com `git format-patch`
(inclui a correcao de OpenSSL 4 / `cfg.profile`). `tools/host/build.sh` chama
`tools/host/apply-duvo-patches.sh`, que aplica a serie com `git am` num clone em `adc5c5a0`. A serie conta como
aplicada somente se o assunto do ULTIMO patch (maior numero) existe em `git -C host log`; nesse caso nao faz nada.
Se `host/.git/rebase-apply` existe (`git am` interrompido) falha com "git am interrompido; rode git -C host am --abort";
se o HEAD nao e `adc5c5a0` e o ultimo patch nao esta no historico (serie parcial ou divergente) tambem falha, sem
aplicar por cima. Para aplicar sem compilar: `bash tools/host/apply-duvo-patches.sh [dir-do-host]` (ou defina
`DUVO_HOST_DIR`; padrao `host/`).

Depois de commitar no `host/` (branch `duvo`), regenerar a serie e commita-la no repo raiz:

```bash
rm -f tools/host/patches/duvo/*.patch
git -C host format-patch --no-signature --zero-commit -o ../tools/host/patches/duvo adc5c5a0..duvo
```

## 3. Build (um comando)

```bash
MSYSTEM=UCRT64 CHERE_INVOKING=1 C:/msys64/usr/bin/bash.exe -lc "bash <repo>/tools/host/build.sh"   # <repo> em formato MSYS, ex. /c/caminho/do/repo
```

`tools/host/build.sh` e idempotente e reprodutivel a partir de `host/build/` vazio (verificado: apagar `host/build`, rodar, gera `host/build/sunshine.exe` e `host/build/tests/test_sunshine.exe`). Ele: aplica a serie Duvo (secao 2); exporta `PROCESSOR_ARCHITECTURE=AMD64` e o Node.js no PATH; configura com `-DBUILD_TESTS=ON`; aplica o patch do Boost (`tools/host/patches/`); move temporariamente `test_http_pairing.cpp` (nao compila no upstream) e o restaura ao sair; roda `ninja`. Build limpo completo: ~6-8 min.

### Problemas encontrados e como o script/commit resolve

1. **`FFmpeg pre-compiled binaries not found at .../dist/Windows-`**: o shell MSYS nao exporta `PROCESSOR_ARCHITECTURE`, entao `CMAKE_SYSTEM_PROCESSOR` fica vazio. O script exporta `AMD64`. (Nao usar `-DFFMPEG_PREPARED_BINARIES=...`: cai no ramo "FFmpeg do sistema" e falha no link.)
2. **Boost**: Apollo exige Boost 1.89 EXATO (`find_package(... EXACT)`); o MSYS2 tem 1.92, entao nao ha como usar o Boost do sistema sem editar o CMake do Apollo; o CMake baixa o 1.89. Com cmake 4.x o Boost.Locale 1.89 falha em `source_group(TREE ...)`; o script aplica `tools/host/patches/boost-locale-source-group.patch` em `host/build/_deps/boost-src` apos o download e reconfigura.
3. **OpenSSL 4.x** (MSYS2 so tem a versao mais nova) e **bug upstream em master** (`cfg.profile` inexistente em `video::config_t`): corrigidos pelo primeiro patch da serie Duvo (`tools/host/patches/duvo/0001-fix-build-OpenSSL-4-compat-and-missing-cfg.profile.patch`, commit "fix(build): OpenSSL 4 compat and missing cfg.profile"), aplicado junto com o resto da serie pelo `build.sh`.

## 4. Testes do upstream

```bash
# BUILD_TESTS (OFF por padrao) ja e ligado por tools/host/build.sh
./host/build/tests/test_sunshine.exe             # o binario e build/tests/test_sunshine.exe
```

Pre-existentes (nao corrigidos):

- `tests/unit/test_http_pairing.cpp` **nao compila** (chama `clientpairingsecret(...)` com assinatura antiga; o Apollo mudou o parametro). `tools/host/build.sh` o move temporariamente para obter o binario e o restaura depois (nao e alterado no repo).
- Sem esse arquivo (resultado reproduzido em build limpo via script): 230 testes em 26 suites, ~6.5 s: **190 passaram, 7 skipped, 33 falharam**:
  - `ConfigConsistencyTest.AllConfigOptionsExistInAllFiles`, `ConfigOptionsInSameOrderWithinSections` (2)
  - `MdnsInstanceNameTests/MdnsInstanceNameTest.Run/3,4,5` (3)
  - `DisplayDeviceConfigTest/ParseRefreshRateOption.IntegrationTest/5,6,7,9` (4)
  - `DisplayDeviceConfigTest/DisplayModeRemapping.IntegrationTest/*` (varios, 24)
  - Skipped: encoders amdvce/quicksync e `MouseHIDTest` (sem hardware/driver).
- Nao foi investigado se alguma falha vem do ambiente (OpenSSL 4 / GCC 16 / sem driver) ou do codigo.
- Com a serie Duvo (2026-11-18): 245 testes em 29 suites: 205 passaram, 7 skipped, 33 falharam (as mesmas pre-existentes acima; em execucoes anteriores 2 casos de `DisplayModeRemapping` passaram, 207/31, o que depende do monitor/ambiente). Todos os 15 testes do Duvo (`DuvoConfig`, `PairQr` com os casos de OTP, `ClientPerm`) passam.

## 5. Executar o host (smoke test)

```bash
./build/sunshine.exe <caminho/sunshine.conf>
```

Subiu sem erros fatais (build stock, antes do Duvo): log mostra `Apollo version: 0.0.0.adc5c5a0.dirty`, mDNS registrado. Com a serie Duvo a porta base e 40989: GameStream HTTP em 40989, web UI em `https://localhost:40990` (log: `Configuration UI available at [https://localhost:40990]`); nao conflita com um Sunshine instalado (47984-47990). Avisos esperados: NvEnc YUV444/device nao existente (sem GPU NVIDIA nesta maquina de teste?), nvprefs undo file. Teste feito com config temporaria; driver de display virtual e ViGEm NAO foram instalados. Parear o Artemis nos aparelhos fisicos (Task 2 step 5) ficou pendente.

Obs.: ja existe um `sunshine.exe` / servico `sunshinesvc` anterior rodando na maquina (iniciado antes desta tarefa); nao foi tocado e ocupa as portas 47984-47990 (o host Duvo usa 40989/40990 e nao conflita).

Obs. importante: um Sunshine instalado ocupa a porta 47989 (e 47984-47990); isso so conflita com o build stock do Apollo. O host Duvo usa 40989/40990 e pode rodar em paralelo.

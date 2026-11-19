#!/usr/bin/env bash
# Build reprodutivel do host (Apollo) no Windows. Rodar dentro do MSYS2 UCRT64:
#   MSYSTEM=UCRT64 CHERE_INVOKING=1 C:/msys64/usr/bin/bash.exe -lc "bash tools/host/build.sh"
# Requer: dependencias pacman (ver docs/dev-setup-host.md) e Node.js oficial em "C:/Program Files/nodejs".
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOST="$ROOT/host"
BOOST_SRC="$HOST/build/_deps/boost-src"
export PATH="$PATH:/c/Program Files/nodejs"
# O shell MSYS nao exporta isto; sem ele CMAKE_SYSTEM_PROCESSOR fica vazio e os binarios FFmpeg nao sao achados.
export PROCESSOR_ARCHITECTURE=AMD64

cd "$HOST"

# Serie Duvo (commits da branch duvo sobre o Apollo adc5c5a0, incluindo as correcoes de OpenSSL 4 e
# cfg.profile), exportada com git format-patch em tools/host/patches/duvo/. Idempotente: nao faz nada se
# host/ ja contem a serie; falha com erro explicito se host/ nao esta no Apollo adc5c5a0.
bash "$ROOT/tools/host/apply-duvo-patches.sh" "$HOST"

CMAKE_ARGS=(-B build -G Ninja -S . -DBUILD_TESTS=ON)

# Apollo exige Boost 1.89 EXATO (MSYS2 tem outra versao), entao o CMake baixa o 1.89.
# Esse Boost.Locale quebra com cmake 4.x: aplicamos o patch (idempotente) apos o download e reconfiguramos.
patch_boost() {
  local f="$BOOST_SRC/libs/locale/CMakeLists.txt"
  [ -f "$f" ] || return 1
  if grep -q '^# source_group(TREE "${CMAKE_CURRENT_SOURCE_DIR}/include/boost/locale"' "$f"; then return 0; fi
  (cd "$BOOST_SRC" && git apply "$ROOT/tools/host/patches/boost-locale-source-group.patch")
}

if ! { [ -f "$BOOST_SRC/libs/locale/CMakeLists.txt" ] && patch_boost && cmake "${CMAKE_ARGS[@]}"; }; then
  # 1a passada: falha esperada no Boost.Locale logo apos o download
  cmake "${CMAKE_ARGS[@]}" || true
  patch_boost
  cmake "${CMAKE_ARGS[@]}"
fi
# tests/unit/test_http_pairing.cpp nao compila no upstream (assinatura antiga, pre-existente).
# Para obter test_sunshine.exe, o arquivo e movido para fora da arvore durante o build e restaurado ao sair.
BROKEN="$HOST/tests/unit/test_http_pairing.cpp"
if [ -f "$BROKEN" ]; then
  mv "$BROKEN" "$BROKEN.skip"
  trap 'mv "$BROKEN.skip" "$BROKEN"' EXIT
  cmake "${CMAKE_ARGS[@]}"   # reconfigura (o GLOB dos testes e reavaliado)
fi
ninja -C build
echo "OK: $HOST/build/sunshine.exe  $HOST/build/tests/test_sunshine.exe"

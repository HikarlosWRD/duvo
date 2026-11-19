#!/usr/bin/env bash
# Duvo - aplica a serie de patches do Duvo (tools/host/patches/duvo/) sobre um clone do Apollo.
# Copyright (C) 2026 Duvo contributors. Licenca: GPLv3 ou posterior (ver LICENSE).
#
# Uso: bash tools/host/apply-duvo-patches.sh [diretorio-do-host]
#      (padrao: $DUVO_HOST_DIR, ou <repo>/host se a variavel nao estiver definida)
# Idempotente: a serie conta como aplicada somente se o assunto do ULTIMO patch (maior numero) existe no
# historico do host. Serie parcial (git am interrompido ou HEAD divergente) falha com instrucoes.
# Num clone novo, o HEAD precisa estar no Apollo adc5c5a0 (git checkout -b duvo adc5c5a0).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOST="${1:-${DUVO_HOST_DIR:-$ROOT/host}}"
PATCHES="$ROOT/tools/host/patches/duvo"
APOLLO_BASE=adc5c5a0

shopt -s nullglob
files=("$PATCHES"/*.patch)
if [ "${#files[@]}" -eq 0 ]; then
  echo "ERRO: nenhum patch em $PATCHES" >&2
  exit 1
fi
last="${files[${#files[@]}-1]}"
# Assunto do ultimo patch: cabecalho Subject: sem o prefixo [PATCH n/m] (continuacoes de linha unidas).
subject="$(awk '/^Subject:/{s=$0; sub(/^Subject: */,"",s); sub(/^\[PATCH[^]]*\] */,"",s); r=1; next}
  r&&/^[[:space:]]/{t=$0; sub(/^[[:space:]]+/,"",t); s=s " " t; next} r{print s; exit}' "$last" | tr -d '[:cntrl:]')"
if [ -z "$subject" ]; then
  echo "ERRO: nao consegui ler o Subject de $last" >&2
  exit 1
fi

cd "$HOST"
gitdir="$(git rev-parse --absolute-git-dir)"
if [ -d "$gitdir/rebase-apply" ]; then
  echo "ERRO: git am interrompido em $HOST; rode git -C $HOST am --abort (e depois repita)" >&2
  exit 1
fi
# (sem pipe direto no grep -q: com pipefail o SIGPIPE do git log daria falso negativo)
subjects="$(git log --format=%s)"
if grep -Fxq -- "$subject" <<<"$subjects"; then
  echo "serie Duvo: ja aplicada em $HOST (ultimo patch: $subject)"
  exit 0
fi
base="$(git rev-parse --verify --quiet "$APOLLO_BASE^{commit}" || true)"
if [ -z "$base" ] || [ "$(git rev-parse HEAD)" != "$base" ]; then
  echo "ERRO: $HOST nao contem o ultimo patch da serie Duvo ('$subject') e o HEAD nao e o Apollo $APOLLO_BASE." >&2
  echo "      Serie parcial ou historico divergente: nao aplico por cima. Recrie o checkout limpo (git -C $HOST checkout -B duvo $APOLLO_BASE) e rode de novo," >&2
  echo "      ou, se voce tem trabalho local, regenere a serie (secao 2 de docs/dev-setup-host.md)." >&2
  exit 1
fi
# git am cria os commits da serie (autor preservado); so define um committer se o git nao tiver um.
ident=()
git config user.email >/dev/null || ident=(-c user.name="Duvo Build" -c user.email="build@duvo.invalid")
git "${ident[@]}" am --quiet "${files[@]}"
echo "serie Duvo: ${#files[@]} patches aplicados em $HOST"

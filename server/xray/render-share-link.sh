#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [[ -f .env.client ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env.client
  set +a
elif [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

: "${XRAY_USER_UUID:?Set XRAY_USER_UUID in server/xray/.env.client, .env, or the environment}"
: "${XRAY_REALITY_PUBLIC_KEY:?Set XRAY_REALITY_PUBLIC_KEY in server/xray/.env.client, .env, or the environment}"

if [[ -z "${XRAY_SERVER_ADDRESS:-}" && -n "${HOST:-}" ]]; then
  XRAY_SERVER_ADDRESS="${HOST##*@}"
fi

: "${XRAY_SERVER_ADDRESS:?Set XRAY_SERVER_ADDRESS or HOST to the VPS IP or domain}"

XRAY_SERVER_PORT="${XRAY_SERVER_PORT:-${XRAY_PORT:-443}}"
XRAY_REALITY_SERVER_NAME="${XRAY_REALITY_SERVER_NAME:-github.com}"
XRAY_REALITY_SHORT_ID="${XRAY_REALITY_SHORT_ID:-0123456789abcdef}"
XRAY_XHTTP_PATH="${XRAY_XHTTP_PATH:-/tunnel-light-xhttp}"

case "$XRAY_USER_UUID" in
  ????????-????-????-????-????????????) ;;
  *) echo "XRAY_USER_UUID must look like an xray uuid" >&2; exit 1 ;;
esac

case "$XRAY_SERVER_PORT" in
  *[!0-9]*|"") echo "XRAY_SERVER_PORT must be numeric" >&2; exit 1 ;;
esac

case "$XRAY_SERVER_ADDRESS" in
  *[!A-Za-z0-9.:-]*|"") echo "XRAY_SERVER_ADDRESS contains unsupported characters" >&2; exit 1 ;;
esac

case "$XRAY_REALITY_PUBLIC_KEY" in
  *[!A-Za-z0-9_-]*|"") echo "XRAY_REALITY_PUBLIC_KEY contains unsupported characters" >&2; exit 1 ;;
esac

case "$XRAY_REALITY_SERVER_NAME" in
  *[!A-Za-z0-9.-]*|"") echo "XRAY_REALITY_SERVER_NAME contains unsupported characters" >&2; exit 1 ;;
esac

case "$XRAY_REALITY_SHORT_ID" in
  *[!A-Fa-f0-9]*|"") echo "XRAY_REALITY_SHORT_ID must be hex" >&2; exit 1 ;;
esac

case "$XRAY_XHTTP_PATH" in
  /*) ;;
  *) echo "XRAY_XHTTP_PATH must start with /" >&2; exit 1 ;;
esac

case "$XRAY_XHTTP_PATH" in
  *[!A-Za-z0-9._~/-]*) echo "XRAY_XHTTP_PATH contains unsupported characters" >&2; exit 1 ;;
esac

urlencode() {
  local input="$1"
  local output=""
  local i char encoded
  LC_ALL=C
  for ((i = 0; i < ${#input}; i += 1)); do
    char="${input:i:1}"
    case "$char" in
      [a-zA-Z0-9.~_-]) output+="$char" ;;
      *) printf -v encoded '%%%02X' "'$char"; output+="$encoded" ;;
    esac
  done
  printf '%s' "$output"
}

encoded_path="$(urlencode "$XRAY_XHTTP_PATH")"
share_link="vless://${XRAY_USER_UUID}@${XRAY_SERVER_ADDRESS}:${XRAY_SERVER_PORT}?type=xhttp&security=reality&pbk=${XRAY_REALITY_PUBLIC_KEY}&sni=${XRAY_REALITY_SERVER_NAME}&sid=${XRAY_REALITY_SHORT_ID}&fp=chrome&path=${encoded_path}#Tunnel-Light"

mkdir -p generated
printf '%s\n' "$share_link" > generated/client-link.txt
printf '%s\n' "$share_link"

if [[ "${1:-}" == "--qr" ]]; then
  if command -v qrencode >/dev/null 2>&1; then
    qrencode -t ansiutf8 < generated/client-link.txt
  else
    echo "qrencode is not installed; install it or scan generated/client-link.txt another way" >&2
  fi
fi

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

XRAY_SERVER_ADDRESS="${XRAY_SERVER_ADDRESS:-host.docker.internal}"
XRAY_SERVER_PORT="${XRAY_SERVER_PORT:-18443}"
XRAY_REALITY_SERVER_NAME="${XRAY_REALITY_SERVER_NAME:-github.com}"
XRAY_REALITY_SHORT_ID="${XRAY_REALITY_SHORT_ID:-0123456789abcdef}"
XRAY_XHTTP_PATH="${XRAY_XHTTP_PATH:-/tunnel-light-xhttp}"
XRAY_LOCAL_SOCKS_LISTEN="${XRAY_LOCAL_SOCKS_LISTEN:-0.0.0.0}"
XRAY_LOCAL_SOCKS_PORT="${XRAY_LOCAL_SOCKS_PORT:-1080}"

case "$XRAY_USER_UUID" in
  ????????-????-????-????-????????????) ;;
  *) echo "XRAY_USER_UUID must look like an xray uuid" >&2; exit 1 ;;
esac

case "$XRAY_SERVER_PORT" in
  *[!0-9]*|"") echo "XRAY_SERVER_PORT must be numeric" >&2; exit 1 ;;
esac

case "$XRAY_LOCAL_SOCKS_PORT" in
  *[!0-9]*|"") echo "XRAY_LOCAL_SOCKS_PORT must be numeric" >&2; exit 1 ;;
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

mkdir -p generated

cat > generated/client-config.json <<EOF
{
  "log": {
    "loglevel": "warning"
  },
  "inbounds": [
    {
      "tag": "local-socks",
      "listen": "$XRAY_LOCAL_SOCKS_LISTEN",
      "port": $XRAY_LOCAL_SOCKS_PORT,
      "protocol": "socks",
      "settings": {
        "auth": "noauth",
        "udp": true
      }
    }
  ],
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "$XRAY_SERVER_ADDRESS",
            "port": $XRAY_SERVER_PORT,
            "users": [
              {
                "id": "$XRAY_USER_UUID",
                "encryption": "none"
              }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "xhttp",
        "xhttpSettings": {
          "path": "$XRAY_XHTTP_PATH",
          "mode": "auto"
        },
        "security": "reality",
        "realitySettings": {
          "serverName": "$XRAY_REALITY_SERVER_NAME",
          "publicKey": "$XRAY_REALITY_PUBLIC_KEY",
          "fingerprint": "chrome",
          "shortId": "$XRAY_REALITY_SHORT_ID"
        }
      }
    }
  ]
}
EOF

echo "Wrote server/xray/generated/client-config.json"

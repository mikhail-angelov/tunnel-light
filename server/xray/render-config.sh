#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

: "${XRAY_USER_UUID:?Set XRAY_USER_UUID in server/xray/.env or the environment}"
: "${XRAY_REALITY_PRIVATE_KEY:?Set XRAY_REALITY_PRIVATE_KEY in server/xray/.env or the environment}"

XRAY_REALITY_DEST="${XRAY_REALITY_DEST:-github.com:443}"
XRAY_REALITY_SERVER_NAME="${XRAY_REALITY_SERVER_NAME:-github.com}"
XRAY_REALITY_SHORT_ID="${XRAY_REALITY_SHORT_ID:-0123456789abcdef}"
XRAY_XHTTP_PATH="${XRAY_XHTTP_PATH:-/tunnel-light-xhttp}"

case "$XRAY_USER_UUID" in
  ????????-????-????-????-????????????) ;;
  *) echo "XRAY_USER_UUID must look like an xray uuid" >&2; exit 1 ;;
esac

case "$XRAY_REALITY_PRIVATE_KEY" in
  *[!A-Za-z0-9_-]*|"") echo "XRAY_REALITY_PRIVATE_KEY contains unsupported characters" >&2; exit 1 ;;
esac

case "$XRAY_REALITY_SERVER_NAME" in
  *[!A-Za-z0-9.-]*|"") echo "XRAY_REALITY_SERVER_NAME contains unsupported characters" >&2; exit 1 ;;
esac

case "$XRAY_REALITY_DEST" in
  *[!A-Za-z0-9.:-]*|"") echo "XRAY_REALITY_DEST contains unsupported characters" >&2; exit 1 ;;
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

cat > generated/config.json <<EOF
{
  "log": {
    "loglevel": "warning"
  },
  "inbounds": [
    {
      "tag": "vless-reality-xhttp",
      "listen": "0.0.0.0",
      "port": 443,
      "protocol": "vless",
      "settings": {
        "clients": [
          {
            "id": "$XRAY_USER_UUID",
            "flow": ""
          }
        ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "xhttp",
        "xhttpSettings": {
          "path": "$XRAY_XHTTP_PATH",
          "mode": "auto"
        },
        "security": "reality",
        "realitySettings": {
          "show": false,
          "dest": "$XRAY_REALITY_DEST",
          "xver": 0,
          "serverNames": [
            "$XRAY_REALITY_SERVER_NAME"
          ],
          "privateKey": "$XRAY_REALITY_PRIVATE_KEY",
          "shortIds": [
            "$XRAY_REALITY_SHORT_ID"
          ]
        }
      }
    }
  ],
  "outbounds": [
    {
      "tag": "direct",
      "protocol": "freedom"
    },
    {
      "tag": "blocked",
      "protocol": "blackhole"
    }
  ]
}
EOF

echo "Wrote server/xray/generated/config.json"

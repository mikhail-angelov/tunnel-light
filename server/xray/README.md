# Tunnel Light Xray Backend

This backend runs Xray-core with VLESS + REALITY + XHTTP on TCP port 443.

## Configure

Generate server values:

```bash
docker run --rm ghcr.io/xtls/xray-core:latest uuid
docker run --rm ghcr.io/xtls/xray-core:latest x25519
```

Create `server/xray/.env` from `.env.example`:

```bash
cp server/xray/.env.example server/xray/.env
```

Set:

- `XRAY_USER_UUID` to the generated UUID.
- Optional: `XRAY_SERVER_ADDRESS` to override the VPS IP or domain used by Android clients.
- `XRAY_REALITY_PRIVATE_KEY` to the generated private key.
- Keep the generated public key for the Android client.
- `XRAY_REALITY_PUBLIC_KEY` to the generated public key when rendering a local client test config.
- `XRAY_REALITY_SERVER_NAME` and `XRAY_REALITY_DEST` to the same TLS 1.3-capable camouflage host.
- `XRAY_REALITY_SHORT_ID` to a hex value also used by the client.
- `XRAY_XHTTP_PATH` to the same path used by the client.

Render the config:

```bash
./server/xray/render-config.sh
```

Render a local client test config:

```bash
./server/xray/render-client-config.sh
```

Render an Android import link:

```bash
./server/xray/render-share-link.sh
```

Render the same link as a terminal QR code when `qrencode` is installed:

```bash
./server/xray/render-share-link.sh --qr
```

## Validate

```bash
cd server/xray
docker compose run --rm --entrypoint xray xray run -test -config /etc/xray/config.json
docker compose -f docker-compose.test.yml --profile test run --rm --entrypoint xray xray-client run -test -config /etc/xray/client-config.json
```

## Run

```bash
cd server/xray
docker compose up -d
docker compose logs -f
```

Local client smoke test:

```bash
cd server/xray
XRAY_PORT=18443 docker compose up -d xray
docker compose -f docker-compose.test.yml --profile test up -d xray-client
curl --socks5-hostname 127.0.0.1:11080 https://www.google.com/generate_204 -I
docker compose -f docker-compose.test.yml --profile test down
docker compose down
```

If `11080` is already in use, override it:

```bash
XRAY_CLIENT_SOCKS_PORT=11081 docker compose -f docker-compose.test.yml --profile test up -d xray-client
curl --socks5-hostname 127.0.0.1:11081 https://www.google.com/generate_204 -I
```

Stop:

```bash
cd server/xray
docker compose down
```
